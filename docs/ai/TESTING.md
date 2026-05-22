# Testing

## Purpose

Testing should support BLE behavior verification without hiding platform behavior.
Automated tests are useful, but manual BLE verification remains required for BLE features.

## Required Checks Before PR

Run these checks before opening a pull request when the project supports them:

```bash
./gradlew ktlintCheck
./gradlew detekt
./gradlew test
./gradlew connectedCheck
```

If a check is unavailable or cannot run in the local environment, record that clearly in
the PR.

## Current Project State

The project currently includes the default unit and instrumentation test locations:

- `app/src/test/`
- `app/src/androidTest/`

BLE-specific tests have not been added yet.

## Automated Test Guidance

Use focused tests for:

- state reducers or state mapping
- timeout decisions
- operation queue ordering
- command serialization
- log formatting that affects verification

Avoid tests that mock away the behavior being verified unless the test is only checking
app-side state transitions.

## Manual BLE Verification

Before PRs that affect BLE behavior, verify the relevant items:

- BLE scan
- connect / disconnect
- reconnect
- notification receive
- command write
- Bluetooth OFF recovery
- TV standby recovery
- background behavior
- Android 16 / 17 comparison when relevant

For each relevant item, record:

- device or emulator used
- Android version
- target SDK if relevant
- observed BLE state transitions
- GATT status codes for failures
- known limitations

## Logging Expectations

BLE verification logs should include:

- timestamp
- thread name when useful
- callback name
- GATT status
- connection state
- operation type
- target device
- service or characteristic UUID when relevant

Do not treat missing logs as a UI-only issue.
Missing logs reduce BLE verification value.

## Pull Request Notes

PR descriptions must state:

- which checks ran
- which checks did not run
- manual BLE verification results when relevant
- unfinished areas or known limits
