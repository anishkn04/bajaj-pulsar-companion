# Bajaj Ride Connect — API reconnaissance

Recovered from `com.bajajconnect.ib` **1.1.14 (32)** by decompiling its Hermes
bundle (`hermes-dec`) and inspecting the APK resources. No traffic capture was
needed for this document; everything below is static analysis plus one manual
login on the reference device.

Evidence files (not committed, in `/tmp/opencode/bajaj/`):

- `bundle.js` — extracted Hermes bytecode
- `dec.js` — decompiled JS (39 MB)
- `login_full.txt`, `whoami_full.txt`, `axios_setup.txt` — extracted regions

## Base URL

`https://fms.bajajauto.com`

The JS reads it from a `react-native-config` export (`ZERSYS_URL`), which is
compiled into resources, not the bundle. Confirmed with:

```sh
aapt2 dump resources base.apk | grep -A1 string/ZERSYS_URL
#   () "https://fms.bajajauto.com"
```

There is no `network_security_config.xml`; the app does **not** pin
certificates. That is why a user-installed CA is ignored on targetSdk 35 and
why interception requires patching the APK (or root).

## Authentication model

Two tokens, with confusing names:

| Token | Origin | Used as |
| --- | --- | --- |
| `zersysWhoAmI` | `GET /api/whoami` | `Authorization: Bearer …` on **every** API call |
| `userToken` | `token` in the `/api/ib/rc/login` response | stored in Redux/local storage, **never** used as a bearer in the bundle |

The "access code" is a per-vehicle dealer code. It is long-lived and there is
no OTP, password or refresh-token endpoint. Silent re-login is therefore
possible whenever the dealer code is still valid.

### 1. App token

```http
GET /api/whoami?subscription_id=Bajaj_CDMS
Content-Type: application/json

{"subscription_id":"Bajaj_CDMS"}
```

```json
{"message":"Success","token":"<app token>"}
```

The app stores the token in `AsyncStorage`/MMKV under `zersysWhoAmI` and the
store time (a locale date string) under `oAuthStoreTime`. `oAuthStoreTime` is
never compared to anything — there is no local expiry check. If the stored
token is absent, the client fetches a new one; a forced refresh removes it and
fetches again.

Failure mode: non-`Success` → `Error("Something went wrong")`; network error →
logs `from zersysWhoAmI` and returns `null`.

### 2. Log in (vehicle ownership check)

```http
POST /api/ib/rc/login
Authorization: Bearer <app token>
Content-Type: application/json

{"vin_number":"…","access_code":"…"}
```

```json
{"message":"Success","token":"<user token>","data":{ … }}
```

Decompiled handler (paraphrased):

```js
if (message === "Success")            storeUserToken(data.token)
else if (message === "Vehicle not registered")
                                      navigate("SignupScreen", {vin})
else                                  toast(message)
// then, on success:
navigate("FetchingBikeInfoScreen", {isSignup: false, userDetails: data.data})
```

`data.data` is passed through as `userDetails`; its exact shape was not needed
for the client and is not modelled yet.

### 3. Validate on startup

```http
POST /api/ib/rc/validate
Authorization: Bearer <app token>
Content-Type: application/json

{"vin_number":"…","access_code":"…"}
```

`message === "Success"` → signed in; anything else → signed out. This is the
official app's cold-start gate. It is a **network** call, which is why the
official app cannot show your data offline.

### 4. Vehicle list

```http
GET /api/ib/rc/customer/vehicles
Authorization: Bearer <app token>
```

```json
{"message":"Success","data":{"data":[ … ]}}
```

Bike objects read by the app include `ChassisNo`, `model`, `model_image` and
`path`/`bikeImage`. The nested `data.data` is then cached under `allBikes`.

## Other endpoints seen

All under `/api/ib/rc/`, all using the same app token:

| Method | Path |
| --- | --- |
| POST | `/signup` |
| POST | `/forgot-access-code` |
| POST | `/customer/vehicle/add` |
| POST | `/customer/vehicles/nickname` |
| GET | `/vin/info?vin_number=` |
| GET | `/vin/security-questions?vin_number=` |
| GET | `/v2-security-questions?language=` |
| GET | `/v2-terms-privacy?language=` |
| GET | `/consent?vin_number=` |

## Why the official app forgets you

Cold start calls `localSignIn()`, which does:

```js
const userInfo = await storage.getMapAsync("userInfo");
if (userInfo == null) { dispatch({type:"signIn", payload:false}); return; }
```

Only if `userInfo` exists does it load `allBikes`, `userToken`,
`zersysWhoAmI` and `oAuthStoreTime`. Reproduced on a real device: log in with
the bike off → kill the process → relaunch → onboarding → welcome → login
form. The credentials are not lost because they were never persisted in a
usable form; the app re-asks instead of re-authenticating.

Our app does the opposite: persist the user-supplied VIN + access code
(encrypted), treat that as the durable session, and re-authenticate silently
when the app token is missing or rejected. The token is an implementation
detail, not the thing the user has to keep.

## Open questions

- Exact HTTP status codes for expired/invalid tokens (body `message` is the
  only signal in JS). Client treats 401 **and** `message` containing
  `Session Expired` / `Unauthorized` as a refresh trigger.
- Whether `userToken` is required for BLE pairing / websocket auth.
- Whether `/api/ib/rc/login` also returns security questions for some
  vehicles (the older `rideapp` flow did).
- Full response schema of `/api/ib/rc/customer/vehicles`.

These are answerable with the spare phone + `apk-mitm` capture; see
`docs/recon/CAPTURE.md`.
