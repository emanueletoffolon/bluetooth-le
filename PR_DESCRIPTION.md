# feat(android): add BLE Foreground Service for background scanning and persistent GATT connections

## Problem

On Android, BLE scanning and GATT connections are suspended when the screen turns off
and the app moves to the background. This is by design: Android aggressively limits
background processes to preserve battery life.

This limitation breaks real-world use cases where the user needs to keep the phone in
a pocket while the app continues to work. A concrete example is an **e-bike companion
app** that records GPX tracks: the user starts a ride, presses record, puts the phone
in their pocket, and expects the app to keep collecting GPS + BLE sensor data
(speed, cadence, power, heart rate) from the bike's Bluetooth peripherals throughout
the whole ride.

Without this fix, as soon as the screen turns off:
- Any ongoing BLE scan is throttled to `SCAN_MODE_LOW_POWER` and eventually suspended
- Existing GATT connections may be dropped because Android reclaims the resources
- The app cannot reconnect because `bluetoothLeScanner.startScan()` called from an
  Activity context is deprioritised by the OS when the app is not in the foreground

On **iOS this problem does not exist**: Core Bluetooth handles background BLE
transparently. Only Android is affected.

---

## Root cause

The Android Bluetooth stack treats BLE operations differently depending on which
**Context** initiated them:

| Initiating context | Screen off / background behaviour |
|--------------------|------------------------------------|
| `Activity` | Scan throttled → suspended; GATT connections at risk |
| `ForegroundService` | Scan and connections kept alive ✓ |

The existing plugin always initiated `bluetoothLeScanner.startScan()` and
`device.connectGatt()` from the Activity (or its `applicationContext`). Simply
starting a `ForegroundService` alongside the existing code is **not enough** —
the scan and connect calls must themselves originate from within the service.

---

## Solution

Introduce an Android `ForegroundService` (`BleForegroundService`) that **owns**
BLE scanning and GATT connections. The JS layer can opt-in by calling
`startForegroundService()` before `requestLEScan()` / `connect()`.

When the foreground service is active, the plugin routes scan and connect calls
through the service so that:

1. `DeviceScanner` is instantiated with `this` (the Service) as `Context`
2. `Device` is instantiated with `this` (the Service) as `Context`, so
   `device.connectGatt(context, ...)` is called from the service — the OS keeps
   the GATT connection alive in the background

All other plugin methods (`read`, `write`, `startNotifications`, etc.) continue to
work unchanged because they operate on the `Device` object reference, which is still
stored in the plugin's `deviceMap`.

When `startForegroundService()` is not called, the plugin behaves exactly as before
(**no breaking changes**).

---

## What was implemented

### 1. `BleForegroundService.kt` (new file)

A standard Android `Service` (not `IntentService`) with the following
characteristics:

**Lifecycle:**
- Started with `startForegroundService(intent)` + `bindService(...)` from the plugin
- Calls `startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)`
  in `onStartCommand` — this is the mechanism that tells Android to keep the process
  alive and allow background BLE operations
- Automatically calls `stopForeground` + `stopSelf()` when there are no active scans
  and no connected devices (via `checkAndStopSelf()`)

**Persistent notification (mandatory for Foreground Services):**
- Channel ID: `ble_foreground_service`
- Channel name: `BLE Background Scanning`
- Importance: `IMPORTANCE_LOW` (no sound, minimal intrusion)
- Title: `"Bluetooth scanning active"` (customisable via `setForegroundServiceNotification`)
- `foregroundServiceType`: `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` (API ≥ 29)

**Binder (`BleForegroundServiceBinder`):**
Exposes the service instance to the plugin so the plugin can call its methods directly
after binding.

**Public API (called from the plugin via binder):**

