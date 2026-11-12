# Pulsar Connect — Task Tracker

Single source of truth for planned work. Keep it current: an agent session starts by reading
this file and ends by updating it.

Last updated: 2026-11-12

## How to use this file

- Pick the highest-priority unchecked task unless the rider asks for something else.
- Move a task to `[~]` when work starts, `[x]` when it is merged, `[!]` if it is blocked.
- A task is only `[x]` when its acceptance notes are met, tests pass, and the commit is on
  `main`. Record the commit short hash next to the task.
- Add new tasks here instead of silently expanding scope. Do not delete history: keep checked
  tasks in place with their commit reference.
- Never put a real VIN, Bluetooth MAC address, access code, token, capture path, or credential
  into this file or into a task note.

### Status legend

| Mark | Meaning |
| --- | --- |
| `[ ]` | Not started |
| `[~]` | In progress |
| `[x]` | Done and merged to `main` |
| `[!]` | Blocked; the task line must state what unblocks it |

### Priority order

`P1` data safety → `P2` link reliability → `P3` everyday QoL → `P4` bigger bets.
Within a priority, work top to bottom unless there is a reason to reorder.

## P1 — Data safety and recovery

The app has `allowBackup="false"` and no export, so losing the phone loses all trips and
reminders. A mistyped odometer is also currently unfixable. This is the highest-value work.

- [x] **PC-01 — Export local records.** (`542fa91`)
  Write a versioned JSON export through the Storage Access Framework (create document). Include
  bike profiles, trips, reminders, and telemetry behind a schema version. Exclude credentials,
  tokens, notification content, and Bluetooth addresses. Offer a "redact VIN" option.
  Acceptance: round-trips through the restore path in a test; export contains no secret fields.

- [x] **PC-02 — Restore from export.** (`542fa91`)
  Open a user-picked JSON file, validate the schema version, and confirm before replacing local
  data. Merge and replace must both be explicit. Reject unknown/newer versions with a clear
  message. Acceptance: tests cover a valid file, a wrong-version file, and malformed JSON.

- [x] **PC-03 — Edit a completed trip.** (`fe225aa`)
  Correct start/end odometer and times without creating a new row. Recompute distance, duration,
  and average speed. Acceptance: `TripRepository` exposes an update method with the same
  validation as `endTrip`; UI edits from the trip card.
  _Shipped v1: odometer correction (distance/average speed recomputed, stored times preserved)._

- [x] **PC-04 — Delete a trip with Undo.** (`fe225aa`)
  Delete only the selected trip, keep the rest of history intact, and offer an Undo snackbar.
  Acceptance: repository test proves unrelated rows survive; UI restores the row on Undo.

- [x] **PC-05 — Discard an active trip.** (`fe225aa`)
  Abandon a mistaken in-progress trip without saving a 0 km record. Require confirmation.
  Acceptance: active trip row is removed and history is unchanged.

- [x] **PC-06 — Edit and delete a reminder.** (`09b0096`)
  Change title, description, due odometer, and due date; delete a reminder. Acceptance:
  repository tests for both operations; completed reminders can be edited or deleted too.

- [x] **PC-07 — Snooze a reminder.** (`09b0096`)
  Snooze by one day or 500 km. Snooze must not mark the reminder delivered/completed.
  Acceptance: due computation reflects the new threshold; notification suppression still works.

- [x] **PC-08 — Undo "complete" on a reminder.** (`09b0096`)
  Clear `completed`/`completedAtMillis` and re-arm alerts. Acceptance: completing then undoing
  makes the reminder due again when its threshold is met.

- [x] **PC-09 — Recurring service reminders.** (`b9d0846`)
  "Every N km / N days" reminders that re-arm the next occurrence when one is completed.
  Acceptance: completing a recurring reminder creates exactly one next occurrence, not a loop.

## P2 — Link reliability visibility

A silently broken mirroring setup currently looks the same as a working one until the rider is
already riding. Make health and failures visible without opening the app.

- [x] **PC-10 — Home setup-health card.** (`2c97f63`)
  One card showing BLE permissions, notification access, notification permission, battery
  optimization, background mode, boot resume, and selected bike. Show "Ready to mirror" or a
  count of items needing attention, with the existing deep links. Acceptance: each item's
  state is derived from the real setting, not a cached guess.

