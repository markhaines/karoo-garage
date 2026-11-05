# ADR-0003: One garage command at a time; cooldown from its end, surviving runtime teardown

- **Status:** Accepted
- **Date:** 2026-11-04
- **Supersedes:** none

## Context

Over the Companion app's Bluetooth tunnel a service call can take 20 seconds or more, and the
request timeout is 30. The trigger used a 20-second timestamp debounce taken at the press, so
it could expire while the first request was still in flight and let a second `toggle` through,
which reverses the door the first one opened (#5). The timestamp was also taken before the
config check, so a press while unconfigured blocked the next real one.

Separately, extension teardown cancelled only the poller (#7): trigger and alert-retry jobs
outlived it, the Karoo connection stayed bound, and a later instance reused the old components.

## Decision

A `CommandGate` admits one command at a time. A press while one is unresolved is refused
outright; after it resolves (done, failed, our timeout, or cancelled by teardown) a 20-second
cooldown runs from that moment. Configuration is checked before the gate, so an unconfigured
press never consumes it. Each outcome is a distinct event, so the rider sees "Sending",
"Still sending", "Already sent", done, failed or "no reply" rather than one generic message.

All runtime work runs in one scope with its own `SupervisorJob`, owned by `RuntimeLifecycle`.
Teardown cancels that scope, disconnects the Karoo service and drops the components; the next
use builds fresh ones. The gate is deliberately *not* part of what teardown drops: a request
cancelled locally may already be on its way to Home Assistant, so a recreated runtime must
still honour the cooldown.

## Consequences

A rider who genuinely wants to reverse the door straight away has to wait out the in-flight
request plus 20 seconds. That is the point. Setup now suggests `open_cover`, which makes a late
duplicate harmless; the default stays `toggle` so existing setups do not change behaviour.

A second action per install (README, Limitations) would need its own gate per physical device,
not one global gate. Any new background work must be launched in the runtime scope, or teardown
will not reach it.

## Options rejected

- **Lengthen the timestamp debounce past the timeout.** Still measured from the press, still
  consumed by unconfigured presses, and still blind to a request that outlives it.
- **Reset the gate on teardown.** Reopens the double-toggle across an extension restart.
