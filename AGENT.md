# Agent Guide

Instructions for any coding agent working in this repository. Read this file and `tasks.md`
before changing code.

## Start of every session

1. Read `tasks.md`.
2. Check `git status` and `git log --oneline -5`.
3. Pick the highest-priority unchecked task unless the rider asks for something specific.
4. Mark it `[~]` in `tasks.md` as part of your first change.
5. When done, mark it `[x]` and add the commit short hash.

Do not silently expand scope. Add new work to `tasks.md` first.

## Project

An independent Android companion for Bajaj Pulsar motorcycles: Kotlin + Jetpack Compose,
offline-first local records, and an opt-in BLE link that mirrors phone status to the cluster.
It is an interoperability research project, not an official Bajaj app.

### Modules

| Path | Purpose |
| --- | --- |
| `app/` | Compose UI, ViewModel, app-private Keystore storage, Android SQLite, BLE platform adapters, foreground service, WorkManager |
| `core/model/` | Domain types: session, trips, reminders, bike profile |
| `core/session/` | Session persistence behind `SecretStore` |
| `core/network/` | Bajaj API client and authentication orchestration |
| `core/ble/` | Pure-JVM BLE scanning, connection state machine, phone-status/call protocol, sender |
| `core/data/` | Repository contracts, shared rules, in-memory test doubles |
| `core/data-jdbc/` | Desktop/JVM SQLite adapter and integration tests |

The Android app depends on `core/data`, **not** `core/data-jdbc`. Never package desktop JDBC
natives into the APK.

## Non-negotiable constraints

- **Kotlin only.** Never add Java source to the app or core modules.
- **Google Material icons only.** Do not add custom in-app vector icon drawables.
- **No alerts or turn-by-turn features.** Navigation integration is out of scope.
- **No vehicle-specific constants.** No hard-coded VIN, model quirks, or dealer access codes.
- **Never commit secrets or personal data:** credentials, access codes, tokens, VINs, Bluetooth
  MAC addresses, notification captures, bugreports, APKs, AABs, keystores, or passwords.
- **Never put a real VIN or MAC in code, tests, docs, previews, fixtures, or commit messages.**
  Use obviously synthetic values (`MD2ABCDEFGH123456`, `AA:BB:CC:DD:EE:FF`).
- **No analytics** and no developer-controlled server receiving rider records.
- **Preserve these behaviors:** BLE/session handling, phone-status mirroring, the foreground
  service, boot resume, reminders and notification suppression, permission handling,
  diagnostics, the safety disclaimer, and sign-out semantics.
- Mirroring settings are remembered across ordinary restarts; **only explicit sign-out**
  disarms them.
- Caller-name and missed-call mirroring stay opt-in and **off by default**. Unknown callers show
  `"Incoming call"`. Never send or store a phone number.
- Background reconnect must stay **low battery**: passive GATT (`autoConnect = true`) plus a
  watchdog, not periodic scanning or tight polling.
- Do not push a release tag or create a release unless the rider explicitly asks.

## Build and test

Requires JDK 21, Android SDK 36, and a configured `local.properties` or `ANDROID_HOME`.
Minimum Android version is API 26.

