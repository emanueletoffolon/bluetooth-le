package com.capacitorjs.community.plugins.bluetoothle

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.getcapacitor.Logger

/**
 * Foreground Service that owns BLE scanning and GATT connections.
 *
 * The key property of this service is that scanning and connectGatt() calls are
 * initiated with the Service as the Context. This is what enables BLE operations
 * to continue when the app is in the background or the screen is off.
 *
 * Usage: bind via BluetoothLe plugin, get the binder, call startScan/connect.
 */
@SuppressLint("MissingPermission")
class BleForegroundService : Service() {

    companion object {
        private val TAG = BleForegroundService::class.java.simpleName
        private const val NOTIFICATION_ID = 88421
        private const val CHANNEL_ID = "ble_foreground_service"
        private const val CHANNEL_NAME = "BLE Background Scanning"

        /**
         * The last ScanResult received via PendingIntent scan for the target device.
         * Written by BleScanReceiver (native, no WebView needed).
         * Read by JS via getLastFoundDevice() on the GPS heartbeat.
         */
        @Volatile
        var lastFoundResult: ScanResult? = null

        /**
         * Fallback for OEM devices (Xiaomi/MIUI) that deliver callbackType=FIRST_MATCH
         * via PendingIntent but with an empty EXTRA_LIST_SCAN_RESULTS list.
         * In this case we cannot get a ScanResult, but we know the device is advertising.
         * Set by BleScanReceiver, cleared alongside lastFoundResult.
         */
        @Volatile
        var lastFoundDeviceId: String? = null

        /**
         * The MAC address of the paired device to look for in PendingIntent scan results.
         * Set by startPendingIntentScan(), cleared by stopPendingIntentScan().
         */
        @Volatile
        var targetPairedDeviceId: String? = null

        /** Customizable notification title (default: "Bluetooth scanning active"). */
        @Volatile
        var notificationTitle: String = "Bluetooth scanning active"

        /** Customizable notification body text (default: empty). */
        @Volatile
        var notificationText: String = ""
    }

    inner class BleForegroundServiceBinder : Binder() {
        fun getService(): BleForegroundService = this@BleForegroundService
    }

    private val binder = BleForegroundServiceBinder()
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var deviceScanner: DeviceScanner? = null

    // Devices created with this Service as context — key is MAC address / deviceId
    private val serviceDeviceMap = HashMap<String, Device>()
    private var isScanning = false

    // PendingIntent-based scan state (survives WebView suspension)
    private var pendingIntentScanPI: PendingIntent? = null
    private var isPendingIntentScanning = false

    // Wake Lock — keeps CPU alive so BLE scan callbacks are not suspended
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // Re-acquire wake lock if Android restarted this service via START_STICKY
        if (intent == null && wakeLock?.isHeld != true) {
            acquireWakeLock()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        stopScanInternal()
        stopPendingIntentScanInternal()
        releaseWakeLock() // safety net in case stopScanInternal was not called
        val devicesToClean = serviceDeviceMap.values.toList()
        serviceDeviceMap.clear()
        devicesToClean.forEach { it.cleanup() }
        super.onDestroy()
    }

    // ---- Public API (called via binder from BluetoothLe plugin) ----

    /**
     * Start BLE scanning. The scanner is owned by this Service so scanning continues in background.
     */
    fun startScan(
        scanFilters: List<ScanFilter>,
        scanSettings: ScanSettings,
        allowDuplicates: Boolean,
        namePrefix: String,
        displayStrings: DisplayStrings,
        onStarted: (ScanResponse) -> Unit,
        onResult: ((ScanResult) -> Unit)?
    ) {
        val adapter = bluetoothAdapter ?: run {
            onStarted(ScanResponse(false, "Bluetooth not available", null))
            return
        }
        try {
            deviceScanner?.stopScanning()
        } catch (e: IllegalStateException) {
            Logger.error(TAG, "Error stopping previous scan: ${e.localizedMessage}", e)
        }
        // DeviceScanner is created with `this` (the Service) as Context
        deviceScanner = DeviceScanner(
            this,
            adapter,
            scanDuration = null,
            displayStrings = displayStrings,
            showDialog = false,
        )
        acquireWakeLock()
        deviceScanner?.startScanning(
            scanFilters,
            scanSettings,
            allowDuplicates,
            namePrefix,
            { response ->
                isScanning = response.success
                if (!response.success) releaseWakeLock()
                onStarted(response)
            },
            onResult
        )
    }

    /**
     * Stop BLE scanning and potentially stop the service if nothing else is active.
     */
    fun stopScan() {
        stopScanInternal()
        checkAndStopSelf()
    }

