# Releasing EVDash to Google Play

Package: `com.innodrive.evdash` · version driven by `versionCode` / `versionName` in `app/build.gradle.kts`.

## 1. Generate the upload keystore (once)

The release build reads four keys from `local.properties` (or the same names as env
vars in CI). Generate a keystore and **back it up** — Play permanently binds the app
to this key (unless you enroll in Play App Signing, in which case this is the *upload*
key and can be reset via support).

```bash
keytool -genkeypair -v \
  -keystore ~/keystores/evdash-upload.jks \
  -alias evdash \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass 'CHOOSE_A_STRONG_PASSWORD' \
  -keypass  'CHOOSE_A_STRONG_PASSWORD' \
  -dname "CN=Innodrive, O=Innodrive, C=US"
```

Then add to `local.properties` (gitignored — never commit):

```
RELEASE_STORE_FILE=/home/rara-widya/keystores/evdash-upload.jks
RELEASE_STORE_PASSWORD=CHOOSE_A_STRONG_PASSWORD
RELEASE_KEY_ALIAS=evdash
RELEASE_KEY_PASSWORD=CHOOSE_A_STRONG_PASSWORD
```

When `RELEASE_STORE_FILE` is unset the release build falls back to the **debug** key
(fine for local smoke-testing, **not** acceptable for a Play upload).

## 2. Build the App Bundle (what Play wants)

Play requires an `.aab`, not an `.apk`:

```bash
./gradlew bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```

Verify it's signed with the release key (not debug):

```bash
./gradlew signingReport | grep -A4 "Variant: release"
```

## 3. Bump version for each upload

Every Play upload needs a strictly higher `versionCode`. In `app/build.gradle.kts`
`defaultConfig`: increment `versionCode`, set a human `versionName`.

## 4. Play Console checklist (outside the repo)

- **Data Safety form** — the app collects/uses location, Bluetooth device info, and
  phone/call state. Declare each and its purpose.
- **Privacy policy URL** — mandatory (location + phone state). Host one and link it.
- **Permissions declarations** — `READ_PHONE_STATE` + `ANSWER_PHONE_CALLS` and
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` trigger a review prompt; justify them
  (call mirroring to the cluster; foreground-service BLE reconnect survives Doze).
- **Store assets** — 512×512 icon, 1024×500 feature graphic, ≥2 phone screenshots,
  short + full description.
- **`targetSdk = 36`** already meets the current Play target-API requirement.
- Consider whether `android:allowBackup="true"` is desired (it backs up the Room
  trip DB to the user's cloud).
