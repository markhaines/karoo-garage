# ADR-0001: Sign in with Home Assistant's own OAuth flow, never a pasted token

- **Status:** Accepted
- **Date:** 2026-09-18
- **Supersedes:** none

## Context

The extension needs authenticated access to Home Assistant from the Karoo. The shortest path
is a long-lived access token: the user creates one in HA, pastes it into a config field, done.

The Karoo is a bike computer. Pasting a token onto it means typing a long opaque string on a
handlebar-sized touchscreen, and the result is a non-expiring credential with full API access
sitting on a device that lives outdoors, gets lent out, and is easy to lose. Rotating it means
doing the typing again.

## Decision

Sign in with a normal Home Assistant username and password, through HA's own OAuth flow, the
same one the official phone apps use. Access renews silently. No token is ever typed or
stored by hand.

Discovery follows the same principle: find Home Assistant by mDNS on the local network, then
offer the user's public URL so the button also works mid-ride, tunnelling over Bluetooth
through the Hammerhead Companion app when there is no WiFi. The user picks their entity from
a list rather than typing an entity ID.

## Consequences

More moving parts than a pasted token: an OAuth client, refresh handling, and a login UI that
has to work on a small touchscreen. In exchange the credential expires, renews without the
user, and can be revoked from HA without touching the bike.

It also sets the bar for the rest of the configuration surface: if the user should not be
typing a token, they should not be typing an entity ID either. That is why entities are
picked from a list and the instance is discovered rather than entered.

## Options rejected

- **Long-lived access token.** Non-expiring full-API credential, typed by hand, on a device
  that gets lost. Rotation is another round of typing.
- **Webhook with a shared secret.** Avoids the login, but needs HA-side setup per user and
  gives no entity list to pick from.
