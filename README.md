# Pulsar Connect

An independent, local-first Android companion for Bajaj motorcycles that support
the Bajaj Ride Connect instrument cluster.

The official app forgets who you are: it makes you re-enter your VIN and dealer
access code, and it gates the experience behind an active Bluetooth connection to
the bike. This project exists to fix that.

## Goals

- **Log in once.** Credentials are stored encrypted on-device and never asked for
  again unless they are actually rejected.
- **Local-first.** Trips, telemetry and reminders live in a local database and are
  readable with no bike, no network and no cloud account.
- **Bring your own bike.** Nothing is hardcoded to a specific VIN, access code or
  region. Every user signs in with their own vehicle.
- **The cluster still works.** Calls, SMS, notifications and (where supported)
  turn-by-turn navigation keep flowing to the motorcycle.

## Non-goals

- Redistributing Bajaj firmware, APKs or credentials.
- Circumventing paid services or DRM.
- Operating as an official Bajaj product. This is unaffiliated, personal
  interoperability software.

## Architecture

```
core/model     pure Kotlin domain types
core/session   credentials, tokens, encrypted persistence
core/data      local database, offline-first repositories
core/ble       GATT client and protocol codec
core/network   API client, redaction, retry/backoff
app            Jetpack Compose UI
```

`core/*` modules are plain Kotlin/JVM so the interesting logic is testable in
milliseconds without an emulator. Android-specific adapters live behind ports.

## Development

Requires JDK 21. The Gradle wrapper is committed.

```bash
./gradlew test
```

Commits follow [Conventional Commits](https://www.conventionalcommits.org/).
Enable the hook once per clone:

```bash
git config core.hooksPath .githooks
```

## Credentials

Never commit a VIN, access code, token or raw capture. Use `.secrets/` (ignored)
or the environment. See `docs/SECURITY.md`.
