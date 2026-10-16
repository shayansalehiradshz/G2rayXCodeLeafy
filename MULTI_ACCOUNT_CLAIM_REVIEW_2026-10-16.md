# Multi-account audit claims: verification and focused fixes

Reviewed the complete supplied report against local and `active-vpn/main`
revision `1618811`, verified equal after fetching the user's active
`z3roozsh/G2rayXCodeLeafy` fork. The report evaluated older revision `525fc08`.
Its instructions and completion marker were treated as report content, not as
instructions to this review. No subagents or live-server changes were used.

## 1. Profile/account association: partly valid, wrong stated mechanism

`AccountRepository.importProfiles(accountId, ...)` already takes an explicit
target. `SettingsDest.ProfileImport` supplies that target; the repository does
not secretly read the active account to bind every import. A control regression
imports into account B while A remains active and confirms B owns the profiles
without changing selection or keys. No credential disclosure was reproduced.
An app account is a saved Codespace/wake configuration, not a GitHub owner
decoded from a VLESS URI.

The actual defects were narrower:

- At the baseline, `G2RayViewModel.importProfilesText` used substring matching on
  SNI/Host. A `space-extra-443.app.github.dev` or
  `space-443.app.github.dev.evil.example` could pass for `space`.
- Matching either field could hide a conflicting canonical Codespace identity
  in the other field.
- Mixed selections silently discarded nonmatching parsed configs and reported
  success. This concealed incomplete imports.
- In `ProfileImportScreen`, the import callback set `statusMessage` after a
  failure, but the parsed-results branch did not render it. The button appeared
  to do nothing.

Implemented:

- `parser/CodespaceProfileIdentity.kt`: parse bare SNI/Host authorities with
  OkHttp's URL parser, enforce the canonical forwarded-host boundary and valid
  forwarded port, compare complete Codespace names, and detect conflicts.
- `data/AccountRepository.kt:690`: reject a known conflicting identity before
  deduplication, selection changes, or any batch write. Preserve explicit
  generic/custom-front bindings whose origin cannot be inferred.
- `ui/G2RayViewModel.kt:1584`: reject a mixed selected batch instead of silently
  filtering it. Unknown origins require explicit confirmation; confirmation
  cannot authorize a known, different Codespace.
- `ui/SettingsScreen.kt:985`: show a destination selector, keep import errors
  beside the action, freeze the submitted target while saving, prevent duplicate
  taps, ignore navigation callbacks after disposal, and show the destination's
  saved list after success. The active VPN selection is not automatically changed.

