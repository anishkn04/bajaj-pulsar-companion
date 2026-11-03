# Bajaj Pulsar Companion App

An independent Android companion for Bajaj Pulsar motorcycles, built with Kotlin and Jetpack Compose. It keeps accepted login credentials encrypted on your device and provides local records you can open with the bike off and without a network connection.

This is an interoperability research project, not an official Bajaj app. Initial sign-in needs a registered Bajaj account, your VIN and dealer access code, and internet access. Compatibility with every model and future Bajaj API changes is not guaranteed.

## Available now

- **Persistent sign-in:** Android Keystore-backed AES-GCM storage for credentials and tokens; silent re-authentication when tokens are rejected.
- **Offline bike details:** the matching VIN's model/nickname is cached locally; a failed background refresh does not block local records.
- **Manual trip log:** enter start/end odometer readings, resume an unfinished trip after restarting, and browse paginated history with distance and duration.
- **Service reminders:** create reminders with a due odometer, a calendar date, or both; mark them complete. Either threshold makes a reminder due, whichever comes first.
- **Optional background alerts:** local WorkManager checks notify you of due reminders, with Android permission/channel controls, persistent duplicate suppression, and sign-out/opt-out cancellation.
- **Bike link foundation:** scan for nearby BLE devices, remember the selected bike per VIN, connect and inspect raw GATT services, and optionally auto-connect while the app is in the foreground.
- **Background mirroring and boot resume:** a `connectedDevice` foreground service keeps the bike link alive when the app is backgrounded; after reboot the link resumes when mirroring, background mode and boot resume are all on and a bike is selected. Mirroring, background and boot are all on by default on a fresh install.
- **Opt-in battery and signal mirroring:** send only the verified idle phone-status frame to the cluster, with MTU-safe writes. Session-scoped and read-only CONTROL polling for diagnostics. No cluster telemetry is decoded yet.
- **Per-bike persistence:** Android-native SQLite stores profiles, trips, telemetry records and reminders in app-private storage.
- **Safe recovery:** failed local reads offer a read-only retry; a failed refresh after a successful save does not invite duplicate writes.

The reminder odometer is a recorded trip reading, **not live vehicle data**. Reminder dates use the device's time zone. Trips are manually entered, not automatically captured or synced with Bajaj. The Bluetooth screen shows raw GATT services and an experimental, opt-in battery/signal mirror; it does not decode cluster values or create trips.

## Bike link and phone-status mirroring

The Bluetooth link keeps the bike connection alive outside any screen. Selection and the
auto-connect flag are stored app-private per VIN. Bluetooth addresses are never logged,
exported, or backed up.

Open **Bike link**, grant Bluetooth access, and pick your bike. Once connected, the screen
shows the discovered GATT services and characteristics. Open **Settings** to control mirroring,
background mode and boot resume.

- **Mirror phone status to cluster** (on by default): sends a 55-byte idle phone-status write
  about once per second; the heartbeat wraps from 255 to 0. It stops when you sign out, switch
  VIN, disconnect, or turn the switch off. Returning to the foreground requires a fresh opt-in
  if background mode is off.
- **Keep mirroring in background** (on by default): runs a `connectedDevice` foreground service
  so mirroring survives leaving the app. Stops when you turn mirroring off. If notification
  permission is denied, the service still runs but no notification is shown; Settings offers a
  shortcut to grant it.
- **Resume mirroring after reboot** (on by default): after reboot, the link resumes when
  mirroring + background mode + boot resume are all on and a bike is selected. There is no
  indefinite background retry — if the bike is out of range at boot, the link resumes once and
  waits for the next foreground or reconnect attempt.
- **Auto-connect** retries an opted-in connection on app foreground, including after Bluetooth
  or permission availability changes. Disabling it keeps a healthy connection open but stops
  pending retries and prevents reconnection after the next disconnect.
- The app requests an ATT MTU of 146 before the first long write and requires at least 58 bytes.
  If the bike cannot negotiate enough space, the app fails the write instead of truncating or
  inventing chunking.
- **Battery** needs no permission. **Cellular signal** additionally needs optional phone-state
  permission; without it, or without telephony support, battery still mirrors and signal is sent
  as unavailable. The app never reads contacts, call logs, phone numbers, SMS, or message
  content.
- A peer that does not expose the writable GENERAL characteristic is shown as incompatible, and
  nothing is sent.

Read-only CONTROL polling: when connected and mirroring is on, the cluster's CONTROL
characteristic is polled every 700 ms (20-byte read, 26-byte response). Button counters are
shown in the Bike link diagnostics card. No actions are transmitted.

The frame format comes from the official Android APK's native encoder, not its iOS JavaScript
path. Only the idle battery/signal subset is enabled. Caller names and missed-call indication
are deliberately **disabled** until a live non-idle capture confirms the cluster accepts them;
see [the BLE notes](docs/recon/BLE.md).

## Background service alerts