- [x] **PC-11 — Richer service notification.** (`3d1d639`)
  Show bike display name and current link state, and add a "Pause mirroring" action next to
  Stop. Acceptance: action updates persisted mirroring intent; notification text never includes
  a VIN or MAC.

- [ ] **PC-12 — "Why isn't it mirroring?" diagnostics.**
  Show the last link transition, last failure reason with timestamp, background/boot gating,
  and passive-wait state. Acceptance: a single screen or card answers "what is stopping the
  link" for every terminal state.

- [ ] **PC-13 — Exportable link log for bug reports.**
  Small in-memory/ring-buffer log of state transitions and failures, exportable as text with
  VIN and MAC redacted. Acceptance: exported log passes a redaction test against synthetic
  MAC/VIN-shaped strings.

- [ ] **PC-14 — Record passive-wait start/attach.**
  Log when a passive wait is armed and when it attaches, so the new background reconnect mode
  can be confirmed on-device. Acceptance: entries appear in PC-12/PC-13 output.

## P3 — Everyday quality of life

- [ ] **PC-15 — Reminder notification actions.**
  Add "Mark done" and "Snooze" actions to reminder notifications. Acceptance: actions work when
  the app is not running and do not duplicate delivery records.

- [ ] **PC-16 — Trip detail and share.**
  Show average speed (already stored but not displayed), duration, coordinates, and a
  share-as-text / optional maps link. Acceptance: share text contains no VIN or MAC.

- [ ] **PC-17 — Home stats.**
  Total distance, trips this month, and next due reminder. Acceptance: values come from local
  records for the signed-in VIN only.

- [ ] **PC-18 — Quick odometer update.**
  Update the latest recorded odometer without starting a trip. Acceptance: updates due-status
  inputs and schedules the existing reminder check.

- [ ] **PC-19 — Trip search and filtering.**
  Filter by date range and group by month. Acceptance: filtering works with pagination and does
  not load the entire history into memory.

- [ ] **PC-20 — Theme options.**
  Light / dark / system plus dynamic color. Acceptance: preference persists across restarts.

- [ ] **PC-21 — Optional biometric app lock.**
  Gate the signed-in UI behind device biometrics when enabled. Document clearly that the local
  database is still not separately encrypted; this is UI gating, not encryption at rest.

## P4 — Bigger bets

- [ ] **PC-22 — Fuel economy from manual fill-ups.**
  Record liters and odometer at fill-up; compute consumption between fill-ups. No protocol
  decoding required. Acceptance: handles partial fill-ups and rejects non-increasing odometers.

- [ ] **PC-23 — Quick Settings tile / widget.**
  Show link state and offer connect/disconnect. Acceptance: reflects the same state as the app
  and does not start the service when mirroring is disarmed.

- [ ] **PC-24 — Planned, not scheduled: cloud sync.**
  Conflicts with the offline-first, no-analytics stance and needs a backend. Do not start
  without an explicit rider decision.

- [!] **PC-25 — Automatic trip capture.**
  Blocked on decoding the bike's telemetry/odometer frames. Unblocks when a targeted HCI snoop
  capture with a known route and odometer readings is available and decoded. Do not guess
  frames or ship unverified decoding.

## Recently completed

Keep this list short; full history is in `git log`.

- [x] Passive background waiting: Android attaches when the bike powers on; 5-minute watchdog,
  uncapped background retries, `WaitingForBike` UI. (`4d9ba31`)
- [x] Mirroring preferences survive restarts; only explicit sign-out disarms. (`657a8d0`)
- [x] Persistence regression tests. (`808b979`)
- [x] Version sourced from `gradle.properties`; CI and tagged release pipeline. (`074f6e8`, `14a166e`, `fe1e9c8`)
- [x] Call mirroring, caller-name and missed-call opt-ins, verified on a live bike.

## Standing constraints for every task

See `AGENT.md`. In short: Kotlin only; Material icons; no turn-by-turn/alerts; no
vehicle-specific constants; never commit secrets, captures, VINs, or MACs; do not push a
release tag unless asked; preserve BLE/session, mirroring, background service, boot resume,
reminders, permissions, diagnostics, the safety disclaimer, and sign-out behavior.
