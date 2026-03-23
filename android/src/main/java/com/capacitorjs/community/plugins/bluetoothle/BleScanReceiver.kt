package com.capacitorjs.community.plugins.bluetoothle

import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.getcapacitor.Logger

/**
 * BroadcastReceiver that receives PendingIntent-based BLE scan results.
 *
 * When Android delivers PendingIntent scan results, the WebView is not required to be alive.
 * Results are stored in BleForegroundService.lastFoundResult so JS can poll them on the
 * next GPS heartbeat tick via the getLastFoundDevice() plugin method.
 */
@SuppressLint("MissingPermission")
class BleScanReceiver : BroadcastReceiver() {

    companion object {
        private val TAG = BleScanReceiver::class.java.simpleName
        const val BG_SCAN_TAG = "BLE_BG_SCAN"
        // BluetoothLeScanner extras — use string literals to avoid SDK ref issues
        private const val EXTRA_LIST_SCAN_RESULTS = "android.bluetooth.le.extra.LIST_SCAN_RESULTS"
        private const val EXTRA_CALLBACK_TYPE = "android.bluetooth.le.extra.CALLBACK_TYPE"
        private const val EXTRA_ERROR_CODE = "android.bluetooth.le.extra.ERROR_CODE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val callbackType = intent.getIntExtra(EXTRA_CALLBACK_TYPE, -1)
        val errorCode = intent.getIntExtra(EXTRA_ERROR_CODE, -1)
        Logger.debug(BG_SCAN_TAG, "onReceive | callbackType=$callbackType errorCode=$errorCode action=${intent.action}")

        val results: ArrayList<ScanResult>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(
                EXTRA_LIST_SCAN_RESULTS,
                ScanResult::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(EXTRA_LIST_SCAN_RESULTS)
        }

        val targetId = BleForegroundService.targetPairedDeviceId
        Logger.debug(BG_SCAN_TAG, "results count=${results?.size ?: 0} | target=$targetId")

        if (!results.isNullOrEmpty()) {
            Logger.debug(BG_SCAN_TAG, "Devices: ${results.map { "${it.device.address} RSSI=${it.rssi}" }}")
        }

        // OEM bug (Xiaomi/MIUI and others): callbackType=FIRST_MATCH(1) is delivered with empty
        // results list. The device IS advertising but the BLE stack doesn't populate the extras.
        // Use the deviceId alone as a "found" signal so JS can proceed with reconnection.
        if (results.isNullOrEmpty()) {
            if (callbackType == 1 && targetId != null) {
                Logger.debug(BG_SCAN_TAG, "FIRST_MATCH with empty results (OEM bug) — marking $targetId as found by deviceId")
                BleForegroundService.lastFoundDeviceId = targetId
            }
            return
        }

        Logger.debug(TAG, "targetPairedDeviceId=$targetId, devices=${results.map { it.device.address }}")

        if (targetId == null) return

        for (result in results) {
            if (result.device.address == targetId) {
                Logger.debug(TAG, "PendingIntent scan: found target $targetId (RSSI=${result.rssi})")
                Logger.debug(BG_SCAN_TAG, "FOUND via ScanResult | deviceId=$targetId RSSI=${result.rssi}")
                BleForegroundService.lastFoundResult = result
                break
            }
        }
    }
}