# ADR-0004: Require HTTPS for the server URL, except to a local address after a warning

- **Status:** Accepted
- **Date:** 2026-11-05
- **Supersedes:** none

## Context

The server URL is where the rider's password, the OAuth refresh token, a legacy long-lived
token and every service call go. Until v1.2.0 the only check was an `http://` or `https://`
prefix, so a typo, an imported file or HA's own advertised public URL could send credentials
over plain HTTP across the internet, or to a URL whose userinfo part (`https://a.com@b.test`)
hides the real host. mDNS discovery offered `http://<ip>:8123` by default. Issue #9.

Home Assistant on the LAN commonly has no certificate at all, and the README already says
LAN-only works if you only trigger near home. Refusing HTTP outright would lock those riders
out of the login screen.

## Decision

Every server URL goes through `ServerUrl.parse`, one pure-Kotlin validator: structural parsing
with `java.net.URI`, then a canonical origin `scheme://host[:port]` (lowercased, default port
dropped, no path). Userinfo, query, fragment, any path beyond `/`, IPv6 zones and ports outside
1 to 65535 are rejected. HTTPS is accepted anywhere. Plain HTTP is accepted only to a local
address: IPv4 loopback, RFC 1918 and link-local; IPv6 loopback, `fc00::/7` and `fe80::/10`;
names ending `.local`, `.home.arpa` or `localhost`. Hostnames are judged by name only, never
resolved. Before a password or token is sent over local HTTP, the rider confirms a warning
dialog. HA's advertised external URL passes the same parse and must also be HTTPS, because it
is the address used away from home. A URL stored by an older version that fails these rules
stops the ride action with the reason instead of sending the token.

## Consequences

- Riders with a remote `http://` URL stored from an older version must switch to HTTPS (or a
  local address) before the garage button works again. This is the point, but it is a
  breaking change for them and the changelog says so.
- A single-label name like `http://homeassistant:8123` is refused over HTTP. Use the IP, the
  `.local` name or HTTPS. Tailscale CGNAT addresses (`100.64.0.0/10`) are not local either.
- Any new code path that takes a server URL (import, discovery, a future setting) must call
  `ServerUrl`, not reinvent a prefix check. The `.kgcfg` importer in PR #11 has its own
  `originOf`; it should delegate here when the two land together.

## Options rejected

- HTTPS only, no exceptions: locks out every certificate-less LAN install, which is the most
  common Home Assistant setup.
- Resolve the hostname and allow HTTP if it lands on a private address: DNS is attacker
  influenced on hostile networks and the answer can change between check and use.
- Allow HTTP anywhere with a warning: a warning tapped through once on a remote URL leaks a
  non-expiring token for good.
