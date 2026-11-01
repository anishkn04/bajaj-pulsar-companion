# Bajaj Pulsar Companion App

An independent Android companion for Bajaj Pulsar motorcycles, built with Kotlin and Jetpack Compose. It keeps accepted login credentials encrypted on your device and provides local records you can open with the bike off and without a network connection.

This is an interoperability research project, not an official Bajaj app. Initial sign-in needs a registered Bajaj account, your VIN and dealer access code, and internet access. Compatibility with every model and future Bajaj API changes is not guaranteed.

## Available now

- **Persistent sign-in:** Android Keystore-backed AES-GCM storage for credentials and tokens; silent re-authentication when tokens are rejected.
- **Offline bike details:** the matching VIN's model/nickname is cached locally; a failed background refresh does not block local records.
- **Manual trip log:** enter start/end odometer readings, resume an unfinished trip after restarting, and browse paginated history with distance and duration.
- **Service reminders:** create reminders with a due odometer, a calendar date, or both; mark them complete. Either threshold makes a reminder due, whichever comes first.
- **Per-bike persistence:** Android-native SQLite stores profiles, trips, telemetry records and reminders in app-private storage.
- **Safe recovery:** failed local reads offer a read-only retry; a failed refresh after a successful save does not invite duplicate writes.

The reminder odometer is a recorded trip reading, **not live vehicle data**. Reminder dates use the device's time zone. Reminders are checked while viewing the app; background notifications are not implemented. Trips are manually entered, not automatically captured or synced with Bajaj.

## Build and test

Requires JDK 21, Android SDK 36 and a configured `local.properties` or `ANDROID_HOME`. The app supports Android 8.0/API 26 and newer.

```sh
./gradlew test
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Core tests use JUnit 5. App tests use JUnit 4 and Robolectric, including native SQLite on API 26 and 35. The first test run needs network access for dependencies and Robolectric's Android SDK jars; Gradle's `--offline` does not control Robolectric's separate SDK resolver. Sandboxed runs also need a writable Java `user.home` for Robolectric's lock/cache, and a writable Gradle cache.

Tests use synthetic credentials and deterministic clocks. No motorcycle or live Bajaj account is needed. BLE/device integration has not been validated by these tests.

## Architecture

```text
app/                  Compose screens, ViewModel, Keystore, native SQLiteOpenHelper
core/model/           Domain types: VIN, credentials, session, trips, reminders
core/session/         Session persistence behind the SecretStore interface
core/network/         Bajaj API client and authentication orchestration
core/data/            Android-safe repository contracts, shared rules, test doubles
core/data-jdbc/       Desktop/JVM SQLite adapter and its integration tests
```

The Android app depends on `core/data`, **not** `core/data-jdbc`. Desktop SQLite JDBC native libraries must not be packaged into the APK. Android persistence uses framework SQLite, without a new production dependency.

## Privacy and sign-out

Credentials and bearer tokens are encrypted. Bike profiles, trip data, telemetry and reminders are **not separately database-encrypted**; they rely on Android's application sandbox and device storage protection. There is no in-app lock.

Signing out removes saved credentials and tokens but **retains local records per VIN**. Signing back into that VIN shows them again. Clearing app storage or uninstalling removes the local database; there is no export/backup workflow yet. Do not rely on this research app as your only copy of service history.

No analytics or developer-controlled server receives these records. Sign-in and vehicle refresh contact Bajaj. See [the security guide](docs/SECURITY.md).

## API recovery

The initial API contract was recovered from the official `com.bajajconnect.ib` v1.1.14 APK. See [API notes](docs/recon/api.md) and [capture instructions](docs/recon/CAPTURE.md).

- `appToken` from `/api/whoami` authorizes login and most documented endpoints.
- `userToken` from login authorizes `/api/ib/rc/customer/vehicles`.
- Silent sign-in relies on persisting the user's access code; no refresh-token endpoint was found.

This does not establish why the official app exhibits the reported login problem or prove that its own storage is absent.

## Still planned

- Bluetooth protocol recovery and automatic cluster telemetry/trip capture. Needs a capture with the bike ignition on; no protocol is invented here.
- Background service reminder notifications.
- Cloud trip sync, export/restore and navigation integration.
- On-device testing with the bike and a connected Android phone.

## Contributing

Use test-first changes and conventional commit messages. Never commit real VINs, access codes, tokens or raw captures. Use the synthetic VIN `MD2ABCDEFGH123456` in tests and redact identifying ride/location data before sharing logs.
