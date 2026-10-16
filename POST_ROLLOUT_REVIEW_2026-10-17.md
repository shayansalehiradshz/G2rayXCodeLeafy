# Post-rollout regression review

Reviewed after `3917418`, on 17 October 2026 (Asia/Tehran). The preceding live
rollout is documented in [LIVE_CODESPACE_VERIFICATION_2026-10-16.md](LIVE_CODESPACE_VERIFICATION_2026-10-16.md).
This pass changes Android failure/retry lifecycle handling, not transport, DNS,
MTU, health thresholds, server configuration or Worker deployment.

## Confirmed and Fixed

### Retained capture lost manual wake connectivity

`G2RayVpnService.cleanupTunnel()` unregistered the physical-network callback and
cleared `SocketProtector`'s selection even when retaining the VPN interface.
The UI still offered manual Worker/GitHub wake, whose protected sockets require
that selection. An invalid startup profile also failed before registration.
Ordinary traffic remained captured, but the app could block its own wake call.

Physical-network registration now follows capture establishment, before
preference/profile validation. A retained error guard keeps this callback and
protected control-plane binding; explicit Stop, revoke or destruction releases
them. A blocked network change updates the binding without changing the error
into Starting or launching an implicit retry. Browsing does not gain a direct
outbound or application bypass.

### Retired cleanup could overwrite a replacement service

Android service destruction schedules asynchronous native/descriptor cleanup.
An old instance could complete after a replacement had begun and publish
Disconnected or cancel the shared notification. Releasing old network metadata
could also clear the replacement's protected-socket context.

Global connection/policy state, socket context and notification changes are now
fenced by the current service owner. Each retired instance still closes its
own interface, unregisters its callback and releases its own runtime resources.

### Manual wake displayed an incorrect startup promise

The standalone Wake button does not start VPN. Its ready message now reports
only that the Codespace route is ready. A separate regression verifies that
successful wake leaves a disconnected tunnel disconnected and unverified.

## Evidence and Scope

Before these fixes, a clean rerun of the existing source passed all 622 Android
tests in 47 suites, lint and both functional debug/instrumentation APK builds.
Four new lifecycle scenarios then failed on both Android 7 and Android 12
fixtures: eight failures. The failing XML is retained locally under ignored
`data/verification/final-review-2026-10-16/before-fixes/`.

The patched lifecycle scenarios passed alongside the existing route
reverification and socket-binding regressions. The test fixture gives each
simulated service its own child scope, matching production ownership rather
than letting one service's destruction cancel all simulated services.

The final local full run passed **631 Android tests in 47 suites**, with zero
failures, errors or skipped tests. Lint reported **zero errors and 25 warnings**;
these are dependency/tooling, compatibility and KTX suggestions, not a clean
zero-warning claim. Fresh build-logic execution passed **14 tests**. Both
functional debug and instrumentation APK packaging, HEV ABI/hash verification
and the libXray build contract passed. The builder test emitted 32 configuration
variants for the separately checked real-core validation gate.

Local server static checks, all nine exit-diagnostic fixture groups, Worker
syntax and the Worker behavior suite also passed. No remote production Worker
was changed by these local mock-based checks.

Built artifact SHA-256 values:

- App: `a9b5a426972922c3429fe74655561eff3a4fb8d7c4e3f45cb79b6261b1278924`
- Instrumentation: `187e613e383f87cf5a9db0dcdcdf3d20512c737a7bb80db9159250a5b4b77e76`

### Hosted Fixture Follow-up

The first Android CI run at `253c309` executed all 631 tests but failed the
existing legacy-DNS saturation fixture; the new lifecycle cases passed. The
separate Linux/server/Worker workflow passed all three jobs at that revision.
The initial red Android run is not counted as a passing gate.

The DNS fixture assumed that a completed warm-up Future meant its single
SynchronousQueue worker could accept another lookup. A controlled JDK
reproduction held `afterExecute` after Future completion and reproduced the
legitimate admission rejection. The fixture now synchronizes actual entry into
its intentionally stuck DNS call before starting the 100 ms Future wait. It
still uses the real bounded executor and retains the timeout, rejection, pool
ceiling, zero-queue and call-count assertions. Production DNS code and deadlines
are unchanged. The revised DNS class passed locally.