    /**
     * Create (or retrieve) a Device whose connectGatt() will use this Service as Context.
     * The returned Device is tracked internally and must be released via [removeDevice].
     *
     * @param deviceId MAC address of the remote device
     * @param onDisconnect callback invoked when the device disconnects unexpectedly
     * @return the Device, or null if deviceId is invalid or BT is unavailable
     */
    fun createDevice(deviceId: String, onDisconnect: (Int) -> Unit): Device? {
        val adapter = bluetoothAdapter ?: return null
        serviceDeviceMap[deviceId]?.let { return it }
        return try {
            val device = Device(this, adapter, deviceId) { status ->
                serviceDeviceMap.remove(deviceId)
                onDisconnect(status)
                checkAndStopSelf()
            }
            serviceDeviceMap[deviceId] = device
            device
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Remove a device from the service's tracking map (call after explicit disconnect).
     */
    fun removeDevice(deviceId: String) {
        serviceDeviceMap.remove(deviceId)
        checkAndStopSelf()
    }

    /**
     * Returns all device IDs currently owned by this service.
     */
    fun getServiceDeviceIds(): Set<String> = serviceDeviceMap.keys.toSet()

    // ---- PendingIntent scan API (survives WebView suspension) ----

    /**
     * Start a PendingIntent-based BLE scan for a specific paired device.
     *
     * Unlike regular BLE scan (which delivers results via notifyListeners → JS),
     * PendingIntent scan delivers results natively to BleScanReceiver even when
     * the WebView is fully suspended in Android standby. JS polls the result
     * via getLastFoundDevice() on the GPS heartbeat.
     *
     * @param deviceId MAC address of the target device
     * @return true if scan started, false if Bluetooth is unavailable
     */
    fun startPendingIntentScan(deviceId: String): Boolean {
        val adapter = bluetoothAdapter ?: return false
        val scanner = adapter.bluetoothLeScanner ?: return false

        // Stop any existing PendingIntent scan first (clears targetPairedDeviceId internally)
        stopPendingIntentScanInternal()

        // Set new target AFTER stopping old scan
        lastFoundResult = null
        lastFoundDeviceId = null
        targetPairedDeviceId = deviceId

        val filter = ScanFilter.Builder()
            .setDeviceAddress(deviceId)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        Logger.debug(BleScanReceiver.BG_SCAN_TAG, "startPendingIntentScan | deviceId=$deviceId scanMode=BALANCED filter=setDeviceAddress")

        val intent = Intent(this, BleScanReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(this, 0, intent, flags)
        pendingIntentScanPI = pi

        return try {
            scanner.startScan(listOf(filter), settings, pi)
            isPendingIntentScanning = true
            Logger.debug(TAG, "PendingIntent scan started for device $deviceId")
            Logger.debug(BleScanReceiver.BG_SCAN_TAG, "scan STARTED | device=$deviceId pi=$pi")
            true
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to start PendingIntent scan: ${e.localizedMessage}", e)
            Logger.error(BleScanReceiver.BG_SCAN_TAG, "scan START FAILED | ${e.localizedMessage}", e)
            pendingIntentScanPI = null
            false
        }
    }

    /**
     * Stop the PendingIntent-based BLE scan and clear the stored result.
     */
    fun stopPendingIntentScan() {
        stopPendingIntentScanInternal()
        checkAndStopSelf()
    }

    /**
     * Return the last ScanResult found by the PendingIntent scan, or null if not found yet.
     */
    fun getLastFoundDevice(): ScanResult? = lastFoundResult

    // ---- Private helpers ----

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BleForegroundService::BleWakeLock"
        )
        wakeLock?.acquire(10 * 60 * 1000L) // 10 min safety timeout
        Logger.debug(TAG, "Wake lock acquired")
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        Logger.debug(TAG, "Wake lock released")
    }

    private fun stopScanInternal() {
        try {
            deviceScanner?.stopScanning()
        } catch (e: IllegalStateException) {
            Logger.error(TAG, "Error stopping scan: ${e.localizedMessage}", e)
        }
        deviceScanner = null
        isScanning = false
        releaseWakeLock()
    }

    private fun stopPendingIntentScanInternal() {
        val pi = pendingIntentScanPI ?: return
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner != null) {
            try {
                scanner.stopScan(pi)
            } catch (e: Exception) {
                Logger.error(TAG, "Error stopping PendingIntent scan: ${e.localizedMessage}", e)
            }
        }
        pendingIntentScanPI = null
        targetPairedDeviceId = null
        lastFoundResult = null
        lastFoundDeviceId = null
        isPendingIntentScanning = false
        Logger.debug(TAG, "PendingIntent scan stopped")
        Logger.debug(BleScanReceiver.BG_SCAN_TAG, "scan STOPPED")
    }

    private fun checkAndStopSelf() {
        if (!isScanning && !isPendingIntentScanning && serviceDeviceMap.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Update the foreground notification while the service is running.
     * Call this after changing [notificationTitle] / [notificationText].
     */
    fun updateNotification(title: String, text: String) {
        notificationTitle = title
        notificationText = text
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
        if (notificationText.isNotEmpty()) {
            builder.setContentText(notificationText)
        }
        return builder.build()
    }
}