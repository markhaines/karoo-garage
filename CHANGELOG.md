# Changelog: karoo-garage

All notable changes. Semver, tagged `vMAJOR.MINOR.PATCH`; a `v*` tag builds a signed release
APK through `release.yml`. Reconstructed from tags and history on 2026-10-02.

## Unreleased

### Security
- `.kgcfg` import hardened (#6): VIEW only (SEND and BROWSABLE removed), `content://` `.kgcfg`
  files or `file://` inside the app's own folder only, 8 KB cap, strict parsing, and a
  confirmation dialog showing server, entity and action (never the token) before anything is
  saved. Cancel leaves the current setup untouched. An OAuth login replaced by an import is
  revoked on the server, best-effort. Parse errors are no longer logged with their message,
  which could quote the token. ADR-0002.

### Fixed
- One garage command at a time (#5). A press while the previous command is unresolved is
  refused, so a slow Bluetooth-tunnel `toggle` can no longer be followed by a second one that
  reverses the door. The 20 second cooldown now runs from the command's end (done, failed,
  timed out or cancelled), not the press, and an unconfigured press no longer consumes it.
  The cooldown is timed on a monotonic clock, so a time-sync step back cannot hold the
  button shut.
  Alerts and the Garage field tell sending, still sending, already sent, done and no reply
  apart. Setup suggests `open_cover` as the safest action. ADR-0003.
- Extension teardown now releases the runtime (#7): every job (trigger, poll, alert retry,
  battery report) runs in one scope with its own SupervisorJob, which teardown cancels before
  disconnecting from the Karoo system and dropping the cached components. The next instance
  builds fresh ones; the command cooldown carries over so a restart cannot double-toggle.

### Added
- 16 unit tests over command serialization and runtime teardown, on virtual time with a fake
  Home Assistant and a fake Karoo connection: a 25 second request emits one call, cooldown from
  the terminal event, unconfigured presses, distinct outcomes, teardown mid-request, and 25
  create/destroy cycles leaving one connection and no live jobs, and a clock stepping back.
- 22 unit tests over the import checks: oversized and endless streams, malformed and
  unexpected content, externally supplied intents, confirm/cancel/revoke behaviour, and LAN
  addresses (including underscore hostnames like `http://my_ha:8123`) that main accepted.
- 13 unit tests over Home Assistant's login flow as the extension parses it: success,
  missing code, the MFA step, humanised errors, an error on the MFA step winning over
  re-asking, abort, unknown types, and MFA module auto-selection (TOTP preferred, never
  re-answering a step that came back with errors, malformed schemas). A misparse here means a
  rider cannot log in.
- CI on every push to `main` and every pull request: the unit tests, Android Lint (fails on
  any error) and a debug build. Release builds stay tag-triggered.
- This changelog. Completes Dev tier 3.

### Changed
- `parseLoginStep`, `mfaModuleToSelect` and `humaniseError` moved into `AuthClient`'s
  companion object as `internal`, so the tests can reach them. They used no instance state;
  call sites are unchanged.
- `GLOSSARY.md` became `CONTEXT.md`, in the domain-modeling skill's format.
- Our own request timeout is now a typed `RequestTimeoutException` instead of matching
  "timed out" in the message.
- `AuthMode` and `GarageState` moved from `ConfigStore.kt` to `GarageState.kt` unchanged, so
  pure logic that uses them compiles without Android.

## 1.2.0 — 2026-09-02

### Added
- Battery reporting to Home Assistant (#10).

## 1.1.1 — 2026-08-09

### Fixed
- Triggers are debounced, and timeouts honour the real latency of the Bluetooth tunnel.

## 1.1.0 — 2026-08-08

### Added
- A tappable, live-state Garage data field.

## 1.0.0 — 2026-08-08

### Added
- OAuth login with Home Assistant credentials, so no long-lived token has to be typed on the
  Karoo. LAN discovery, with the advertised public URL suggested after a LAN login.
- Entities fetched per domain in slices, to stay under the Companion bridge's 100 KB cap.
- A Done button, a directive success message after Test, a last-step dialog, and a proper
  launcher icon.

## 0.1.2 — 2026-05-01

### Added
- Launcher icon.

## 0.1.1 — 2026-04-29

### Changed
- Home Assistant calls route through the karoo-ext network bridge rather than OkHttp, so they
  work over the Bluetooth tunnel mid-ride.

## 0.1.0 — 2026-04-29

### Added
- An in-ride "Open Garage" action that calls a Home Assistant service, with release signing
  and the GitHub Actions release workflow.
