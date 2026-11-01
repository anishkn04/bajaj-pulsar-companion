# Security

## What we store

| Data | Where | Protection |
| --- | --- | --- |
| VIN, access code | app-private storage | AES-GCM key in the Android Keystore |
| App token, user token | app-private storage | same key |
| Trips, telemetry | local database | app-private storage |

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
- A lost device being unlocked to read trips (Keystore-bound key, no backup of
  secrets; `android:allowBackup="false"` for secret storage).
- Accidental disclosure via logs, screenshots or commits (redaction helpers,
  debug-only logging).

We do **not** defend against:

- A rooted device or a compromised OS.
- Bajaj changing their API (we will break; that is expected).
- Someone who already has your VIN + access code. Those are the credentials.
- Traffic analysis by your network operator. TLS is used; we do not add
  pinning because it would break when Bajaj rotates certificates.

## Interoperability, not impersonation

This project talks to a service the user already has an account with, using
credentials the user owns, for a vehicle the user owns. It does not bypass
authentication, share accounts, or redistribute Bajaj code.
