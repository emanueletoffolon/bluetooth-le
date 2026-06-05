# BLE Foreground Service — Riepilogo implementazione

## Obiettivo

Permettere a `capacitor-community/bluetooth-le` di eseguire scan BLE e mantenere
connessioni GATT con lo schermo spento e l'app in background su Android.

La chiave è che `bluetoothLeScanner.startScan()` e `device.connectGatt()` devono
essere chiamati con un `Service` come Context, non con l'Activity.

---

## File creati

### `android/src/main/java/com/capacitorjs/community/plugins/bluetoothle/BleForegroundService.kt`

Nuovo `Service` Android che possiede internamente scan e connessioni GATT.

Responsabilità:
- Crea un `DeviceScanner(this, ...)` → lo scanner usa il Service come Context
- Crea `Device(this, ...)` → `connectGatt()` è owned dal Service
- Mostra una notifica persistente obbligatoria (requisito Android per i Foreground Service)
- Si ferma automaticamente (`stopForeground` + `stopSelf`) quando non ci sono
  scan attivi né dispositivi connessi
- Espone `BleForegroundServiceBinder` per permettere al plugin di chiamarne i metodi

Metodi esposti via binder:
| Metodo | Descrizione |
|--------|-------------|
| `startScan(filters, settings, allowDuplicates, namePrefix, displayStrings, onStarted, onResult)` | Avvia lo scan con Service context |
| `stopScan()` | Ferma lo scan |
| `createDevice(deviceId, onDisconnect)` | Crea un Device con Service context, lo traccia internamente |
| `removeDevice(deviceId)` | Rimuove il device dal tracking interno |
| `getServiceDeviceIds()` | Restituisce gli ID dei device gestiti dal service |

Notifica:
- Channel ID: `ble_foreground_service`
- Channel name: `BLE Background Scanning`
- Importance: `IMPORTANCE_LOW`
- Titolo: `Bluetooth scanning active`
- `foregroundServiceType`: `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` (API >= 29)

---

## File modificati

### `android/src/main/java/com/capacitorjs/community/plugins/bluetoothle/BluetoothLe.kt`

**Import aggiunti:**
```kotlin
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
```

**Campi aggiunti:**
```kotlin
private var bleService: BleForegroundService? = null
private var startForegroundServiceCall: PluginCall? = null
private val foregroundServiceDeviceIds = HashSet<String>()
private val serviceConnection = object : ServiceConnection { ... }
```

**Metodo aggiunto — `handleOnDestroy()`:**
Fa unbind dal service quando l'Activity viene distrutta, evitando leak.

**Metodi plugin aggiunti:**

`startForegroundService(call)`:
- Avvia il service con `startForegroundService(intent)` + `bindService(..., BIND_AUTO_CREATE)`
- Risolve la call in `onServiceConnected` (dopo che il bind è completato)
- Se il service è già bound, risolve subito

`stopForegroundService(call)`:
- Ferma lo scan via `service.stopScan()`
- Chiama `cleanup()` su tutti i device connessi tramite il service
- Li rimuove da `deviceMap` e `foregroundServiceDeviceIds`
- Chiama `unbindService()` + `stopService()`

**Metodi modificati:**

`requestLEScan()`:
- Se `bleService != null`: usa `service.startScan()` invece di creare un `DeviceScanner` diretto
- Altrimenti: comportamento originale invariato

`stopLEScan()`:
- Se `bleService != null`: usa `service.stopScan()`
- Altrimenti: comportamento originale invariato

`connect()`:
- Se `bleService != null`: crea il Device via `service.createDevice()` (Service context),
  lo registra in `deviceMap` e `foregroundServiceDeviceIds`, poi chiama `device.connect()`
  normalmente — tutte le operazioni successive (read/write/notify) funzionano invariate
- Altrimenti: comportamento originale invariato

`disconnect()`:
- Dopo il disconnect, se il device era gestito dal service:
  rimuove anche da `foregroundServiceDeviceIds` e chiama `service.removeDevice()`

---

### `android/src/main/AndroidManifest.xml`

Permessi aggiunti:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission
  android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"
  tools:targetApi="34" />