Open **Service reminders → Enable alerts** to opt in. Android 13 and newer request notification permission only after you tap the button. If permission or the Service reminders channel is blocked, the screen shows delivery as blocked and offers Android notification settings. You can still view and manage reminders without allowing alerts.

- Background checks run approximately every **12 hours**, with an additional queued check after sign-in/restore, recorded odometer changes, reminder changes, or returning to alert settings. Overlapping changes queue a follow-up check instead of cancelling an in-flight delivery.
- WorkManager persists scheduling across process restarts and device reboots. Android battery restrictions, Doze and force-stop can delay or prevent execution; these are **not exact-time alarms**. Open the app to review service dates rather than relying solely on notifications.
- Checks use only the currently signed-in VIN's local records. No network or running motorcycle is required. Without a saved odometer, date reminders still work, but odometer-only reminders need a recorded trip reading.
- Each reminder is recorded as delivered once; dismissing an alert, reopening the app, or disabling/re-enabling alerts does not deliberately repeat it. Reminders blocked by permission/channel settings are not marked delivered.
- Completing a reminder clears its alert. Signing out or disabling alerts cancels scheduled checks and displayed service alerts. Sign-out retains your device's opt-in preference; signing back in resumes checks.
- Notifications contain generic text and open the app. They do not include a VIN, title, access code, token or location, including on the lock screen.

## Build and test

Requires JDK 21, Android SDK 36 and a configured `local.properties` or `ANDROID_HOME`. The app supports Android 8.0/API 26 and newer.

```sh
./gradlew test
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Core tests use JUnit 5. App tests use JUnit 4, Robolectric and WorkManager's test helpers, including native SQLite, notification permission/channel behavior, persistent delivery suppression, sign-out races, and overlapping-work scheduling on API 26 and 35. The first test run needs network access for dependencies and Robolectric's Android SDK jars; Gradle's `--offline` does not control Robolectric's separate SDK resolver. Sandboxed runs also need a writable Java `user.home` for Robolectric's lock/cache, and a writable Gradle cache.

Tests use synthetic credentials and deterministic clocks. No motorcycle or live Bajaj account is needed. The BLE suite additionally covers MTU negotiation, oversized-write rejection, battery/signal source behavior, sender cadence, and the opt-in lifecycle using fake transports and Robolectric. These tests do **not** establish real-bike compatibility or prove how the cluster renders values.

## Architecture

```text
app/                  Compose screens, ViewModel, Keystore, native SQLiteOpenHelper
core/model/           Domain types: VIN, credentials, session, trips, reminders
core/session/         Session persistence behind the SecretStore interface
core/network/         Bajaj API client and authentication orchestration
core/ble/             Pure-JVM BLE scanning, connection state machine, phone-status protocol and sender
core/data/            Android-safe repository contracts, shared rules, test doubles
core/data-jdbc/       Desktop/JVM SQLite adapter and its integration tests
```

The Android app depends on `core/data`, **not** `core/data-jdbc`. Desktop SQLite JDBC native libraries must not be packaged into the APK. Android persistence uses framework SQLite, without a new production dependency.

## Privacy and sign-out

Credentials and bearer tokens are encrypted. Bike profiles, trip data, telemetry and reminders are **not separately database-encrypted**; they rely on Android's application sandbox and device storage protection. There is no in-app lock.

Signing out removes saved credentials and tokens but **retains local records per VIN**. Signing back into that VIN shows them again. Clearing app storage or uninstalling removes the local database; there is no export/backup workflow yet. Do not rely on this research app as your only copy of service history.

No analytics or developer-controlled server receives these records. Sign-in and vehicle refresh contact Bajaj. Battery and signal readings are used only for the opt-in mirror and are not stored. See [the security guide](docs/SECURITY.md).

## API recovery

The initial API contract was recovered from the official `com.bajajconnect.ib` v1.1.14 APK. See [API notes](docs/recon/api.md) and [capture instructions](docs/recon/CAPTURE.md).

- `appToken` from `/api/whoami` authorizes login and most documented endpoints.
- `userToken` from login authorizes `/api/ib/rc/customer/vehicles`.
- Silent sign-in relies on persisting the user's access code; no refresh-token endpoint was found.

This does not establish why the official app exhibits the reported login problem or prove that its own storage is absent.

## Still planned

- **Bluetooth protocol decoding:** live cluster telemetry and automatic trip capture. Blocked on a targeted HCI snoop capture with the bike switched on; see [the BLE capture guide](docs/recon/BLE.md). No protocol is invented here.
- **Caller and missed-call mirroring:** static frame layouts were recovered, but no live non-idle frames have been captured, so these stay disabled.
- Cloud trip sync, export/restore and navigation integration.
- On-device testing with the bike and a connected Android phone, including a smoke test of background mirroring and boot resume on real hardware.

## Contributing

Use test-first changes and conventional commit messages. Never commit real VINs, access codes, tokens or raw captures. Use the synthetic VIN `MD2ABCDEFGH123456` in tests and redact identifying ride/location data before sharing logs.
