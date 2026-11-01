# ADR 0001 — Pure-Kotlin core, Android shell

- Status: accepted
- Date: 2026-11-01

## Context

Most of this app's value is not UI. It is the rules around sessions,
persistence, protocol framing and offline behaviour: exactly the code that is
slowest to test through an emulator and easiest to get wrong. The app also
needs to be verifiable without a motorcycle, a working Bajaj account, or a
network.

## Decision

Split the codebase into plain Kotlin/JVM modules and a thin Android app:

```
core/model     domain types (VIN, credentials, session)
core/session   session persistence behind a SecretStore port
core/data      local database + offline-first repositories
core/ble       GATT client + frame codec
core/network   API client + auth orchestration
app            Jetpack Compose UI, Android adapters
```

- `core/*` must not depend on the Android SDK. Android specifics sit behind
  ports (`SecretStore`, clock, dispatcher) and are implemented in `app`.
- Tests in `core/*` run on the JVM with JUnit 5 and MockWebServer.
- The `app` module wires real implementations (Keystore, Room, Bluetooth) and
  holds UI.

## Consequences

- Session, protocol and API logic are tested in milliseconds, in CI, with no
  emulator.
- The Android layer stays small enough to review.
- A little ceremony for ports and dependency injection.
- `java.time` works on all supported versions because the app enables
  core-library desugaring; the core modules are JVM-only anyway.

## Alternatives considered

- **Single Android module with Robolectric.** Slower tests, and most logic
  would still not touch the framework. Rejected.
- **Kotlin Multiplatform.** No iOS target is planned; the extra source-set
  machinery earns nothing today.
- **Java.** The user asked for Kotlin, and Kotlin's null-safety and
  coroutines fit the concurrency requirements.
