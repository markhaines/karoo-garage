# karoo-garage

A [Hammerhead Karoo 3](https://www.hammerhead.io/) extension that opens (or
toggles, or whatever you wire it to) a [Home Assistant](https://www.home-assistant.io/)
entity from the in-ride menu. Tap the **Open Garage** action on your Karoo and
it fires a single REST call at your Home Assistant.

The original use case: roll up the driveway, tap the assigned button combo, garage door opens, no
hands off the bars.

Works with any Home Assistant entity that accepts a service call — covers,
switches, buttons, scripts, scenes, automations.

**v0.2.0: log in, don't paste tokens.** Setup is now: enter (or tap the
auto-discovered) HA URL, type your normal HA username and password on the
Karoo, then pick your garage door from a list. The app uses Home Assistant's
own OAuth flow (the same one the official phone apps use) and silently renews
its access. No long-lived token, no 180-character strings, no cable. The old
token-based setup still works under "Use a long-lived token instead".

WARNING: This extension was 100% vibe coded. I have no idea what I'm doing. If you install it your bike could explode.  I have it running on my Karoo3 and it works perfectly however. My bike is yet to explode.

## How it works

```
Karoo in-ride menu
  └─ Open Garage  (BonusAction)
      └─ [OAuth mode] refresh the 30-min access token if stale
      └─ POST {your HA URL}/api/services/{domain}/{service}
          Authorization: Bearer {access token, or legacy long-lived token}
          { "entity_id": "{your entity}" }
      └─ in-ride alert: "Garage" / "Sending command…"
```

If the call fails (network error, bad token, wrong entity), you get a red
in-ride alert with the error message instead.

The HTTP request goes through the karoo-ext SDK's network bridge
(`OnHttpResponse` / `MakeHttpRequest`), which means the Karoo system picks
the best path automatically: direct over WiFi when available, or tunnelled
over Bluetooth via the Hammerhead Companion app when it isn't.

## Connectivity

The Karoo 3 has WiFi and Bluetooth, but no cellular modem. To reach Home
Assistant from your bike you need one of:

- **Karoo on WiFi** — works only when you're physically in range of a saved
  network, typically the last few tens of metres of the ride home.
- **Hammerhead Companion app paired and running** on a phone with internet.
  The Companion app provides a Bluetooth bridge that the Karoo (and karoo-ext
  extensions like this one) can route HTTP requests through, end-to-end. This
  is the path that works mid-ride, anywhere your phone has signal.

Latency over the BLE-tunnelled path is ~1–2 seconds per request (vs ~50ms
direct over WiFi). For a one-shot garage-open call that's fine; the in-ride
alert just appears with a small delay.

If you see "no route to host" when testing remotely, the Karoo has no
internet path at all — check that the Companion app is open on your phone
and connected to the Karoo, and that your phone has working internet.

## Status

Verified on a Karoo 3 running firmware **1.628.2410** (April 2026 release).
Should work on any Karoo 3 firmware that supports karoo-ext 1.1.7+. Issues
and PRs welcome.

## Requirements

- **Karoo 3** with Karoo OS supporting karoo-ext extensions (firmware shipped
  in 2024 or later).
- A **Home Assistant** install reachable from your Karoo's network. Public
  HTTPS works (Nabu Casa, Caddy, your own reverse proxy); LAN-only works too
  if your Karoo always rides home before you trigger the action.
- A **long-lived access token** in Home Assistant. Create one at:

  > Home Assistant → click your user (bottom-left) → **Security** tab → scroll
  > to **Long-lived access tokens** → **Create token**.

  Copy it once — Home Assistant only shows it the first time.
- The **entity ID** of whatever you want to control. Find it under
  Home Assistant → Developer Tools → States. For a typical Home Assistant
  garage door cover, this looks like `cover.garage_door`.

## Install