```

Dichiarazione service aggiunta dentro `<application>`:
```xml
<service
  android:name=".BleForegroundService"
  android:enabled="true"
  android:exported="false"
  android:foregroundServiceType="connectedDevice" />
```

---

### `src/definitions.ts`

Aggiunti all'interfaccia `BluetoothLePlugin`:
```typescript
startForegroundService(): Promise<void>;
stopForegroundService(): Promise<void>;
```

---

### `src/bleClient.ts`

Aggiunti all'interfaccia `BleClientInterface` (con JSDoc) e implementati in `BleClientClass`:
```typescript
async startForegroundService(): Promise<void> {
  await this.queue(async () => {
    await BluetoothLe.startForegroundService();
  });
}

async stopForegroundService(): Promise<void> {
  await this.queue(async () => {
    await BluetoothLe.stopForegroundService();
  });
}
```

---

## Perché funziona in background

| Scenario | Context di scan/connect | Comportamento |
|----------|------------------------|---------------|
| Senza foreground service | `Activity` | Scan sospeso a schermo spento |
| Con foreground service | `Service` | Scan e connessioni attivi in background ✓ |

Android riconosce che lo scan e il `connectGatt` sono stati avviati da un
`ForegroundService` con `foregroundServiceType="connectedDevice"` e li mantiene
attivi anche quando il processo dell'app non è in foreground.

---

## Integrazione nell'app Ionic

### 1. Build del plugin e sync

```bash
# Nella cartella del plugin
npm run build

# Nel progetto Ionic
npx cap sync android
```

### 2. Permesso notifiche (Android 13+)

Nel file `android/app/src/main/AndroidManifest.xml` del **tuo progetto**:
```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Richiedi il permesso a runtime prima di chiamare `startForegroundService()`:
```typescript
import { LocalNotifications } from '@capacitor/local-notifications';

if (Capacitor.getPlatform() === 'android') {
  await LocalNotifications.requestPermissions();
}
```

### 3. Codice app

```typescript
import { Capacitor } from '@capacitor/core';
import { BleClient } from '@capacitor-community/bluetooth-le';

async function startBackgroundScan() {
  // Inizializzazione normale
  await BleClient.initialize();

  // Avvia il foreground service (solo Android)
  if (Capacitor.getPlatform() === 'android') {
    await BleClient.startForegroundService();
    // Da questo momento appare la notifica "Bluetooth scanning active"
  }

  // Scan in background — funziona anche a schermo spento
  await BleClient.requestLEScan(
    { services: ['0000180d-0000-1000-8000-00805f9b34fb'] },
    (result) => {
      console.log('Device trovato:', result.device.deviceId);
    }
  );
}

async function connectInBackground(deviceId: string) {
  // Connessione persistente in background
  await BleClient.connect(deviceId, (id) => {
    console.log('Disconnesso:', id);
  });

  // Read/write/notify funzionano normalmente
  const value = await BleClient.read(deviceId, serviceUUID, charUUID);
}

async function stopAll() {
  await BleClient.stopLEScan();

  if (Capacitor.getPlatform() === 'android') {
    await BleClient.stopForegroundService();
    // → ferma scan, disconnette tutti i GATT, rimuove notifica
  }
}
```

---

## Regole d'uso

- `startForegroundService()` va chiamato **prima** di `requestLEScan()` e `connect()`
  se si vuole il comportamento in background
- Se non viene chiamato, il plugin funziona **esattamente come prima** (nessun breaking change)
- Su iOS e web i metodi esistono nell'interfaccia TS ma non hanno implementazione nativa:
  il bridge li ignorerà silenziosamente — usare sempre il check `Capacitor.getPlatform() === 'android'`
- `stopForegroundService()` fa cleanup completo: non è necessario chiamare
  `stopLEScan()` o `disconnect()` separatamente per i device gestiti dal service

---

## Checklist di verifica

- [ ] App può fare scan BLE con schermo spento
- [ ] App mantiene connessione GATT durante standby
- [ ] Notifica foreground appare quando il service è attivo
- [ ] `stopForegroundService()` ferma tutto e rimuove la notifica
- [ ] Comportamento invariato se `startForegroundService()` non viene chiamato
- [ ] Nessun memory leak: callback GATT puliti al disconnect
