# AGENTS.md

## Purpose

This repository uses Codex to support:

1. Android BLE feature implementation
2. Small Android improvements
3. Validation and bug fixes
4. Draft PR creation
5. BLE behavior verification

Codex must prioritize safety, small changes, and minimal architectural impact.

This repository is primarily a BLE verification and learning project.

Avoid over-engineering.

---

## Required AI Documentation

Before making changes, Codex must read:

- docs/ai/SKILLS.md
- docs/ai/ARCHITECTURE.md
- docs/ai/DECISIONS.md
- docs/ai/TESTING.md
- .codex/prompt.md

---

## Optional Android Skills

Additional Android implementation references may exist under:

```text
.codex/skills/**
````

Codex may read relevant skills when necessary.

Examples:

* android-cli
* testing-setup
* navigation-3
* styles
* perfetto-trace-analysis
* edge-to-edge

These skills are supplemental references and do not override repository architecture rules.

---

## Allowed Changes

Codex may modify:

- app/**
- gradle/**
- docs/**
- tests/**
- README.md
- build.gradle.kts
- settings.gradle.kts
- gradle.properties

Codex should prefer small changes and avoid touching unrelated files.

---

## Forbidden Changes

Codex must not modify:

- .github/workflows/**
- secrets/**
- .env*
- signing configs
- production release configs
- CI infrastructure

---

## Human Approval Required

The following require approval:

- Workflow changes
- Major architecture changes
- Adding external BLE libraries
- Adding databases
- Adding background workers
- Adding unrelated networking
- Introducing new infrastructure
- Significant changes to reconnect behavior
- Changes that alter BLE operation queue semantics

---

## Allowed Scope

Prefer:

- BLE scan improvements
- reconnect handling
- timeout handling
- logging improvements
- validation fixes
- UI fixes
- documentation updates
- small reliability improvements

New Android dependencies are allowed when they:

- support the documented architecture
- remain scoped to the requested implementation
- do not hide core BLE behavior

Avoid:

- large refactors
- multi-feature PRs
- speculative abstractions
- replacing Android BLE APIs with wrappers

---

## BLE Architecture Rules

BLE operations must:

- execute sequentially
- avoid parallel GATT operations
- use explicit timeout handling
- expose GATT status codes in logs
- preserve readable state transitions

Do NOT:

- hide BLE callbacks
- silently swallow GATT errors
- rely only on callbacks without timeout handling
- introduce hidden retry behavior

---

## Android Requirements

The app should properly handle:

- Bluetooth OFF / ON
- permission denied
- app background state
- screen OFF
- reconnect after disconnect
- process recreation
- Android 16 / 17 behavior differences
- targetSdkVersion behavior differences

BLE behavior verification is more important than UI polish.

---

## Required Checks

Before PR:

```bash
./gradlew ktlintCheck
./gradlew detekt
./gradlew test
./gradlew connectedCheck
````

---

## Manual Verification

Before PR:

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

## Pull Request Rules

All PRs must:

* Be Draft PRs
* Use English section titles
* Write all section contents in Japanese
* Include summary
* Include changes
* Include manual verification steps
* Include BLE verification results when relevant
* PR title must exactly match the related Issue title
* Follow AGENTS.md naming conventions

---

## Writing Guidelines

Keep descriptions:

* concise
* concrete
* factual

Prefer:

* bullet points
* explicit limitations
* explicit non-goals
* clear verification steps

Avoid:

* vague wording
* marketing-style explanations
* exaggerated claims
* subjective statements

Clearly separate:

* implementation details
* architectural intent
* known limitations

Do not omit unfinished areas.

---

## Safety Rules

Codex must stop if:

* scope is unclear
* approval is required
* BLE behavior is ambiguous
* reconnect semantics may change
* Android lifecycle impact is uncertain

---

## Preferred Strategy

1. Read docs
2. Make minimal change
3. Validate BLE behavior
4. Run checks
5. Create Draft PR

---

## Immutable Governance Files

Codex must NOT modify the following files unless explicitly requested:

```text
.codex/prompt.md
AGENTS.md
.github/CODEOWNERS
.github/pull_request_template.md
.github/ISSUE_TEMPLATE/**
.github/workflows/**
```

These files define repository governance, architecture, and workflow policies.

---

## Naming Conventions

Use clear and human-readable names for:

* branches
* issues
* pull requests

Avoid vague names such as:

* fix
* update
* misc
* changes

Prefer descriptive names such as:

* implement-gatt-operation-queue
* add-ble-reconnect-handling
* add-ble-log-screen
* implement-timeout-handling
* fix-ble-disconnect-detection

---

## Branch Naming

Use:

```text
<type>/<short-description>
```

Examples:

```text
feat/add-ble-scan-screen
feat/implement-gatt-operation-queue
feat/add-reconnect-handling
fix/disconnect-timeout
chore/update-android17-targetsdk
```

Keep names concise and readable.

---

## Issue and Pull Request Naming

Issue titles and PR titles MUST be identical.

Examples:

```text
[impl] implement BLE scan screen
[impl] add GATT operation queue
[impl] add reconnect handling
[fix] prevent parallel GATT operations
```

The PR title must exactly match the related issue title.

Avoid unrelated wording changes.

---

## Pull Request Template

When creating pull requests, always use:

```text
.github/pull_request_template.md
```

All pull requests must follow the repository PR template.

---

## MVP Priorities

Prioritize implementation order:

1. BLE scan
2. connect / disconnect
3. detailed BLE logs
4. service discovery
5. notification / indication
6. write command
7. GATT operation queue
8. timeout handling
9. reconnect handling
10. last connected TV persistence
11. background / standby verification

Avoid over-engineering.

Keep the implementation simple and maintainable.
