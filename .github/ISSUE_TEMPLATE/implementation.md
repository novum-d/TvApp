---
name: Android Implementation
about: Android BLE TV companion implementation task
title: "[impl] "
labels: ["implementation"]
assignees: []
---

# Summary

Describe the Android implementation goal clearly.

Example:

- implement BLE scan flow
- implement reconnect handling
- implement GATT operation queue
- add BLE logging screen

---

# Scope

Describe the exact implementation scope.

Out-of-scope items must NOT be implemented.

Examples of out-of-scope items:

- production backend integration
- analytics
- cloud sync
- user account system
- non-BLE networking
- major architecture changes

---

# Requirements

List concrete requirements.

- [ ]
- [ ]
- [ ]

Examples:

- [ ] scan nearby BLE devices
- [ ] connect to selected TV device
- [ ] display GATT services
- [ ] add reconnect handling
- [ ] add timeout handling
- [ ] add detailed BLE logs

---

# Acceptance Criteria

Implementation is complete when:

- [ ]
- [ ]
- [ ]

Examples:

- [ ] BLE scan works on Android 16 and 17
- [ ] reconnect works after TV standby recovery
- [ ] GATT operations are serialized
- [ ] logs display callback order and status codes
- [ ] no parallel BLE writes occur

---

# Technical Notes

Relevant architecture and implementation notes.

Examples:

- use existing package structure
- keep BLE logic isolated
- prefer explicit state handling
- use StateFlow for UI state
- use coroutine-based timeout handling
- avoid unnecessary abstraction layers
- preserve current BLE queue behavior

---

# Constraints

The implementation MUST follow `.codex/prompt.md`.

Do NOT:

- introduce databases
- introduce caching systems
- introduce unrelated networking
- introduce background workers unless explicitly requested
- add external BLE libraries unless explicitly approved
- over-engineer architecture
- replace Android BLE APIs with wrappers
- modify `.codex/prompt.md`
- add unrelated changes

Do NOT modify governance files:

- .codex/prompt.md
- .github/pull_request_template.md
- .github/ISSUE_TEMPLATE/*
- .github/CODEOWNERS

Keep implementation minimal and focused.

---

# Android BLE Requirements

Implementation should properly handle:

- Bluetooth OFF / ON
- permission denied
- app background state
- screen OFF
- reconnect after disconnect
- timeout behavior
- Android 16 / 17 behavior differences
- targetSdkVersion behavior differences

Do NOT rely only on callbacks without timeout handling.

Do NOT execute multiple GATT operations in parallel.

---

# Testing

Required validations before completion:

```bash
./gradlew ktlintCheck
./gradlew detekt
./gradlew test
./gradlew connectedCheck
````

Manual verification:

* [ ] BLE scan
* [ ] connect / disconnect
* [ ] reconnect
* [ ] notification receive
* [ ] command write
* [ ] Bluetooth OFF recovery
* [ ] TV standby recovery
* [ ] background behavior
* [ ] Android 16 / 17 comparison

---

# Deliverables

Expected outputs:

* implementation
* tests
* documentation updates if necessary
* manual verification notes
* BLE behavior observations if relevant

---

# Notes for Codex

Prefer:

* small readable functions
* explicit state transitions
* minimal dependencies
* readable BLE logs
* defensive timeout handling

Avoid:

* unnecessary abstractions
* macro-heavy implementations
* speculative features
* hidden BLE state management
* callback spaghetti

