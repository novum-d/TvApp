# BLE TV Companion Sample Codex Prompt

## Overview

Build an Android BLE verification app that maintains a long-lived connection with a TV device.

The purpose of this app is to verify BLE behavior before production implementation.

The app should help verify:

- scan
- connect / disconnect
- service discovery
- notification / indication
- command write
- reconnect
- timeout
- GATT operation queue
- foreground / background behavior
- TV standby / power-off handling
- detailed BLE logs

Avoid over-engineering.
This is a learning and verification app, not a production app.

---

# Tech Stack

- Kotlin
- Jetpack Compose
- MVVM
- StateFlow
- Coroutines
- Android BLE APIs

---

# Core Features

- BLE device scan
- Device list
- Connect / disconnect
- Show services and characteristics
- Read characteristic
- Write characteristic
- Subscribe to notification / indication
- Detailed BLE event log

---

# TV Always-Connected Features

- Save the last connected TV
- Auto reconnect to a known TV
- Reconnect on app launch
- Reconnect after Bluetooth OFF / ON
- Reconnect after the TV returns from standby
- Exponential backoff
- Manual reconnect button
- Connection state display

---

# Reliability Features

- GATT operation queue
- Connect timeout
- Service discovery timeout
- Write timeout
- Notification timeout / heartbeat
- Silent disconnect detection
- Last received timestamp
- Command retry policy

---

# TV Command Features

Example commands:

- power
- volume up / down
- mute
- input switch

Commands must be queued.
Rapid button taps must not send BLE writes in parallel.

---

# Logging

Log:

- timestamp
- thread name
- BLE callback name
- GATT status
- connection state
- operation type
- target device
- characteristic UUID

Logs must be visible in the app.

---

# Error Simulation

Add buttons for:

- close GATT
- force disconnect
- reconnect while connecting
- clear saved TV
- simulate command timeout

---

# Android Behavior Verification

Verify:

- permission denied
- Bluetooth OFF
- location setting OFF, if required
- app background
- screen OFF
- Doze
- process kill
- Android 16 / 17 comparison
- targetSdkVersion difference

---

# Architecture

Recommended structure:

```text
app/src/main/java/.../
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
````

---

# Implementation Rules

* Do not call multiple GATT operations in parallel
* Do not rely only on callbacks without timeout handling
* Do not hide GATT status codes
* Do not use global mutable state
* Do not over-abstract BLE logic
* Keep the code readable for review
* Prefer explicit state transitions

---

# MVP Priorities

1. BLE scan
2. connect / disconnect
3. detailed logs
4. service discovery
5. notification / indication
6. write command
7. GATT operation queue
8. timeout handling
9. reconnect handling
10. last connected TV persistence
11. background / standby verification

---

# Safety Rules

Codex must ask before:

* adding external BLE libraries
* adding a database
* adding background workers
* adding networking unrelated to BLE
* introducing complex architecture
* changing the MVP scope