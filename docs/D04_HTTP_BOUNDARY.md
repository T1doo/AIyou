# Next independent HTTP/Mock-server slice: proposed execution boundary

The partition correction must pass and freeze before transport implementation.
This document prepares a concrete scope; it does not claim an HTTP client or
Mock-server contract has already been delivered.

## Product boundary

- Add a pure JVM transport interface around PreparedRequest/ProtocolStream and
  an HTTP implementation. Keep the existing model/domain separation and do not
  wire it into App, Room, Keystore, provider settings or background jobs.
- Production endpoint policy remains HTTPS with platform TLS validation, no
  redirects, no imported CA, no TLS bypass and no credential configuration.
  No real provider connection or capability probe is part of tests or delivery.
- For this credential-free slice, transport must not synthesize Authorization,
  x-api-key or arbitrary sensitive headers. It sends only existing protocol
  headers and synthetic bodies. Real authentication requires a later separately
  specified consent/secret-injection boundary.
- Define finite connect/total/idle response deadlines, response/error size caps
  and one-owner stream consumption. Cancellation closes the owned request/body;
  first validated terminal closes the body immediately. EOF before it fails.
- Disable automatic retries and redirects in the transport. FailurePolicy only
  recommends bounded next actions; no replay, fallback or second request is
  implicit. Transport exceptions expose categories, never endpoint/body/header
  contents or raw provider error messages.
- Pass bounded reads to ProtocolStream without decoding or precharging a whole
  read against protocol response limits. Those limits apply to its consumed
  prefix. A close/cleanup IOException after a validated terminal must not revoke
  Completed; before-terminal I/O failure remains failure. Non-success HTTP error
  bodies require a separate bounded, nonlogging read policy.

## Synthetic test boundary

- Each protocol must have an actual loopback-only Mock HTTP server contract
  exercised through the real HTTP implementation, with an injected test-only
  connector/endpoint seam. Fake transports may add unit coverage but cannot
  replace this wire-contract gate. The seam must live in test sources or remain
  explicitly separate from the production connector; it cannot loosen the
  production HTTPS validator or expose a release HTTP/TLS-bypass setting.
- Bind only ephemeral loopback ports; no public listeners, device ports, DNS
  scans or credentials. Own and close only the fixtures started by the test.
- For each protocol, inspect actual method/path/body/nonsecret headers; feed
  coalesced and fragmented valid/invalid SSE; assert terminal/cancel/timeout/EOF
  cleanup and exactly one request. Preserve the proxy/version path.
- Cover non-2xx and 3xx without follow-up requests, bounded Retry-After metadata,
  slow/truncated bodies, oversized responses and redacted diagnostics. Mock
  server success cannot stand in for a real provider connection or full D04.
- Both actual HTTP contracts must include terminal plus invalid/oversized suffix
  in one server write and in fragmented writes, and prove equivalent typed
  outcomes and prompt owned-body closure. Transport buffering must not recreate
  the decoder's corrected read-boundary defect.

## Before implementation

Freeze the HTTP abstraction and test seam, verify/pin the selected maintained
HTTP dependency from its official source, resolve and lock transitive versions,
and reuse the already verified JDK17/Gradle toolchain and licenses. No installation
of Android tools, new SDK agreement, credential, paid service or security access
expansion is needed. Keep D02 device acceptance on its separate NOT RUN track.