| Method | Description |
|--------|-------------|
| `startScan(filters, settings, allowDuplicates, namePrefix, displayStrings, onStarted, onResult)` | Creates `DeviceScanner(this, ...)` — scanner context is the Service |
| `stopScan()` | Stops the scanner; calls `checkAndStopSelf()` |
| `createDevice(deviceId, onDisconnect)` | Creates `Device(this, ...)` — `connectGatt()` will be called with Service context; tracks the device internally |
| `removeDevice(deviceId)` | Removes a device from internal tracking after explicit disconnect |
| `getServiceDeviceIds()` | Returns the set of device IDs currently owned by the service |
| `updateNotification(title, text)` | Updates the live foreground notification content |

**Internal state:**
- `serviceDeviceMap: HashMap<String, Device>` — tracks all devices connected through
  the service; used for cleanup on `onDestroy()`
- `isScanning: Boolean` — used by `checkAndStopSelf()` to decide when to stop

---

### 2. `BluetoothLe.kt` (modified)

**New fields:**
```kotlin
private var bleService: BleForegroundService? = null
private var startForegroundServiceCall: PluginCall? = null
private val foregroundServiceDeviceIds = HashSet<String>()
private val serviceConnection: ServiceConnection
```

`foregroundServiceDeviceIds` tracks which device IDs were connected via the foreground
service so that `stopForegroundService()` knows which devices to clean up.

**`handleOnDestroy()` override:**
Unbinds from the service when the Activity is destroyed to prevent a leak.

**New plugin method — `startForegroundService()`:**
1. Starts the service with `startForegroundService(intent)` (required on API ≥ 26 to
   trigger `onStartCommand` before the 5-second ANR timeout)
2. Calls `bindService(intent, serviceConnection, BIND_AUTO_CREATE)`
3. Stores the `PluginCall` and resolves it inside `onServiceConnected` — this ensures
   the JS `await` returns only after the bind is complete and the binder is available

**New plugin method — `stopForegroundService()`:**
1. Calls `service.stopScan()` to stop any ongoing scan
2. Iterates over `foregroundServiceDeviceIds`, calls `device.cleanup()` and removes
   each device from both `deviceMap` (plugin) and the service's internal map
3. Calls `unbindService()` + `stopService()` to fully terminate the service
4. The foreground notification is removed automatically by the service

**Modified — `requestLEScan()`:**
```
if (bleService != null) → service.startScan(...)   // background-capable
else                    → existing DeviceScanner    // original behaviour
```

**Modified — `stopLEScan()`:**
```
if (bleService != null) → service.stopScan()
else                    → deviceScanner?.stopScanning()
```

**Modified — `connect()`:**
```
if (bleService != null) → bleService.createDevice(deviceId, onDisconnect)
                          deviceMap[deviceId] = device  // plugin still owns the ref
                          foregroundServiceDeviceIds.add(deviceId)
                          device.connect(timeout, ...)  // called normally
else                    → existing getOrCreateDevice()  // original behaviour
```

The critical point: `Device` is constructed with the **Service** as `context`, so
`device.connectGatt(context, ...)` is called from the service process. Subsequent
operations (read, write, notifications) work unchanged because they use the same
`Device` object stored in `deviceMap`.

**Modified — `disconnect()`:**
After a successful disconnect, if the device was connected via the foreground service,
removes it from `foregroundServiceDeviceIds` and calls `service.removeDevice()` so
the service can call `checkAndStopSelf()` correctly.

---

### 3. `AndroidManifest.xml` (modified)

**New permissions:**
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission
  android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"
  tools:targetApi="34" />
```

`FOREGROUND_SERVICE_CONNECTED_DEVICE` is required on Android 14+ (API 34) for any
foreground service that uses `foregroundServiceType="connectedDevice"`.

**New service declaration:**
```xml
<application>
  <service
    android:name=".BleForegroundService"
    android:enabled="true"
    android:exported="false"
    android:foregroundServiceType="connectedDevice" />
