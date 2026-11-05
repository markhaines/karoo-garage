# ADR-0002: Treat a .kgcfg import as untrusted input, confirmed before it replaces anything

- **Status:** Accepted
- **Date:** 2026-11-04
- **Supersedes:** none

## Context

The legacy setup path imports a `.kgcfg` file. For a file manager to open it with the Garage
app, `ImportConfigActivity` has to be exported, so any app on the Karoo can launch it with any
intent and any URI it likes. Until #6 it accepted VIEW and SEND (and BROWSABLE links), read the
whole stream, and immediately replaced the server, the credential and the physical action the
garage button fires, dropping an OAuth login without revoking it. A file the rider did not make
could silently point the button at someone else's server, or at a different entity.

## Decision

A config import is untrusted input. The activity stays exported, but only for VIEW; the code
re-checks the action, accepts only `content://` URIs whose display name ends `.kgcfg` and
`file://` URIs inside the app's own external-files directory (the `push-config.sh` route),
reads at most 8 KB, and parses strictly (UTF-8, no unknown keys, http(s) URL without
userinfo/query/fragment, Home Assistant slug shapes for entity/domain/service, a header-safe
token). The parsed config is held in memory and shown to the rider as server origin, entity and
action, never the token. Nothing is written until they tap Import; any other exit leaves the
stored config as it was. When an import replaces an OAuth login, that refresh token is revoked
on the old server, best-effort, after the local write. The checks live in `ConfigImport`,
free of Android types, so they are unit-tested.

## Consequences

One extra tap on every import, and `push-config.sh` no longer finishes the job alone: someone
has to confirm on the Karoo. Sharing a config into the app (SEND) is gone; it never worked
anyway, since the code only read `intent.data`. Any future import route (QR code, Companion
push) should go through the same `ConfigImport` checks and the same confirmation.

Server URL policy for the rest of the app (HTTPS-only, canonical origins) is #9 and is
deliberately not decided here; the import only refuses URLs that are unambiguously wrong.

## Options rejected

- **Unexported activity.** Safe, but a file manager could no longer open a `.kgcfg`, which is
  the documented route for riders without adb.
- **Signature permission on the activity.** Same effect: file managers are not signed by us.
- **Drop `file://`.** Breaks `push-config.sh`; the scoped-to-own-folder check keeps it safe.
