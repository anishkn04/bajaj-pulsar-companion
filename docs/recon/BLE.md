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

## Verified link and truthful readiness

GATT service discovery can be answered from Android's cache in a few milliseconds, while LE
encryption for a bonded cluster is still completing. Treating that cached result as "connected"
produced false `Connected`/`Sending` states and a dropped first ATT request
(`smp_link_encrypted: SMP state machine busy`, `BTM_SetEncryption busy`).

The link is therefore verified before it is reported:

1. After service discovery, wait a short encryption settle window
   (`DEFAULT_HANDSHAKE_SETTLE_MILLIS`, 1 500 ms in production; 0 in unit tests).
2. Probe the ATT layer with a real `requestMtu(146)` request, bounded by
   `DEFAULT_HANDSHAKE_TIMEOUT_MILLIS` (6 s). No payload is written by the probe.
3. Publish `Connected` only if the probe succeeds and the negotiated MTU is at least
   `REQUIRED_MTU` (58). A smaller MTU is a terminal `MtuTooSmall` failure and is not retried.
4. Retry a failed or timed-out probe up to `DEFAULT_MAX_HANDSHAKE_ATTEMPTS` (3) with reconnect
   backoff, then report `BleFailure.NotResponding`.
5. A disconnect that happens during the probe follows the normal auto-reconnect policy rather
   than counting as a silent probe failure.

Closing a session calls `gatt.disconnect()` before `gatt.close()`, so a pending ATT
transaction is cancelled immediately instead of occupying the stack until its 30 s response
timeout.

Mirroring is only reported as active after the sender confirms its **first successful write**.
`PhoneStatusSender.hasSent` is reset for each run, so enabling mirroring, reconnecting, or
resuming after a restart never claims "Sending" before the cluster has acknowledged a frame.
CONTROL polling starts after that same first write, because early reads contend with the
readiness probe and the first status write.

The UI reflects this directly: the headline stays `Connecting…` until the probe succeeds,
`Sending` appears only after the first confirmed write, and probe exhaustion or repeated
disconnects shows `Bike not responding` with a readable explanation instead of a raw failure
`toString()`.

## Mirroring, background mode and boot resume

Mirroring sends the phone-status (battery/signal) frame to the cluster, plus the
opt-in caller-name and missed-call fields described below. It is **on by default**
on a fresh install, but remains **session-scoped**: it stops when the rider signs
out, switches VIN, disconnects, or turns mirroring off.

| Setting | Default | Meaning |
| --- | --- | --- |
| Mirror phone status to cluster | on | Sends the battery/signal frame about once per second. |
| Keep mirroring in background | on | Runs a `connectedDevice` foreground service so mirroring survives leaving the app. |
| Resume mirroring after reboot | on | Auto-starts the link after boot when mirroring + background are both on and a bike is selected. |
| Caller name | off | Adds an incoming caller's display name to the GENERAL frame. |
| Missed-call icon | off | Sends one fixed MISS_CALL frame per missed call. |

`runsInBackground = mirroring && keepInBackground`. `resumesAfterBoot = startOnBoot && runsInBackground && armedVin != null`. With the mirroring defaults on and a bike selected, `resumesAfterBoot` is true.

The two call features are **opt-in and off by default**. They are independent of the
battery/signal switch: turning mirroring off stops every frame, but enabling a call
feature never turns on anything else.

The foreground service runs only while mirroring is active and background mode is on. It stops when mirroring is turned off, when background mode is turned off, or when the notification stop action is tapped. If notification permission is denied, the service still runs but no notification is shown — an in-app warning appears in Settings.

**Background model:** the `BikeLinkController` is a singleton in the application scope (`AppContainer`), so it outlives any screen. `BikeLinkService` is logic-thin: its `ACTION_START` handler calls `controller.ensureRunning()`, which uses the controller's existing connect-with-response logic. There is no indefinite background retry and no `connectGatt(autoConnect=true)`; after a reboot the link resumes once when the boot receiver starts the service. This is a documented limitation — if the bike is out of range at boot, the link will not keep retrying indefinitely.

Boot resume is handled by `BootReceiver` on `BOOT_COMPLETED`. It starts `BikeLinkService` only when `resumesAfterBoot` is true. The receiver has no `android:permission` attribute; it filters on the boot action in `onReceive`.

## CONTROL polling (read-only diagnostics)

When connected, mirroring is on and the first status write has been confirmed, the cluster's
CONTROL characteristic (`0a10676e-6972-6565-6e69-676e4543544f`) is polled every 700 ms. Each poll is a 20-byte read; the response is 26 bytes. This is **read only** — no actions are transmitted. Values are button counters and are surfaced in the Bike link screen's CONTROL diagnostics card.

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

