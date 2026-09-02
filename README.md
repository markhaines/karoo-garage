# karoo-garage

<!-- Screenshot slot: docs/ device shots — the login/entity-picker screen and the Garage
     data field mid-ride. Grab over adb next time the Karoo is plugged in. -->

Open your garage door from your handlebars. karoo-garage is a
[Hammerhead Karoo 3](https://www.hammerhead.io/) extension that fires a
[Home Assistant](https://www.home-assistant.io/) service call from the in-ride menu: roll up
the driveway, hit the assigned bonus button combo or on-screen button, and the door opens :-)

It works with any HA entity that accepts a service call — covers, switches, buttons, scripts,
scenes, automations — so the garage door is just the obvious use.

[![Latest release](https://img.shields.io/github/v/release/markhaines/karoo-garage)](https://github.com/markhaines/karoo-garage/releases/latest)
[![License](https://img.shields.io/github/license/markhaines/karoo-garage)](LICENSE)

[Install](#install) · [Configure](#configure) · [In-ride button](#set-up-the-in-ride-button) · [Service recipes](#service-call-recipes)

> WARNING: This extension was 100% vibe coded. I have no idea what I'm doing. If you install it
> your bike could explode. I have it running on my Karoo3 and it works perfectly however. My
> bike is yet to explode.

## Features

- Log in, don't paste tokens: sign in on the Karoo with your normal HA username and password —
  it's HA's own OAuth flow, the same one the official phone apps use, and access renews silently
- Finds your Home Assistant for you (mDNS on the local network), then offers your public URL so
  the button also works mid-ride
- Pick your door from a list — no entity IDs to look up, no config files to write
- Works anywhere your phone has signal, tunnelling over Bluetooth through the Hammerhead
  Companion app when there's no WiFi
- A live-state **Garage** data field for your ride screen: shows OPEN/CLOSED, and tapping it
  fires the action — no hardware button needed
- Or bind it to hardware: the "Open Garage" action slots into any Karoo controller position
  (SRAM AXS long-press included)
- Debounce and timeouts tuned for real-world Bluetooth-tunnel latency, so a laggy tap can't
  double-toggle the door behind you
- Legacy long-lived-token setup still available if you prefer it
- **Battery reporting (v1.2+).** Posts the Karoo's own battery and the last
  known battery of every paired sensor (SRAM AXS components, power meters,
  lights, radar, HRM) to a Home Assistant webhook at ride end, on a 30 minute
  idle tick, and whenever the paired-device list changes. Built for a garage
  wall panel that says what needs charging.

## Install

**Requirements:** a Karoo 3 (2024-or-later firmware), a Home Assistant install reachable over
HTTPS (Nabu Casa or your own reverse proxy; LAN-only works if you only ever trigger it near
home), and your HA username + password.

No computer needed — install straight from your phone (Karoo firmware 1.527+):

1. On your phone, open the [latest release](https://github.com/markhaines/karoo-garage/releases/latest)
   and long-press the `.apk` file.
2. Share the link with the **Hammerhead Companion** app — it pushes the extension to your Karoo.
3. Reboot the Karoo once, then launch **Garage** from the app drawer and follow
   [Configure](#configure) below.

<details>
<summary>Other ways to install (adb / file manager)</summary>

1. On the Karoo, go to **Settings → About → Software** and tap the version
   number 7+ times to reveal **Developer options**.
2. In Developer options, enable **Allow installation from unknown sources** and
   **Android debug bridge** (USB debugging).
3. Plug the Karoo into your computer with USB-C.
4. Either:
   - **adb route**: `adb install -r karoo-garage-1.2.0.apk`, or
   - **drag-and-drop route**: copy the APK to the Karoo's storage in any
     folder, open the file from the Karoo's file manager, tap **Install**.
</details>

## Configure

### The normal way — log in (v1.0+)

1. Launch the **Garage** app from the Karoo's app drawer.
2. Enter your Home Assistant URL. If the Karoo is on the same WiFi as HA, the
   app usually discovers it via mDNS — a green "Found … tap to use" box
   appears; tap it instead of typing.
   After a login through a local address, the app checks whether your HA
   advertises a public URL (HA → Settings → System → Network) and offers to
   switch to it — recommended, because the local address only works on home
   WiFi while the public one also works mid-ride via the Companion app.
3. Tap **Log in** and enter your normal HA username and password (plus your
   MFA code if you use one). This is HA's own login flow: the app never sees
   more privilege than your account has, and the session shows up in your HA
   profile's refresh-token list where you can revoke it any time.
4. Pick the entity to control from the list (covers first), choose the
   service (`toggle` is the default for covers), then **Test connection** —
   it fires the real service call, so expect your door to move. A dialog
   then points you at the final step (binding the ride button, below), and
   **Done** exits the app.

The app stores a refresh token in encrypted storage and silently renews its
30-minute access tokens, including mid-ride over the Companion-app bridge.

Notes:

- If you don't ride for 90+ days, HA expires the idle session and the next
  tap shows "Login expired — open the Garage app". Log in again; 30 seconds.
- Reinstalled HA? Same thing: log in again. No cables, no token minting.
- Prefer least privilege? Create a dedicated non-admin HA user (e.g.
  `karoo`) and log the Karoo in as that.

<details>
<summary>The old ways — long-lived token instead of logging in</summary>

Two file-based routes remain for people who prefer a dedicated token (or
run an HA old enough not to have the login-flow API). Both feed the form
behind **"Use a long-lived token instead"** on the app's first screen —
nobody should be typing a 180-character token on a touchscreen by hand.

You'll need a **long-lived access token** (Home Assistant → your user →
**Security** → **Long-lived access tokens**) and the **entity ID** of what you
want to control (Developer Tools → States, e.g. `cover.garage_door`).

**Option A — drop a config file**

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
permission. If you have adb, use `push-config.sh` instead (Option B) — it
targets a path the app can always read.

**Option B — `tools/push-config.sh` (the adb route)**

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
</details>

## The in-ride data field — state display + tap to trigger

v1.1 adds a graphical **Garage** data field, which does two jobs at once:

- **Shows the entity's live state** ("OPEN", "CLOSED", "OPENING", …),
  polled every 20 seconds while the field is on screen — visual confirmation
  the door actually moved, or an early warning that it never closed.
- **Fires the configured action when tapped.** This is the trigger for
  riders without a SRAM AXS controller: no hardware button needed, the
  field itself is the button.

To add it: **Profiles → edit a ride page → add a data field → Extensions →
Garage**. It sizes from a single cell up to a full-width tile. After a tap
the field fast-polls for half a minute so you see the opening → open
transition happen.

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

## Battery reporting (v1.2+)

The Karoo already knows the battery state of everything it is paired with:
the ANT+ profiles for SRAM AXS, power meters, lights, radar and heart-rate
straps all carry a battery status, and karoo-ext exposes it as `SavedDevices`.
v1.2 turns the Karoo into a data source for the house. It POSTs a JSON report
to a Home Assistant **webhook**, and an automation on the HA side fans it out
into whatever helpers or sensors you like.

**Setup**

1. In Home Assistant, create an automation with a webhook trigger. Pick a long
   random id (32+ characters): the id is the only secret on this path, there is
   no auth header. Set `local_only: false` if reports should also arrive over the
   Companion app tunnel from outside your LAN.

   ```yaml
   triggers:
     - trigger: webhook
       webhook_id: karoo_batteries_<random>
       allowed_methods: [POST]
       local_only: false
   actions:
     - action: input_number.set_value
       target: {entity_id: input_number.karoo_battery}
       data: {value: "{{ trigger.json.karoo.battery_pct }}"}
   ```

2. On the Karoo: **Settings → Garage → Battery reporting**. Paste the webhook
   id, switch reporting on, tap **Dump devices**. The dump is the same payload
   with `"kind": "dump"` plus the raw `SavedDevices` tree, so render it as a
   persistent notification once and read off the ids and serials you want to
   map. **Send now** posts a normal report.

**When it sends**

- **Ride end** (any state to `Idle`).
- **Paired devices changed**, which includes the first `SavedDevices` emission
  after boot, so powering the Karoo on at home is a free report.
- **Idle tick** every 30 minutes while not riding, skipped below 15% unless
  charging. It is a coroutine delay, not an alarm, so it never wakes the device.
- Manual, from the Settings buttons.

Sends are coalesced (60 second floor for automatic reasons) and never queued:
a failed POST logs one warning and is dropped, the next trigger tries again.

**Payload**

```json
{
  "kind": "report", "reason": "ride_end", "ext_version": "1.2.0", "sent_at": 1788342795982,
  "karoo": { "serial": "…", "battery_pct": 80, "charging": true, "stream_pct": 80.0, "stream_state": "Streaming" },
  "ride_state": "Idle",
  "devices": [
    { "id": "16645-34-5", "name": "RD_01 System", "connection": "ANT_PLUS", "enabled": true,
      "manufacturer": "SRAM", "serial": null, "battery": "NEW", "battery_at": 1788342334318,
      "supported": ["TYPE_SHIFTING_BATTERY_ID", "…"],
      "components": {
        "LEFT_SHIFTER":  { "battery": "NEW", "battery_at": 1788342334319, "manufacturer": "SRAM", "serial": "1217531072" },
        "REAR_DERAILLEUR": { "battery": "NEW", "battery_at": 1788342334318, "manufacturer": "SRAM", "serial": "1216531645" }
      } }
  ]
}
```

`battery` is the Karoo's own coarse bucket: `NEW`, `GOOD`, `OK`, `LOW`,
`CRITICAL` or `INVALID`. Treat it as coarse: an AXS derailleur pack the BLE
advert put at 48% still reported `NEW`. `battery_at` is when the Karoo last
heard that device's battery page, not when the report was sent, so key any
"freshest wins" logic on it. Device `id` is the stable ANT id and is the safer
key for devices (a radar with a light channel shows up twice with one serial);
components carry their SRAM serial.

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

Latency over the BLE-tunnelled path is ~1–2 seconds per request in good
conditions, and has been observed exceeding 20 seconds in the wild (vs ~50ms
direct over WiFi). The app allows 30 seconds before declaring a timeout, and
a timeout doesn't necessarily mean failure — the command can still be in
transit and land afterwards. That's also why the app ignores re-presses for
20 seconds: with `toggle`, a queued duplicate arriving late reverses the
door the first press just opened. If you only ever trigger this arriving
home, consider setting the service to `open_cover` instead of `toggle` —
it's idempotent, so a duplicate can never close the door on you.

If you see "no route to host" when testing remotely, the Karoo has no
internet path at all — check that the Companion app is open on your phone
and connected to the Karoo, and that your phone has working internet.

## Status

v1.2.0 verified end to end (login, entity picker, public-URL switch, in-ride
action, battery reporting: dump, manual send, boot-time report) on a Karoo 3 running firmware **1.628.2410** (April 2026 release),
against Home Assistant 2026.8. Built against karoo-ext 1.1.9; should work on
any Karoo 3 firmware that supports karoo-ext 1.1.x. Issues and PRs welcome.

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
git tag v1.x.y
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
    ├── drawable/bg_discovered.xml      # green "found HA" box
    ├── drawable/ic_launcher_foreground.xml   # icon: garage + bike
    ├── drawable/ic_launcher_monochrome.xml   # themed-icon silhouette
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
