# Bluetooth (BLE) link

The app has a deliberately small, verified Bluetooth foundation:

- scan for nearby BLE devices;
- let the rider select the bike once per VIN;
- connect, discover and display raw GATT services/characteristics;
- remember the selection and auto-connect on app foreground when enabled;
- reconnect with backoff after an unexpected disconnect;
- opt in to mirroring phone battery level and cellular signal to the cluster;
- keep mirroring alive in the background via a foreground service;
- resume mirroring after reboot when background mode and boot resume are on;
- poll the cluster's CONTROL characteristic read-only for diagnostics.

Selection and the auto-connect flag are stored app-private per VIN. Bluetooth addresses are
not logged, exported or backed up.

## Mirroring, background mode and boot resume

Mirroring sends only the verified idle phone-status (battery/signal) frame to the cluster. It
is **on by default** on a fresh install, but remains **session-scoped**: it stops when the
rider signs out, switches VIN, disconnects, or turns mirroring off.

| Setting | Default | Meaning |
| --- | --- | --- |
| Mirror phone status to cluster | on | Sends the idle battery/signal frame about once per second. |
| Keep mirroring in background | on | Runs a `connectedDevice` foreground service so mirroring survives leaving the app. |
| Resume mirroring after reboot | on | Auto-starts the link after boot when mirroring + background are both on and a bike is selected. |

`runsInBackground = mirroring && keepInBackground`. `resumesAfterBoot = startOnBoot && runsInBackground && armedVin != null`. With all defaults on and a bike selected, `resumesAfterBoot` is true.

The foreground service runs only while mirroring is active and background mode is on. It stops when mirroring is turned off, when background mode is turned off, or when the notification stop action is tapped. If notification permission is denied, the service still runs but no notification is shown — an in-app warning appears in Settings.

**Background model:** the `BikeLinkController` is a singleton in the application scope (`AppContainer`), so it outlives any screen. `BikeLinkService` is logic-thin: its `ACTION_START` handler calls `controller.ensureRunning()`, which uses the controller's existing connect-with-response logic. There is no indefinite background retry and no `connectGatt(autoConnect=true)`; after a reboot the link resumes once when the boot receiver starts the service. This is a documented limitation — if the bike is out of range at boot, the link will not keep retrying indefinitely.

Boot resume is handled by `BootReceiver` on `BOOT_COMPLETED`. It starts `BikeLinkService` only when `resumesAfterBoot` is true. The receiver has no `android:permission` attribute; it filters on the boot action in `onReceive`.

## CONTROL polling (read-only diagnostics)

When connected and mirroring is on, the cluster's CONTROL characteristic (`0a10676e-6972-6565-6e69-676e4543544f`) is polled every 700 ms. Each poll is a 20-byte read; the response is 26 bytes. This is **read only** — no actions are transmitted. Values are button counters and are surfaced in the Bike link screen's CONTROL diagnostics card.

CONTROL map (zero-based offsets):

| Offset | Meaning |
| --- | --- |
| `[0] & 0x0f` | Volume |
| `[1]` | Call accept |
| `[2]` | Call reject |
| `[3]` | Reject + SMS |
| `[4] & 0x3f` | Playlist page |
| `[5]` | Take-me-home |
| `[6]` | Resume |
| `[7]` | Pause |
| `[8]` | Next track |
| `[9]` | Previous track |
| `[10]` | Stop |
| `[11]` | Missed-call read |
| `[12]` | Alert read |
| `[13]` | Launch media |

Only verified frames may be transmitted. Caller name, missed-call, alert and turn-by-turn
frames remain disabled until a live non-idle capture confirms the cluster accepts them.

## Automated validation

```sh
./gradlew :core:ble:test
./gradlew :app:testDebugUnitTest --tests 'dev.pulsarconnect.app.ble.*'
./gradlew test :app:assembleDebug :app:lintDebug
```

Regression tests cover cancellation cleanup, stale discovery callbacks, permission
and adapter changes during reconnect backoff, changing the reconnect preference,
per-VIN isolation, Android GATT discovery/disconnect handoff, MTU negotiation,
stale MTU callbacks, oversized-write rejection, battery/signal source permission
changes, sender cadence/heartbeat wrapping, opt-in lifecycle stops, background
mirroring defaults, boot resume gating, and service start/stop lifecycle.

These tests use fake transports and Robolectric, not a real motorcycle. They do
not establish hardware compatibility or validate cluster rendering.

## Verified Android phone-status subset

The evidence comes from the Android APK's native encoder, not the iOS JavaScript
encoder in the same bundle. The JavaScript path uses a different byte-0 value and
an additive checksum; neither applies to Android.

