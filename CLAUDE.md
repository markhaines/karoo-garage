# karoo-garage

Hammerhead Karoo 3 extension that calls Home Assistant to open the garage door
when triggered from the in-ride menu.

## Toolchain (already configured globally on this machine)

| Tool | Path | Notes |
|---|---|---|
| JDK 17 | `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` | `JAVA_HOME` exported in `~/.zshrc` |
| Android SDK | `/opt/homebrew/share/android-commandlinetools` | `ANDROID_HOME` exported in `~/.zshrc`. Includes `platforms;android-34`, `build-tools;34.0.0`, `platform-tools` |
| `adb` | `$ANDROID_HOME/platform-tools/adb` | on PATH |
| `gh` | logged in as `markhaines`, scope includes `read:packages` |
| `~/.gradle/gradle.properties` | mode 0600 | holds `gpr.user=markhaines` and `gpr.key=<gh token>` for GitHub Packages auth — used by Gradle to fetch `io.hammerhead:karoo-ext` |

## Build

```sh
./gradlew :app:assembleDebug         # produces app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug          # builds and installs to attached device
./gradlew :app:lint                  # static analysis
```

## Sideload onto the Karoo 3

The Karoo 3 runs Android. Two ways to install:

1. **ADB over USB-C** (preferred while developing): plug Karoo into the Mac, enable Developer Mode + USB Debugging on the Karoo, then `./gradlew :app:installDebug`.
2. **APK install on device**: copy `app-debug.apk` to the Karoo and tap to install (Karoo allows sideloading from its file manager once Developer Mode is on).

## Home Assistant integration

The extension calls HA's REST API:

```
POST {ha_base_url}/api/services/{domain}/{service}
Authorization: Bearer {long_lived_access_token}
Content-Type: application/json

{ "entity_id": "{entity_id}" }
```

Configuration (HA URL, token, entity ID, domain, service) is entered in a settings
screen on the Karoo and persisted in `EncryptedSharedPreferences`. The token is
never embedded in the APK or in this repo.

## Project conventions

- **Kotlin**, single-module Android Gradle project, Kotlin DSL (`build.gradle.kts`)
- minSdk 23 (per karoo-ext), compileSdk 34, targetSdk 34
- HTTP via OkHttp; JSON via kotlinx-serialization (or stdlib if trivial)
- No tracking/analytics, no third-party network calls beyond the configured HA URL
- Treat the long-lived access token as sensitive: encrypted storage only, never log it, never include it in crash reports

## Useful commands cheat sheet

```sh
adb devices                                # list attached devices (Karoo should show)
adb logcat -s GarageExtension              # tail extension logs by tag
adb install -r app/build/outputs/apk/debug/app-debug.apk
gh auth refresh -s read:packages           # if Gradle Packages auth ever fails
```
