# Changelog: karoo-garage

All notable changes. Semver, tagged `vMAJOR.MINOR.PATCH`; a `v*` tag builds a signed release
APK through `release.yml`. Reconstructed from tags and history on 2026-10-02.

## Unreleased

### Added
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

### Security
- Server URLs are parsed structurally and stored as a canonical origin (#9). Userinfo, query,
  fragment, any path, IPv6 zones and invalid ports are refused. HTTPS is required except for a
  local address (private or link-local IPv4/IPv6, loopback, `.local`, `.home.arpa`), and the
  app warns before a password or token goes out over local HTTP. Login, the legacy form,
  `.kgcfg` import, mDNS discovery and HA's advertised public URL all use the same validator,
  `ServerUrl`. Discovery prefers HA's HTTPS `internal_url` from its mDNS record when there is
  one. Underscore hostnames keep working, as in PR #11's importer. 22 unit tests. ADR-0004.
- **Breaking for remote HTTP setups:** a stored `http://` URL that is not local now stops the
  garage action with "server URL can only use http:// on your home network" instead of
  sending the token. That includes a bare name such as `http://homeassistant:8123`, which
  is not provably local. Switch to HTTPS, or log in again with a local IP or `.local` name.

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
