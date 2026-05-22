# Decisions

This document records repository-level decisions for AI-assisted changes.
Keep entries concise and factual.

## Accepted Decisions

### Use Android BLE APIs Directly

Use platform Android BLE APIs for scan, connection, GATT callbacks, reads, writes,
notifications, and indications.

Reason:

- this app exists to verify real Android BLE behavior
- hiding platform behavior would reduce verification value

Impact:

- callbacks and GATT status codes must remain visible in logs
- external BLE libraries require approval

### Keep BLE Operations Sequential

Only one GATT operation may be active at a time.

Reason:

- Android GATT behavior is sensitive to parallel operations
- command writes from rapid UI input must not run concurrently

Impact:

- read, write, subscribe, and service discovery work must use explicit ordering
- changes to operation queue semantics require approval

### Prefer Explicit Timeout Handling

BLE operations that wait for callbacks must have explicit timeouts.

Reason:

- Android BLE callbacks can be delayed or missing
- timeout behavior must be visible during verification

Impact:

- timeout logs should include operation type and target UUID when relevant
- relying only on callbacks is not sufficient

### Keep Reconnect Behavior Conservative

Reconnect behavior should be added gradually and remain easy to inspect.

Reason:

- reconnect can affect app lifecycle, Bluetooth OFF / ON recovery, and TV standby tests
- hidden retries make manual verification harder

Impact:

- significant reconnect semantic changes require approval
- retry and backoff behavior must be documented when implemented

### Documentation May Lead Implementation

When required AI documentation is missing, add concise documentation before code changes.

Reason:

- AGENTS.md requires these documents to be read before modifications
- future code changes need stable guidance

Impact:

- documentation should describe current project state and known limits
- avoid documenting unimplemented behavior as completed

## Pending Decisions

These decisions are intentionally not finalized yet:

- exact GATT operation queue API
- reconnect backoff values
- last connected TV persistence storage
- notification heartbeat policy
- command retry policy
- Android 16 / 17 comparison procedure