```sh
./gradlew test
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- Core tests use JUnit 5. App tests use JUnit 4 + Robolectric, including real framework SQLite
  and WorkManager test helpers.
- First run needs network access for dependencies and Robolectric SDK jars.
- Prefer the narrowest test task while iterating, then run `./gradlew test` before committing.
- Tests must use synthetic credentials, deterministic clocks, and fake BLE transports. No live
  account or motorcycle is required.
- The BLE suite proves protocol/state-machine behavior, not real-bike rendering compatibility.

## Definition of done

A task is complete only when all of these hold:

1. Acceptance notes in `tasks.md` are met.
2. Behavior changes have tests; bug fixes have a regression test that fails without the fix.
3. `./gradlew test` passes.
4. `./gradlew :app:assembleDebug :app:lintDebug` passes for UI/platform changes.
5. No sensitive data or generated artifacts are staged (`git status` reviewed).
6. The change is committed with a Conventional Commit and pushed to `main`.
7. `tasks.md` is updated in the same commit (`[x]` plus the short hash).

## Commit and push conventions

- Conventional Commits: `feat(scope): …`, `fix(scope): …`, `test(scope): …`, `docs: …`,
  `build(ci): …`, `refactor(scope): …`.
- Include the task ID in the body, for example `Refs: PC-04`.
- Keep commits focused; one task per commit where practical.
- Push to `main` with the `anishkn04` GitHub account. If the push is rejected, rebase onto
  `origin/main`; do not force-push shared history.
- Version lives only in `gradle.properties` (`project.version`, `project.versionCode`). Bump
  both only for a release the rider asked for.

## Code map

| Area | Files |
| --- | --- |
| App wiring | `app/src/main/kotlin/dev/pulsarconnect/app/AppContainer.kt`, `ui/PulsarConnectApp.kt` |
| State | `app/src/main/kotlin/dev/pulsarconnect/app/ui/AppViewModel.kt`, `ble/BleViewModel.kt` |
| Screens | `app/src/main/kotlin/dev/pulsarconnect/app/ui/HomeScreen.kt`, `TripHistoryScreen.kt`, `RemindersScreen.kt`, `BikeConnectionScreen.kt`, `SettingsScreen.kt` |
| Compose primitives | `app/src/main/kotlin/dev/pulsarconnect/app/ui/PulsarComponents.kt`, `ui/theme/` |
| BLE connection | `core/ble/src/main/kotlin/dev/pulsarconnect/core/ble/BikeConnection.kt` |
| Link policy | `core/ble/src/main/kotlin/dev/pulsarconnect/core/ble/BikeLinkController.kt` |
| Protocol | `core/ble/src/main/kotlin/dev/pulsarconnect/core/ble/PhoneStatusProtocol.kt`, `CallProtocol.kt` |
| Android BLE adapters | `app/src/main/kotlin/dev/pulsarconnect/app/ble/AndroidBle*.kt` |
| Foreground service / boot | `app/src/main/kotlin/dev/pulsarconnect/app/ble/BikeLinkService.kt`, `BootReceiver.kt` |
| Notifications | `app/src/main/kotlin/dev/pulsarconnect/app/notifications/`, `ble/BikeLinkService.kt` |
| Call listening | `app/src/main/kotlin/dev/pulsarconnect/app/calls/` |
| Repositories | `core/data/src/main/kotlin/dev/pulsarconnect/core/data/Repository.kt` |
| Android persistence | `app/src/main/kotlin/dev/pulsarconnect/app/data/AndroidDatabase.kt` |
| Domain types | `core/model/src/main/kotlin/dev/pulsarconnect/core/model/DataModels.kt` |
| CI / release | `.github/workflows/ci.yml`, `.github/workflows/release.yml` |
| Protocol notes | `docs/recon/BLE.md`, `docs/SECURITY.md` |

## Task tracking protocol

- `tasks.md` is the backlog, not a changelog. Keep it current in every commit.
- Add new tasks with the next `PC-##` ID under the correct priority. Include acceptance notes
  specific enough to verify.
- If a task is blocked, mark `[!]` and write the unblock condition on the same line.
- If a task turns out to be wrong or unnecessary, do not delete it silently: mark it done with a
  one-line reason, or move it to a "Dropped" note in the commit body and remove it with the
  rider's agreement.
- Do not reorder priorities on your own; ask the rider first.

## Safety and privacy notes

- The local trip/reminder database is **not** separately encrypted; it relies on the Android
  app sandbox. Do not claim otherwise in UI or docs.
- `allowBackup="false"` means there is no OS-level backup. PC-01/PC-02 are the backup story.
- Diagnostic output and exports must redact VIN and Bluetooth addresses and must never include
  credentials, tokens, notification content, or coordinates unless the rider explicitly opts in.
- The app must remain usable with the bike off and without a network connection, except for
  sign-in and bike-profile refresh.