Pick a signed APK from the [Releases](https://github.com/markhaines/karoo-garage/releases)
page, or build from source (see below).

To put it on your Karoo:

1. On the Karoo, go to **Settings → About → Software** and tap the version
   number 7+ times to reveal **Developer options**.
2. In Developer options, enable **Allow installation from unknown sources** and
   **Android debug bridge** (USB debugging).
3. Plug the Karoo into your computer with USB-C.
4. Either:
   - **adb route**: `adb install -r app-debug.apk`, or
   - **drag-and-drop route**: copy the APK to the Karoo's storage in any
     folder, open the file from the Karoo's file manager, tap **Install**.

## Configure

### The normal way — log in (v0.2.0+)

1. Launch the **Garage** app from the Karoo's app drawer.
2. Enter your Home Assistant URL. If the Karoo is on the same WiFi as HA, the
   app usually discovers it via mDNS — a "Found Home Assistant at …" line
   appears; tap it instead of typing.
3. Tap **Log in** and enter your normal HA username and password (plus your
   MFA code if you use one). This is HA's own login flow: the app never sees
   more privilege than your account has, and the session shows up in your HA
   profile's refresh-token list where you can revoke it any time.
4. Pick the entity to control from the list (covers first), choose the
   service (`toggle` is the default for covers), then **Test connection**.

The app stores a refresh token in encrypted storage and silently renews its
30-minute access tokens, including mid-ride over the Companion-app bridge.

Notes:

- If you don't ride for 90+ days, HA expires the idle session and the next
  tap shows "Login expired — open the Garage app". Log in again; 30 seconds.
- Reinstalled HA? Same thing: log in again. No cables, no token minting.
- Prefer least privilege? Create a dedicated non-admin HA user (e.g.
  `karoo`) and log the Karoo in as that.

### The old ways — long-lived token

Tap **"Use a long-lived token instead"** on the app's first screen for the
classic five-field setup, or use one of the file-based routes below. These
remain for people who prefer a dedicated token (or run an HA old enough not
to have the login-flow API).

### Option A — drop a config file

1. Copy [`garage.kgcfg.example`](./garage.kgcfg.example) and fill in your real
   values. Save it as `garage.kgcfg`:

   ```json
   {
     "baseUrl": "https://home.example.com",
     "token": "your_long_lived_access_token",
     "entityId": "cover.garage_door",
     "domain": "cover",
     "service": "toggle"
   }
   ```

2. With the Karoo plugged into your computer (USB-C, MTP file transfer mode),
   copy `garage.kgcfg` into the Karoo's `Download` folder.
3. On the Karoo, open the file manager, find `garage.kgcfg`, and tap it.
4. Android offers **Garage** as the handler — pick it.
5. You'll see "Configuration imported." The file is read once into encrypted
   storage; the file itself can be deleted.

This works because tapping the file in the Karoo's file manager grants the
app a `content://` URI for it. Don't try to shortcut it over adb: a file
pushed to `Download` and opened with a raw `file://` path fails with a
permission error under scoped storage, because the app has no storage
permission. If you have adb, use `push-config.sh` instead (Option C) — it
targets a path the app can always read.

### Option B — type it on the Karoo

1. Launch the **Garage** app, tap **"Use a long-lived token instead"**.
2. Fill in each field. Long-press a field to paste from the Karoo's clipboard
   if you've previously copied a value there.
3. Tap **Save**, then **Test connection** to fire a real service call against
   the configured entity. A green "Test succeeded." means you're done.

### Option C — `tools/push-config.sh` (the adb route)

For people building from source who already have `adb`:

```sh
./tools/push-config.sh path/to/garage.kgcfg            # release build
./tools/push-config.sh path/to/garage.kgcfg --debug    # debug build
```

This pushes the file to the app's own external-files directory
(`/sdcard/Android/data/<package>/files/`), which the app can read without
any storage permission, triggers the import activity, and then deletes the
file from the device (it contains your token in plaintext). Pass `--debug`
(or a full package name) as the second argument if you installed the debug
build instead of the release one.

## Set up the in-ride button

The extension exposes a **BonusAction** named "Open Garage". Karoo OS lets you
bind any BonusAction to one of the in-ride controller positions or to an
in-ride menu slot:

1. On the Karoo, go to **Settings → Controllers** (or your ride profile's
   controller config).
2. Find the slot you want to use (a hardware button, a spare control panel
   slot, etc.).
3. Pick **Open Garage** from the available actions.

Now during a ride, hitting that control fires the configured service call.

## Service call recipes

`domain` and `service` map directly to Home Assistant. Some common patterns:

| What you have | `domain` | `service` |
|---|---|---|
| A `cover` entity (most garage doors) | `cover` | `toggle` (or `open_cover`) |
| A `switch` entity | `switch` | `turn_on` (or `toggle`) |
| A `button` entity | `button` | `press` |
| A `script` | `script` | `turn_on` |
| A `scene` | `scene` | `turn_on` |
| An `automation` triggered manually | `automation` | `trigger` |

Default is `cover.toggle` because, for a garage door, you usually want a
single button to open or close depending on state.

## Building from source

You'll need:

- **JDK 17** (Temurin, OpenJDK, or Zulu — anything 17.x).
- **Android SDK** with **platforms;android-34** and
  **build-tools;34.0.0** installed.
- **The karoo-ext dependency**, via either route:
  - **GitHub Packages** (needs a token with `read:packages` — GitHub requires
    auth even for public reads): run
    `gh auth refresh -h github.com -s read:packages`, then add
    `gpr.user=<your-github-username>` and `gpr.key=<your-gh-token>` to
    `~/.gradle/gradle.properties`, or set `GITHUB_ACTOR` and `GITHUB_TOKEN`
    environment variables.
  - **No token at all**: clone
    [hammerheadnav/karoo-ext](https://github.com/hammerheadnav/karoo-ext) at
    the tag pinned in `gradle/libs.versions.toml` and run
    `./gradlew :lib:publishToMavenLocal` there — this build checks
    `mavenLocal()` for `io.hammerhead` first.

Then:

```sh
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

To install over USB while you're iterating:

```sh
./gradlew :app:installDebug
adb logcat -s GarageExtension     # tail extension logs
```

## Releases

Tagged commits matching `v*` (e.g. `v0.1.0`) trigger
[`.github/workflows/release.yml`](./.github/workflows/release.yml), which
builds a signed release APK with R8 minification and attaches it to a GitHub
Release.

To cut a release as a maintainer:

```sh
git tag v0.x.y
git push --tags
```

The workflow uses these repo secrets:

| Secret | Contents |
|---|---|
| `KEYSTORE_BASE64` | base64-encoded contents of the release keystore (`base64 -i release.keystore`) |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias (e.g. `karoo-garage`) |
| `KEY_PASSWORD` | key password (same as `KEYSTORE_PASSWORD` for PKCS12 keystores) |
| `GPR_USER` | GitHub username (used to fetch `io.hammerhead:karoo-ext` from GitHub Packages) |
| `GPR_KEY` | personal access token with `read:packages` |

## Project layout

```
app/src/main/
├── AndroidManifest.xml                 # extension service + activities
├── kotlin/com/hainesy/karoogarage/
│   ├── GarageExtension.kt              # KarooExtension subclass + BonusAction
│   ├── KarooHttp.kt                    # HTTP via the karoo-ext bridge
│   ├── AuthClient.kt                   # HA login_flow + token endpoints
│   ├── HomeAssistantClient.kt          # service call + silent token refresh
│   ├── EntityRepository.kt             # compact entity list via /api/template
│   ├── HaDiscovery.kt                  # mDNS discovery of HA on the LAN
│   ├── ConfigStore.kt                  # EncryptedSharedPreferences wrapper
│   ├── Config.kt                       # legacy .kgcfg import shape
│   ├── SettingsActivity.kt             # login / entity picker / legacy UI
│   ├── EntityPickerActivity.kt         # tap-to-choose entity list
│   └── ImportConfigActivity.kt         # handles .kgcfg file open intent
└── res/
    ├── xml/extension_info.xml          # declares the BonusAction
    ├── layout/activity_settings.xml
    ├── layout/activity_entity_picker.xml
    ├── values/{strings,colors,themes}.xml
    ├── drawable/ic_garage.xml          # in-ride alert icon
    ├── drawable/ic_launcher_foreground.xml
    └── mipmap-*/ic_launcher.{png,xml}  # adaptive launcher icon
```

## Security notes

- Credentials (the OAuth refresh token, or a legacy long-lived token) are
  stored in Android `EncryptedSharedPreferences` (AES-256 GCM, master key in
  the Android KeyStore). Nothing is in plain SharedPreferences or logs, and
  your password itself is never stored — it's exchanged for tokens during
  login and discarded.
- No credentials are embedded in the APK.
- The HTTP client trusts the system trust store. Self-signed Home Assistant
  certs won't validate. Use Let's Encrypt via your reverse proxy, or front
  Home Assistant with [Caddy](https://caddyserver.com/) /
  [Nginx Proxy Manager](https://nginxproxymanager.com/) /
  [Traefik](https://traefik.io/).
- Either credential acts with the logged-in user's full permissions. Anyone
  with your unlocked Karoo and the Garage app can fire the configured service
  call — make sure that's a tradeoff you're happy with. For least privilege,
  create a dedicated non-admin HA user for the Karoo; OAuth sessions also
  appear in that user's profile as revocable refresh tokens, so "log the
  bike out" is one click in HA.

## Limitations

- **One action per install.** The BonusAction is hard-coded to "Open Garage."
  If you want a second action (open a gate, turn on the garage light), fork
  it or watch [#multiple-actions](https://github.com/markhaines/karoo-garage/issues).
- **No phone-side companion app.** Configuration happens on the Karoo or via
  USB-C — there's no iOS/Android app to push config over Bluetooth.
- **Karoo 2 unverified.** karoo-ext supports both, but I've only run this on
  Karoo 3 (see Status above). Reports from Karoo 2 owners welcome.

## Acknowledgements

- [karoo-ext](https://github.com/hammerheadnav/karoo-ext) by SRAM/Hammerhead — the SDK that makes this possible (and provides the BLE-tunnelled HTTP transport).
- [Home Assistant](https://www.home-assistant.io/).
- Garage door icon adapted from [Material Symbols](https://fonts.google.com/icons) (Apache 2.0).

## License

Apache License 2.0. See [LICENSE](./LICENSE).