</application>
```

`exported="false"` ensures the service cannot be started by other apps.
`foregroundServiceType="connectedDevice"` is the type that grants Android permission
to keep BLE connections alive in the background.

---

### 4. `src/definitions.ts` (modified)

Added to `BluetoothLePlugin`:
```typescript
setForegroundServiceNotification(options: { title: string; body?: string }): Promise<void>;
startForegroundService(): Promise<void>;
stopForegroundService(): Promise<void>;
```

---

### 5. `src/bleClient.ts` (modified)

Added to `BleClientInterface` (with JSDoc) and implemented in `BleClientClass`:
```typescript
async startForegroundService(): Promise<void>
async stopForegroundService(): Promise<void>
async setForegroundServiceNotification(options: { title: string; body?: string }): Promise<void>
```

All three delegate to the native plugin through the serial queue (same pattern as
every other method in `BleClientClass`) to preserve operation ordering.

---

### 6. `src/web.ts` (modified)

Added stub implementations that throw `unavailable` (same pattern as other
Android-only methods like `requestEnable`):
```typescript
async startForegroundService(): Promise<void> {
  throw this.unavailable('startForegroundService is not available on web.');
}
async stopForegroundService(): Promise<void> {
  throw this.unavailable('stopForegroundService is not available on web.');
}
```

---

## API usage example (e-bike GPX recording app)

```typescript
import { Capacitor } from '@capacitor/core';
import { BleClient } from '@capacitor-community/bluetooth-le';

// Called when the user taps "Start Recording"
async function startRecording(bikeDeviceId: string) {
  await BleClient.initialize();

  if (Capacitor.getPlatform() === 'android') {
    // Start the foreground service — persistent notification appears.
    // From this point on, scan and connect calls are owned by the service.
    await BleClient.startForegroundService();

    await BleClient.setForegroundServiceNotification({
      title: 'E-bike recording active',
      body: 'Recording your ride in background',
    });
  }

  // Works in background on Android thanks to the foreground service.
  // On iOS works natively without any extra setup.
  await BleClient.connect(bikeDeviceId, async (id) => {
    console.warn('Disconnected from bike:', id);
    // Optionally re-scan and reconnect here
  });

  // Subscribe to speed/cadence/power characteristics
  await BleClient.startNotifications(bikeDeviceId, CSC_SERVICE, CSC_CHAR, (value) => {
    recordDataPoint(value);
  });
}

// Called when the user taps "Stop Recording"
async function stopRecording() {
  if (Capacitor.getPlatform() === 'android') {
    // Stops scan, disconnects all GATT connections, removes notification.
    await BleClient.stopForegroundService();
  } else {
    await BleClient.disconnect(bikeDeviceId);
  }

  saveGpxFile();
}
```

---

## Backward compatibility

- If `startForegroundService()` is never called, **all existing behaviour is
  unchanged**. The new code paths are guarded by `if (bleService != null)`.
- iOS and web are unaffected. The new methods exist in the TS interface but throw
  `unavailable` on web and are simply not present natively on iOS (the JS layer
  should guard with `Capacitor.getPlatform() === 'android'`).
- All existing plugin methods (`read`, `write`, `startNotifications`, `createBond`,
  etc.) continue to work unchanged regardless of whether the foreground service is
  active.

---

## Testing checklist

- [ ] BLE scan continues with screen off (foreground service active)
- [ ] GATT connection maintained during device standby
- [ ] Foreground notification appears when service starts
- [ ] Notification removed when `stopForegroundService()` is called
- [ ] `stopForegroundService()` disconnects all GATT connections cleanly
- [ ] All existing plugin methods work normally without calling `startForegroundService()`
- [ ] No memory leaks: GATT callbacks cleaned up on disconnect
- [ ] Service stops itself automatically when scan stops and all devices disconnect
- [ ] App does not crash on activity destroy while service is bound

---

## Android API level compatibility

| Feature | Min API |
|---------|---------|
| `ForegroundService` | 26 (O) |
| `startForegroundService()` | 26 (O) |
| `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` | 29 (Q) |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` permission | 34 (U) |
| `STOP_FOREGROUND_REMOVE` | 24 (N) |

All SDK version branches are guarded with `Build.VERSION.SDK_INT` checks.
