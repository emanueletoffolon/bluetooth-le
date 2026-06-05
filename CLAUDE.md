# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build (clean + docgen + tsc + rollup)
npm run build

# Run Jest tests
npm test
npm run test:watch        # watch mode
npm run test:coverage     # with coverage

# Lint and format
npm run lint              # ESLint + Prettier check + SwiftLint
npm run fmt               # auto-fix all linting issues
npm run lint:android      # Android lint only

# Verify all platforms (runs tests + build for web, iOS, Android)
npm run verify
npm run verify:ios        # xcodebuild clean build test (requires macOS + Xcode)
npm run verify:android    # gradlew clean build test

# Regenerate API docs (README.md + dist/docs.json) from JSDoc in src/bleClient.ts
npm run docgen
```

Node v22.13.1 and npm v11.0.0 are pinned via Volta.

To run a single Jest test file:
```bash
npx jest src/bleClient.spec.ts
```

## Architecture

This is a tri-platform Capacitor plugin. The same BLE API is implemented in TypeScript (web), Swift (iOS), and Kotlin (Android).

### TypeScript layer (`src/`)

Two distinct API surfaces exist:

- **`BleClient`** (`src/bleClient.ts`) — The primary user-facing singleton. Implements `BleClientInterface`, wraps every call in a serial queue, handles data conversion between platforms, and manages event listener lifecycle. This is what app developers import and use.
- **`BluetoothLe`** (registered in `src/plugin.ts`) — The raw Capacitor plugin bridge. Mirrors the native plugin API (`BluetoothLePlugin` in `src/definitions.ts`). On web it delegates to `BluetoothLeWeb`; on native it calls through to Swift/Kotlin.

Supporting modules:
- `src/conversion.ts` — Public conversion helpers (`numbersToDataView`, `dataViewToText`, `numberToUUID`, `hexStringToDataView`, etc.). On native, characteristic values are transported as hex strings; `BleClient` converts to/from `DataView` transparently.
- `src/queue.ts` — Serial promise queue used by `BleClient` to prevent concurrent BLE operations from racing.
- `src/validators.ts` — UUID parsing/normalization (`parseUUID`). UUIDs must be 128-bit format (e.g. `0000180d-0000-1000-8000-00805f9b34fb`); `numberToUUID(0x180d)` converts 16-bit values.
- `src/timeout.ts` — Timeout wrapper for web operations.
- `src/config.ts` — `DisplayStrings` type for customizing the device picker UI.
- `src/web.ts` — Web implementation via the Web Bluetooth API.

### iOS layer (`ios/Sources/BluetoothLe/`)

- `Plugin.swift` — `CAPPlugin` subclass; thin dispatcher that delegates to `DeviceManager` and per-device `Device` instances.
- `DeviceManager.swift` — Owns the `CBCentralManager`. Handles scanning, device discovery, the `requestDevice` alert/list UI, and scan filter logic. Maintains a `callbackMap` keyed by operation name and a `timeoutMap` for cancellable `DispatchWorkItem` timeouts.
- `Device.swift` — Wraps a `CBPeripheral`. Manages connect/disconnect, service discovery, read/write/notify operations, and per-operation callbacks.
- `Conversion.swift` — Data conversion between `Data`/hex strings.
- `ScanFilters.swift` — `ManufacturerDataFilter` and `ServiceDataFilter` types.
- `DeviceListView.swift` — SwiftUI scrollable device picker (alternative to alert dialog).

### Android layer (`android/src/main/java/.../bluetoothle/`)

- `BluetoothLe.kt` — `@CapacitorPlugin` annotated class; handles permissions, dispatches to `Device` instances.
- `Device.kt` — Wraps a `BluetoothGatt`; manages all GATT operations.
- `DeviceScanner.kt` — Wraps `BluetoothLeScanner`; builds `ScanFilter` lists and manages scan lifecycle.
- `DeviceList.kt` — Tracks multiple connected devices.
- `Conversion.kt` — Hex string / byte array conversions.

### Data flow pattern

Native platforms cannot pass binary data through the Capacitor bridge, so characteristic values are always serialized as hex strings between native and JS. `BleClient` transparently converts: outgoing `DataView` → `dataViewToHexString` before calling native; incoming hex strings → `hexStringToDataView` before returning to the caller. The web implementation uses `DataView` natively throughout.

### Event listener key conventions

- Scan results: `'onScanResult'`
- Bluetooth state: `'onEnabledChanged'`
- Characteristic notifications: `'notification|{deviceId}|{service}|{characteristic}'`
- Disconnect: `'disconnected|{deviceId}'`

### Documentation generation

Public API docs are generated from JSDoc comments in `src/bleClient.ts` (the `BleClientInterface`) using `@capacitor/docgen`. Run `npm run docgen` (or `npm run build`) to regenerate `README.md` and `dist/docs.json`. A post-processing script (`scripts/fix-docgen-types.js`) patches the output before Prettier formats it.