Final source `bdd922c1d3ba454f5bff400033d6b1a4aac7fd0b` then passed both hosted
workflows:

- [Android Tests](https://github.com/z3roozsh/G2rayXCodeLeafy/actions/runs/38965556125):
  631 tests across 47 suites, no failures/errors/skips; native payload/restart
  fixtures; instrumentation compilation; all 32 generated configurations
  accepted by the verified pinned Xray; release-schema/build-contract checks.
  Hosted lint reported zero errors and 26 warnings, separately from local lint.
- [Static Tests](https://github.com/z3roozsh/G2rayXCodeLeafy/actions/runs/38965561204):
  66 static, 197 server behavior, nine exit-diagnostic groups and 66 Worker
  checks; benchmark budgets, retained-flow diagnostic/race fixtures, real-core
  upload expiry and isolated transports, ShellCheck and Worker dry-run passed.

The full local suite was also rerun after the DNS fixture correction: all 631
tests passed again. The later correction changes tests/documentation only;
the previously built APKs contain the same final production code.

These fixtures use real Android Builder configuration and simulated descriptor,
network and service boundaries. They establish source behavior, not actual
packet routing, OEM process scheduling or native descriptor behavior on a phone.
The acceptance cases are recorded in
[g2ray_app/PRODUCTION_TEST_MATRIX.md](g2ray_app/PRODUCTION_TEST_MATRIX.md).

## Live Host Recheck

The authenticated GitHub Codespaces API still reports the active host Available
in EastUs2. Its tracked checkout was clean at `3917418`. The production Xray
26.7.28 process remained PID 238, and both configuration and UUID file hashes
matched the preceding rollout. Local/edge HTTP probes returned 200 in 1/135 ms;
the supervisor reported a current heartbeat and valid ownership/version.

IPinfo and Country.is again observed the same US IPv4 exit. This is sampled
server-egress evidence. It does not establish universal geolocation agreement,
a permanently fixed address or the exit seen by an independently routed app.
The authenticated XHTTP/WS and retained-flow observations from the preceding
rollout remain separate, explicitly Codespace-side measurements.

No engine restart, UUID rotation, live config rewrite, port-visibility change,
region change, Worker deployment or GitHub branch deletion was needed. GitHub
currently has one branch, `main`.

The live checkout was subsequently fast-forwarded to the reviewed Android fix
and then `bdd922c`; the live engine, configuration and UUID were preserved.
Clean per-transport authenticated checks returned:

| Transport | Google 204 | Cloudflare 204 | Example 200 | HTTP/2 initial / retained |
| --- | --- | --- | --- | --- |
| XHTTP | 427 ms | 415 ms | 219 ms | 118 / 26 ms |
| WebSocket | 182 ms | 101 ms | 188 ms | 183 / 13 ms |

Each later HTTP/2 request began one minute after the initial request and opened
zero new connections. Both separate commands completed successfully and
rechecked PID/configuration/UUID preservation. These are small Codespace-side
SOCKS/edge samples, not phone latency, throughput, packet-loss or media tests.

An earlier combined SSH diagnostic lost its terminal stream before delivering
the complete result; it is not counted as an overall pass. A fresh connection
verified that no owned test processes remained and the production engine was
unchanged. The clean per-transport reruns used SSH keepalive and saved their
output under the live host's ignored `data/verification/final-review-2026-10-17/`.

## Readiness Boundary

ADB did not list a phone during this pass. No installation, signed-release
upgrade, independent-app country/capture test, Doze/handoff acceptance or
sustained media/AI-stream acceptance is claimed. The new APK is a tested source
candidate, not a certification that every device or network is flawless.
Production release-signing inputs are not configured in this environment, so
no signed release bundle or release-upgrade acceptance is claimed. The signing
gate was not bypassed and no disposable release key was created.

The historical retained-connection idle-stall issue is not closed by a small
healthy server sample. Android Always-on plus Block connections without VPN
remains necessary for OS-level protection outside the lifetime of this process.
Codespaces' documented maximum active session also prevents an uninterrupted
single-host service promise. See the official
[Android VPN guide](https://developer.android.com/develop/connectivity/vpn) and
[Codespaces lifecycle](https://docs.github.com/en/codespaces/about-codespaces/understanding-the-codespace-lifecycle).
