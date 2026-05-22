# Android BLE Skill

## Important Rules

- Never execute parallel GATT operations
- Always implement timeout handling
- Always log GATT status codes
- Prefer explicit reconnect state machines
- Do not rely only on callbacks
- Handle Bluetooth OFF / ON
- Handle Android background behavior
- Verify Android 16 / 17 differences

## Common BLE Pitfalls

- GATT 133
- stale BluetoothGatt instance
- missing close()
- notification race conditions
- reconnect loops
- MTU timing issues
- service discovery timing
- OEM BLE stack differences