| Item | Verified value |
| --- | --- |
| Service | `0010676e-6972-6565-6e69-676e4543544f` |
| GENERAL characteristic | `0210676e-6972-6565-6e69-676e4543544f` |
| GENERAL frame size | 55 bytes |
| Write type | GATT write-with-response |
| Captured ATT MTU | 146 |
| Required ATT MTU | at least 58 (55-byte payload plus 3-byte ATT header) |
| Cadence | about 1 second; battery refreshed about every 5 seconds |

Idle GENERAL layout, zero-based offsets:

| Offset | Value |
| --- | --- |
| 0 | `0xc0 \| volume`; the capture shows `0xc5` for no headset and default volume 5 |
| 1 | quantized battery level shifted left by 3; call state is zero |
| 2 | cellular signal level `0..4` |
| 3 | 0 |
| 4 | 1 |
| 5-52 | zero; caller fields stay empty for this idle subset |
| 53 | heartbeat, starting at 1, wrapping 255 to 0 |
| 54 | zero; the Android encoder does not add a checksum |

Battery quantization:

| Battery percent | Level |
| --- | --- |
| below 20 | 0 |
| 20 to below 40 | 1 |
| 40 to below 60 | 2 |
| 60 to below 80 | 3 |
| 80 or above | 4 |

Unavailable battery and unavailable signal are encoded as 0. That is the wire
fallback, not a claim that the phone measured a low value. Signal is Android's
`SignalStrength.level`, not BLE RSSI or dBm. An unavailable value is only sent
after the rider opts in.

`0xc5` is **not** a magic or sync header. It is the observed `0xc0 | 5` default.
The meanings of the always-set `0xc0` bits are not established, so the app must
not assign them protocol semantics.

The on-device HCI capture contains 151 GENERAL writes across two bike
connections. All 151 match the idle layout above byte-for-byte, including zero
bytes 5-52, heartbeat in byte 53, and byte 54 zero. This validates the idle
subset only; it does not prove receiver behaviour for other firmware variants.

## What remains disabled

The native code also contains static layouts for call and missed-call frames, but
the available capture has no non-idle caller or missed-call frames. The app does
not transmit them:

- MISS_CALL (`0310676e-6972-6565-6e69-676e4543544f`) is 74 bytes.
- ALERT (`0410676e-6972-6565-6e69-676e4543544f`) is 40 bytes.
- GENERAL caller text occupies byte 20 (UTF-8 length, capped at 30) and bytes
  21-50 in the native static analysis.

Those layouts are research notes, not enabled features. Caller names and missed
calls stay off until a live non-idle capture confirms the cluster accepts them.

Live speed/RPM/fuel/gear telemetry is still not established. The capture's ACTION
characteristic (`0a10676e-6972-6565-6e69-676e4543544f`) returns 26-byte read
responses, but their meaning is not decoded and the values observed were not
sufficient to justify a decoder. A naive parser that split the ACL payload
incorrectly reported 22 bytes; the reassembled response is 26.

ATT handles in the capture (`0x0025` writes, `0x002b` reads) are capture-local and
must not be hardcoded. Classic Bluetooth HFP and OBEX exchanges also appear on
another connection; their ownership has not been established, so they must not be
attributed to the bike or used as proof of its call/contacts protocol.

## Needed capture: non-idle phone status

A new HCI snoop log should capture an incoming call while the official Android app
is connected to the bike. Do not publish caller names, phone numbers or raw
payloads.

1. On the Android phone, enable **Developer options → Enable Bluetooth HCI snoop log**.
2. Turn the bike ignition and kill switch on. Keep the bike stationary and in
   neutral; do not ride while handling the phone.
3. Open the official Bajaj Ride Connect app and connect to the bike.
4. From a second phone, call the connected phone while leaving the call
   unanswered long enough to observe the cluster, then optionally answer and end.
   Do not take the bike on the road.
5. Stop the snoop log, then collect a bug report:

   ```bash
   mkdir -p captures
   adb bugreport captures/hci-bugreport.zip
   ```

6. Extract the file named like `btsnoop_hci.log` / `btsnoop_hci.log.*` from the
   bug report zip. Keep it in `captures/`, which is gitignored.

If the phone is rooted, the live file is commonly under
`/data/misc/bluetooth/logs/btsnoop_hci.log`; pull it with root access instead of
taking a bug report.

## Before sharing a capture

The log contains Bluetooth addresses and raw payloads, and a bug report contains
device/system metadata. Never attach the raw file to an issue or commit it.
Share only the extracted BLE section after replacing addresses and removing
unrelated traffic, or reproduce the observations as text. Never include a VIN,
access code, token, location or ride history.
