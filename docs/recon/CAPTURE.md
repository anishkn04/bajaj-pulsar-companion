# Capturing real traffic (spare phone)

Only needed to confirm status codes and response schemas. Keep every artefact
out of git.

## Why it needs a spare phone

`targetSdk 35` ignores user-installed CAs unless the app opts in, and the APK
has no `network_security_config.xml`. Options, best first:

1. **Patched APK** (`apk-mitm`) on the spare phone. Re-signing changes the
   signature, so the official app must be uninstalled first and its login
   repeated. That is fine on the spare.
2. **Rooted spare** + a system CA (Magisk module).

The daily-driver phone used for the initial recon stays unmodified.

## Procedure

```bash
# 1. Patch and align a copy (never the installed daily-driver APK)
apk-mitm base.apk                # writes base-patched.apk
adb install -r base-patched.apk

# 2. Proxy
mitmproxy -p 8080 --set confdir=.secrets/mitm
adb shell settings put global http_proxy <host-lan-ip>:8080
adb shell settings put global global_http_proxy_host <host-lan-ip>
adb shell settings put global global_http_proxy_port 8080

# 3. Trust the mitmproxy CA in the device user store, then log in with your
#    own vehicle's VIN + access code.

# 4. Capture only the auth calls
mitmdump --set confdir=.secrets/mitm \
  -w captures/auth.flows \
  "~u fms.bajajauto.com & (~u whoami | ~u /api/ib/rc/)"

# 5. Clean up
adb shell settings put global http_proxy :0
adb shell settings delete global global_http_proxy_host
adb shell settings delete global global_http_proxy_port
```

## What to record

- HTTP status for a bad app token (401? 403? 200 with an error message?).
- `/api/whoami` response headers: is the token a JWT with `exp`?
- `/api/ib/rc/login` response: full `data` object.
- `/api/ib/rc/customer/vehicles`: full body.
- Any `Set-Cookie` headers.

Redact VIN, access code, tokens and cookies before pasting anything into an
issue or a doc.