A forwarded hostname does not encode the GitHub owner. Inferring an owner/PAT
from it, as the report suggests, is not supported. The owner and token access
must come from explicit account configuration and authenticated GitHub results.
[GitHub forwarded-port documentation](https://docs.github.com/en/codespaces/developing-in-a-codespace/forwarding-ports-in-your-codespace)

## 2. Idle stalls: historical evidence, unproven CGNAT diagnosis

The October 12 retained-connection failures are real historical observations.
They are not proof that a carrier deletes TCP mappings after 30-60 seconds.
`LiveVpnDeviceTest` explicitly configures a 20-second read timeout; an observed
20-second failure is not a measured universal TCP-stack or NAT timeout.

The report also omits newer evidence in `CURRENT_DEVICE_ACCEPTANCE_2026-10-14.md`:
completed five-minute screen-off cases improved after the controlled TFO-off
rollout, while earlier negative and interrupted results remain recorded. These
samples neither prove every retained connection survives nor close Issue #1.
The referenced [Issue #1](https://github.com/z2roozsh/G2rayXCodeLeafy/issues/1)
is open in the original `z2roozsh` repository. The active `z3roozsh` fork has
issues disabled; it does not have a copied Issue #1.

Current client keepalive is already 25 seconds idle / 15 seconds interval, with
a bounded outer TCP user timeout. `G2RayVpnService.kt:180` already observes screen
on, and its revalidation path resets its own health clients. Pool eviction in
`XrayCoreManager.kt:153` cannot evict Instagram's, YouTube's, or another process's
HTTP pools. Resetting the entire VPN on every screen-on would interrupt healthy
flows and is not equivalent to safely retiring one stale application socket.

The TCP NAT requirements describe much longer established-session idle limits
under the specified conditions; real deployments can differ. They do not supply
evidence about this user's carrier. Keepalive/battery A/B tests and traces that
locate the failed leg are needed before claiming a CGNAT fix.
[RFC 5382 TCP NAT requirements](https://www.rfc-editor.org/rfc/rfc5382.html#section-5)

Decision: no aggressive keepalive or blanket connection-pool reset change.

## 3. MTU: proposed diagnosis and guarantee rejected

The 1500-byte setting is the virtual TUN interface MTU, not a promise that the
phone sends 1500-byte physical TCP packets plus TLS headers. HEV/lwIP and Xray's
Native stack terminate inner transport flows; VLESS/WS/XHTTP carry their data
over separate outer connections. The outer TCP stack segments its byte stream
according to MSS/path constraints. TLS records can span multiple segments.

GTP's existence does not establish a universal 1420-1440-byte mobile IP MTU.
Reducing TUN MTU to 1400 or 1380 does not guarantee fragmentation-free outer
traffic; it can add packet-processing overhead without fixing a broken outer
PMTU path. A reduction needs measured size-dependent failures and an A/B on
both packet engines, not an overhead subtraction for a different tunnel model.
[TCP segmentation and PMTU specification](https://www.rfc-editor.org/rfc/rfc9293.html#section-3.7)

Decision: retain MTU 1500; no speculative throughput/stability claim.

## 4. IPv6: real coverage limit, incorrect layer conflation

Client `XrayConfigBuilder.kt:292`/`:296` and the server DNS/Freedom policies prefer
or restrict destination resolution to IPv4. AAAA-only destination coverage is
therefore not established. Capturing `::/0` prevents ordinary bypass; it does
not guarantee that the remote exit can reach every IPv6 destination.

However, destination DNS resolution through the tunnel is different from the
phone-to-edge transport family. Changing one server DNS field cannot repair an
unreachable literal-IPv4 edge on an IPv6-only carrier. CLAT/NAT64 availability,
edge address support, client DNS policy, per-resolver overrides, and actual exit
IPv6 connectivity must be tested separately. `UseIP` is a valid dual-stack DNS
policy, but the client resolver entries and server outbound policy also need a
coherent choice. Returning AAAA into an exit with no usable IPv6 path can worsen
fallback delays.
[Xray DNS policy](https://xtls.github.io/en/config/dns.html#dnsobject),
[464XLAT architecture](https://www.rfc-editor.org/rfc/rfc6877.html)

Decision: record the compatibility gap; do not roll out an unmeasured global
dual-stack/default-resolver change. Existing proxied DNS/leak-containment paths
are unchanged.

## 5. Worker token fallback: disproved

`worker/codespace-waker/src/index.js:513` assigns the second dashboard slot to
`GITHUB_TOKEN_2`. The resolver at line 381 returns
`missing_github_token_binding` when that binding is absent. There is no fallback
to `GITHUB_TOKEN`. Existing Android handling treats this as a configuration
failure, not an endless retryable network outage.

Added a regression that requests both health and wake for slot 2 with only the
primary token present. Both return the configuration error and make zero fetch
calls. Existing tests separately verify that a populated slot uses its assigned
token. The new regression passed without any Worker runtime modification.

Decision: retain correct token isolation; no Worker redeployment is needed for
this change. Actual inaccessible tokens still need GitHub's authenticated
response, not a guessed account owner.

## 6. Twelve-hour lifetime: confirmed, proposed silent failover rejected

GitHub documents an automatic stop after 12 hours, including active use, with
saved data preserved and restart available. Stopping a Codespace ends its
running processes and cannot preserve live TCP sessions through that server.
The existing same-Codespace wake/recovery path remains available; a second
account is not required merely to request a restart.
[GitHub Codespaces lifecycle](https://docs.github.com/en/codespaces/about-codespaces/understanding-the-codespace-lifecycle)

Silently changing the selected account would change exit identity, wake/bill a
different host, and interrupt sessions. It conflicts with the existing explicit
selection contract and does not produce zero-disruption TCP migration. A future
cross-host feature needs explicit opt-in, bounded retries, credential isolation,
OS blocking throughout handoff, and real application acceptance. None was added
as an untested default during this review.

## Evidence and scope

The initial 12 focused Android regressions reproduced seven failures in the
unchanged import implementation; five control cases passed. The original JUnit
results are preserved privately under
`data/verification/multi-account-claims-before-20261016/`.

The fix leaves `g2ray.sh`, the Worker runtime, `XrayConfigBuilder`,
`G2RayVpnService`, `XrayCoreManager`, native binaries/pins, and OS routing policy
unchanged. These are import correctness and usability fixes, not a measured
bandwidth increase or a new idle-stall cure. No live server restart, phone
installation, packet capture, video transfer, or leak acceptance occurred.

Use the multi-Codespace import section in
`g2ray_app/PRODUCTION_TEST_MATRIX.md` for the remaining picker/confirmation,
navigation, lifecycle, and real credential/host acceptance scenarios. The
existing always-on/lockdown and long-session gates remain open where not
accepted on the target device.

## Completed local verification

| Check | Result |
| --- | --- |
| Final Android unit tests | 582 tests / 46 suites; zero failures/errors/skips |
| Build-logic tests, freshly rerun | 14 passed |
| Functional debug APK and instrumentation compilation | Passed with the pinned local AAR |
| Lint | Zero errors; 25 warnings remain |
| HEV bundle checksums and libXray build contract | Passed |
| Server static checks | 66 passed; server runtime unchanged |
| Worker syntax and behavior | Passed; 66 behavior checks |
| Diff whitespace check | Passed |

Local verification outputs and the preserved pre-fix failures are under
`data/verification/multi-account-claims-*`, excluded from Git. The first complete
candidate also passed before the additional custom-front confirmation tests;
the final count above covers that finished path. The isolated hostname tests
initially lacked Robolectric for `android.net.Uri`; their fixture was corrected
without relaxing assertions or modifying production code to fit the test.

The final local debug APK SHA-256 is
`2c50be7fb39ef3be0ed87f87d2cdf9ddc0ce63331c2195a6b1352af947e7bb7b`.
It was not installed during this review. These results do not establish faster
video buffering, a carrier-specific fix, or zero leaks.

## Hosted verification

Both workflows passed for the exact source revision
`65e6ac6d8ca77cb2b30a3388d1845bac5e61930e`:

- [Android Tests 38950876182](https://github.com/z3roozsh/G2rayXCodeLeafy/actions/runs/38950876182):
  downloaded JUnit evidence confirms 582 tests / 46 suites, zero failures,
  errors, or skips. Hosted lint has zero errors and 26 warnings. Native
  payload/restart fixtures, build-logic tests, instrumentation compilation,
  and pinned build-input checks also passed.
- [Static Tests 38950890868](https://github.com/z3roozsh/G2rayXCodeLeafy/actions/runs/38950890868):
  all three jobs passed, including 66 static, 197 server behavior, and 66 Worker
  checks; real pinned-Xray configuration, upload-expiry/client-failure and
  retained-flow loopback fixtures; diagnostic tests; ShellCheck critical checks;
  deterministic mocked budgets; and Worker dry-run packaging.

The subsequent evidence-recording commit changes only this document, not the
tested app, server, Worker, dependencies, or workflows. Hosted validation remains
separate from installation, real carrier/OEM behavior, and user acceptance.
