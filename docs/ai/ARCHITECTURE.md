# Architecture

## Purpose

This app is a BLE verification and learning app for TV companion behavior.
It is not a production BLE framework.

Architecture decisions should favor:

- small, readable changes
- explicit BLE state transitions
- visible BLE callback and GATT status handling
- minimal abstraction over Android BLE APIs
- behavior that is easy to verify manually

## Current State

The project currently contains a basic Android app using:

- Kotlin
- Jetpack Compose
- Material 3
- Android Gradle Plugin

BLE modules have not been implemented yet.

## Intended Package Shape

When BLE features are added, keep the structure close to:

```text
app/src/main/java/io/novumd/tvapp/
├── ble/
│   ├── BleScanner.kt
│   ├── BleConnectionManager.kt
│   ├── GattOperationQueue.kt
│   ├── GattOperation.kt
│   └── BleLogger.kt
├── tv/
│   ├── TvDevice.kt
│   ├── TvCommand.kt
│   └── TvRepository.kt
├── ui/
│   ├── scan/
│   ├── device/
│   ├── logs/
│   └── settings/
└── MainActivity.kt
```

This structure is guidance, not permission for a large upfront refactor.
Add packages only when the related feature is implemented.

## BLE Rules

BLE operations must:

- execute sequentially
- avoid parallel GATT operations
- use explicit timeout handling
- log callback names and GATT status codes
- keep connection state transitions readable

BLE operations must not:

- hide Android BLE callbacks behind opaque wrappers
- silently swallow GATT errors
- add hidden retry behavior
- replace Android BLE APIs with external BLE libraries without approval

## State Management

Use MVVM-style state ownership with Kotlin coroutines and StateFlow where useful.
Keep state close to the feature that owns it.

Avoid global mutable state.

## UI Role

UI polish is secondary to BLE verification.
Screens should make BLE behavior observable:

- scan state
- connection state
- discovered services and characteristics
- operation status
- timestamps
- BLE event logs
- relevant errors

## Non-Goals

Do not introduce these without explicit approval:

- external BLE libraries
- databases
- background workers
- unrelated networking
- major architecture layers
- broad refactors unrelated to the current BLE verification step
