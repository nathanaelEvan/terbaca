# Signed release builds

How to build a signed Terbaca release (PRD M5-06). The repository never contains a signing key or a
password. They live only on the machine that builds the release.

## 1. Create the upload key (once)

```sh
keytool -genkeypair -v \
  -keystore ~/keys/terbaca-upload.jks \
  -alias terbaca \
  -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` ships with the JDK. It asks for a keystore password, a key password and your name. Keep the
`.jks` file **outside** the repository. `.gitignore` also ignores `*.jks`, `*.keystore` and
`keystore.properties` in case one lands there by accident.

**Back it up.** Store the `.jks` file and both passwords in a password manager or another safe
place. With Google Play App Signing (recommended, see §4), a lost upload key can be reset through
Play support. For a sideloaded app, a lost key means users must uninstall before they can install an
update.

## 2. Tell Gradle where the key is

`app/build.gradle.kts` reads four values, each from a Gradle property or an environment variable:

| Gradle property (`~/.gradle/gradle.properties`) | Environment variable |
|---|---|
| `terbaca.storeFile` (absolute path to the `.jks`) | `TERBACA_STOREFILE` |
| `terbaca.storePassword` | `TERBACA_STOREPASSWORD` |
| `terbaca.keyAlias` | `TERBACA_KEYALIAS` |
| `terbaca.keyPassword` | `TERBACA_KEYPASSWORD` |

Put them in your **user** Gradle properties (`~/.gradle/gradle.properties`, on Windows
`%USERPROFILE%\.gradle\gradle.properties`), never in the repository's `gradle.properties`:

```properties
terbaca.storeFile=/home/you/keys/terbaca-upload.jks
terbaca.storePassword=...
terbaca.keyAlias=terbaca
terbaca.keyPassword=...
```

If any of the four is missing, the release build still works but is **unsigned**
(`app-release-unsigned.apk`). That's what CI produces.

## 3. Build

```sh
./gradlew :app:bundleRelease     # app/build/outputs/bundle/release/app-release.aab (for Google Play)
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-release.apk  (for sideloading)
```

Before each release, raise `versionCode` (and `versionName`) in `app/build.gradle.kts`. Android
refuses to install an update with the same or a lower `versionCode`.

Check the signature on an APK:

```sh
$ANDROID_HOME/build-tools/<version>/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

It should print your certificate (`CN=...`) and no errors.

Install on the tablet: `adb install -r app/build/outputs/apk/release/app-release.apk`. A device that
has the debug build installed must uninstall it first, because the signatures differ.

## 4. Google Play (later)

`docs/decisions.md` plans a Play release. Use **Play App Signing**: upload the `.aab` signed with the
upload key above, and Google re-signs it with an app signing key it keeps. Play then requires the
target SDK of its current policy; this build targets SDK 36.

## Checked on 2026-10-11

- With a throwaway test keystore set through the environment variables, `:app:assembleRelease`
  produced `app-release.apk` (9.6 MB, arm64) and `apksigner verify --print-certs` showed the test
  certificate.
- Without the variables, it produced `app-release-unsigned.apk` (`apksigner`: "DOES NOT VERIFY").
- `:app:bundleRelease` produced `app-release.aab`.
