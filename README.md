# Bajaj Pulsar Companion App

An independent, local-first Android companion app for Bajaj Pulsar motorcycles that fixes the official Bajaj Ride Connect app's forced re-login and lack of data retention.

## Key Features

- **Persistent Login**: Users stay logged in across app restarts and device reboots
- **Offline Support**: No network required to view basic information
- **Sync-Ready**: Data sync planned for future versions
- **Native Android**: Built with Kotlin + Jetpack Compose
- **TDD**: All tests are green and follow the TDD approach

## What It Fixes

### Official App Issues
1. **Forced Re-login**: The official app forgets user credentials on cold start, requiring re-authentication every time
2. **No Data Retention**: User data is not persisted between sessions
3. **Network Dependency**: The official app requires constant internet connection even for basic operations

### Our Solution
1. **Persistent Credentials**: VIN and access code are encrypted and stored locally using Android Keystore
2. **Silent Re-authentication**: When tokens expire or are rejected, the app silently fetches new ones
3. **Offline Mode**: Users can view their bike information and use core features without internet

## Architecture

The app follows a clean architecture with clear separation of concerns:

```
app/
├── ui/              # Jetpack Compose UI screens
│   ├── LoginScreen.kt
│   ├── HomeScreen.kt
│   └── Theme.kt
├── ui/viewmodel/    # ViewModel layer
│   └── AppViewModel.kt
├── KeystoreSecretStore.kt  # Encrypted secret storage
└── MainActivity.kt

core/
├── model/           # Data models
│   ├── Session.kt
│   ├── Credentials.kt
│   └── Vin.kt
├── session/         # Session persistence layer
│   ├── SessionRepository.kt
│   └── SessionContract.kt
├── network/         # Network layer with auth management
│   ├── BajajApi.kt
│   └── BajajSessionManager.kt
└── data/            # Database layer (planned)
└── ble/            # Bluetooth layer (planned)
```

## Build Status

- ✅ Core modules: All 16 tests passing
- ✅ Network layer: All API endpoints recovered and tested
- ✅ Authentication: Persistent login with silent re-authentication
- ✅ UI: Login and home screens functional
- ✅ Security: Encrypted storage with Keystore
- ✅ Build: APK built successfully

## Known Issues & Planned Improvements

### Currently Working
- Login with VIN and access code
- Persistent session across app restarts
- Silent token refresh on rejection
- Home screen displays bike info (model + VIN)

### Planned for Future Versions
- Trip logging and telemetry
- Bluetooth cluster data streaming
- Service reminders
- Turn-by-turn navigation integration
- Full offline functionality

## API Recovery Notes

The app was reverse-engineered from the official `com.bajajconnect.ib` v1.1.14 APK. Key discoveries:

1. **Two Token System**: 
   - `appToken` (from `/api/whoami`) used for all API calls
   - `userToken` (from `/api/ib/rc/login`) never used as a bearer token in the current app

2. **Vehicle List Endpoint**: The `/api/ib/rc/customer/vehicles` endpoint unexpectedly uses `userToken` as the bearer token, not `appToken`.

3. **No Refresh Tokens**: There's no OTP, password, or refresh token endpoint - silent re-login relies on dealer access code persistence.

## Security

- All secrets (VIN, access code, tokens) are encrypted using AES-GCM
- Encryption keys are stored in Android Keystore (never extracted)
- No network security configuration pinning (to allow certificate rotation)
- `android:allowBackup="false"` to prevent secret extraction from backups

## Testing

The app follows TDD principles:
- All API endpoints have contract tests
- Session persistence is thoroughly tested
- Token refresh logic has single-flight tests
- Edge cases covered (unregistered vehicles, invalid access codes)

## Installation

1. Build the app: `./gradlew :app:assembleDebug`
2. Install on device: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. Launch the companion app

## Contributing

This is a research project focused on reverse-engineering Bajaj's authentication system. Contributions should:

- Follow the existing TDD approach
- Never commit real VIN, access codes, or tokens
- Use synthetic VIN `MD2ABCDEFGH123456` for tests
- Focus on code quality and test coverage

## Credits

- Reverse-engineered from official Bajaj Ride Connect app
- Based on static analysis of Hermes bundle and APK resources
- Used synthetic test data to avoid exposing real credentials