Only verified frames may be transmitted. Caller name and missed-call frames are
implemented and opt-in (see the next section), but still need a live capture to
confirm the cluster accepts them. Alert and turn-by-turn frames are not
implemented at all: the official Android app does not expose them as features.

## Call mirroring and privacy

The official Android app's native encoder defines two call features, and this app
implements only those two:

- **Incoming caller name.** The caller's display name is written into the GENERAL
  frame while the phone is ringing or the call is active. No number is ever sent.
- **Missed-call icon.** One fixed 74-byte MISS_CALL frame is written after a missed
  call. It contains no name, number or history.

Privacy model:

- Both switches default to off and are separate from the battery/signal switch.
- Call data comes from a `NotificationListenerService` reading only the dialer's
  call notification. The app requests neither `READ_CONTACTS` nor `READ_CALL_LOG`.
- The dialer package allowlist is the active default dialer plus stock OEM dialers;
  notifications from every other app are ignored.
- Only a display label may leave the parser. Phone-shaped values, generic labels
  (`Incoming call`, `Unknown`, `Private number`, …) and overlong labels collapse
  to the protocol fallback `Incoming call`; the wire path has no number fallback.
- `READ_PHONE_STATE` (already optional for signal strength) is also used to tell
  an incoming RINGING call from an outgoing call. Without it, caller-name mirroring
  stays unavailable; missed-call detection still works from the notification alone.
- Events that arrive while the link is down are dropped, not replayed. Nothing is
  logged or persisted.
- Turning mirroring off, signing out or disconnecting stops collection; the sender
  cancels its flow and the platform listener is unregistered.

On the wire, the caller fields are byte 20 (UTF-8 length, capped at 30 bytes on a
code-point boundary) and bytes 21-50, with zero padding. `CallState` occupies the low
three bits of byte 1 (`0` none, `1` incoming, `2` outgoing, `3` active, `4` end);
only an active call sets byte 3 to `1` and byte 4 to `0`. The missed-call frame is
byte 0 `1` followed by 73 zero bytes. MISS_CALL needs a 77-byte MTU; GENERAL needs
58. If the peer does not expose a writable MISS_CALL endpoint or the MTU is too
small, that one frame is skipped without stopping normal mirroring.

## Automated validation

```sh
./gradlew :core:ble:test
./gradlew :app:testDebugUnitTest --tests 'dev.pulsarconnect.app.ble.*'
./gradlew test :app:assembleDebug :app:lintDebug
```

Regression tests cover cancellation cleanup, stale discovery callbacks, permission
and adapter changes during reconnect backoff, changing the reconnect preference,
per-VIN isolation, Android GATT discovery/disconnect handoff, MTU negotiation,
stale MTU callbacks, disconnect-before-close ordering, readiness-probe gating
(no `Connected` while probing), encryption settle ordering, bounded probe retry
and `NotResponding`, first-write gating for mirroring and CONTROL polling,
truthful status text, oversized-write rejection, battery/signal source permission
changes, sender cadence/heartbeat wrapping, opt-in lifecycle stops, background
mirroring defaults, boot resume gating, service start/stop lifecycle, call-state
and caller-field encoding, name sanitization/truncation, missed-call priority and
capability checks, dialer allowlisting, and notification privacy filters.

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

## Call frames: implemented, hardware verification pending

The call layouts come from the native encoder's static analysis. The app now
encodes and transmits them behind the opt-in switches above, but the available
capture has no non-idle caller or missed-call frames. Hardware behaviour is
therefore still unverified:

- MISS_CALL (`0310676e-6972-6565-6e69-676e4543544f`) is a 74-byte fixed trigger.
- GENERAL caller text occupies byte 20 (UTF-8 length, capped at 30) and bytes
  21-50; the remaining bytes stay zero.
- ALERT (`0410676e-6972-6565-6e69-676e4543544f`) remains unimplemented.

Do not claim cluster support for caller names or missed calls until the capture in
the next section confirms it. The app must never substitute a phone number for a
missing name; unnamed callers get the generic `Incoming call` label.

Live speed/RPM/fuel/gear telemetry is still not established. The capture's ACTION
characteristic (`0a10676e-6972-6565-6e69-676e4543544f`) returns 26-byte read
responses, but their meaning is not decoded and the values observed were not
sufficient to justify a decoder. A naive parser that split the ACL payload
incorrectly reported 22 bytes; the reassembled response is 26.

ATT handles in the capture (`0x0025` writes, `0x002b` reads) are capture-local and
must not be hardcoded. Classic Bluetooth HFP and OBEX exchanges also appear on
another connection; their ownership has not been established, so they must not be
attributed to the bike or used as proof of its call/contacts protocol.

## Needed capture: live call verification

A new HCI snoop log should capture an incoming call while the official Android app
is connected to the bike. Do not publish caller names, phone numbers or raw
payloads. This is the remaining gate before the call features can be described as
working on hardware.

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
