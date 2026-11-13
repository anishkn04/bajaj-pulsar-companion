# Security

## What we store

| Data | Where | Protection |
| --- | --- | --- |
| VIN, access code | app-private storage | AES-GCM key in the Android Keystore |
| App token, user token | app-private storage | same key |
| Bike profiles, trips, telemetry, reminders | native Android SQLite database | app-private storage; not separately encrypted |
| Alert opt-in/pause state and delivered/completed reminder IDs | app-private preferences | no credentials, VINs, titles or locations |
| App lock preference | app-private preferences | opt-in boolean only; no credentials or vehicle data |
| Background scheduling | WorkManager's app-private database | generic work names; no credentials or vehicle data in work input |

Nothing is uploaded to a server we control. There is no analytics, no crash
reporting SDK and no account with us.

## Rules for contributors

1. **Never commit a VIN, access code, token, cookie or raw capture.** The
   `.gitignore` blocks the common capture formats, but it cannot guess.
2. Use the synthetic VIN `MD2ABCDEFGH123456` in tests and fixtures.
3. Redact before sharing a log: VIN, `Authorization`, cookies, GPS, timestamps
   that identify a ride.
4. A secret that reaches git history is compromised. Rotate it (ask the dealer
   for a new access code) rather than rewriting history alone.

## Threat model

We defend against:

- Another app on the device reading our files (app sandbox + Keystore).
- Ordinary Android app backup of credentials (`android:allowBackup="false"`).

Credentials use Keystore-backed encryption, but the local SQLite database does
not. The optional app lock asks for the device biometric whenever the app
returns from the background; it hides the signed-in UI and is **not** encryption
at rest. Someone with access to the app's private storage, a rooted device or a
compromised OS can still read the database. Screenshots are not blocked. If
biometrics become unavailable (for example, all fingerprints are removed), the
locked screen offers a deliberate "Turn off app lock" escape hatch rather than
locking the rider out of their own local records. Contributor redaction rules
are a process safeguard, not an automatic disclosure-prevention mechanism.

Signing out clears saved credentials/tokens, **not** bike profiles, trips or
reminders. Records remain per VIN for the next sign-in. Clear Android app
storage or uninstall to remove them. There is no export/restore workflow yet;
users should retain another copy of important service records.

Background service alerts are opt-in. They read the signed-in VIN's local
records and saved odometer without contacting Bajaj. Android permission and
channel settings are checked before delivery. Notification content and its
public/lock-screen version are generic: no VIN, reminder title, access code,
token or location. An immutable content intent opens the app without sensitive
extras. Notification listeners can still observe that an alert occurred.

Signing out also pauses/cancels background checks and clears displayed service
alerts while retaining the opt-in and delivery-ID history. Turning alerts off
cancels checks and displayed alerts without deleting reminders. Sign-in resumes
checks only for the current session. Delivery deduplication is local; clearing
app data resets it along with the records. These best-effort background alerts
are not exact alarms or a safety-critical service schedule.

We do **not** defend against:

- A rooted device, a compromised OS, or someone who can pass the device lock.
  The optional app lock is UI gating only, not database encryption.
- Bajaj changing their API (we will break; that is expected).
- Someone who already has your VIN + access code. Those are the credentials.
- Traffic analysis by your network operator. TLS is used; we do not add
  pinning because it would break when Bajaj rotates certificates.

## Interoperability, not impersonation

This project talks to a service the user already has an account with, using
credentials the user owns, for a vehicle the user owns. It does not bypass
authentication, share accounts, or redistribute Bajaj code.
