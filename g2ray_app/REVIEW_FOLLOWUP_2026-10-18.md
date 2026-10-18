# Review follow-up and release boundaries

Reviewed baseline: `f2a0c64`. Date: 18 October 2026.

The supplied reviews contain useful boundary cases, but also unsupported speed,
leak, hosting, and readiness guarantees. This follow-up does not change the
working transport, DNS, fragmentation, failover, or route-optimization defaults.

## Verified fixes

1. **Corrupt saved routing data:** malformed arrays, non-string elements,
   invalid domains/packages, and excess entries could throw or enter runtime
   routing. The preferences reader now validates the same rules as the writer
   and falls back to the full-tunnel policy. It does not erase the stored data
   or turn on bypass. Storage-access errors are not reclassified as JSON errors.
2. **Foreign JSON imports:** a plain JSON config list with an absent or newer
   schema must not prevent importing its supported VLESS links. Structured list
   extraction also handles escaped JSON slashes. Each link still goes through
   the normal parser and the 500-profile cap. This does not claim compatibility
   with another application's metadata. Compressed `g2ray://import/` payloads
   still require a recognized bundle; gzip/expanded-size limits remain enforced.
   Only matching-Codespace, supported-schema metadata can update local usage.
3. **Quick Settings stop:** Android can reject a service start request. Tile stop
   now handles background-start/security rejection by opening the app for
   management, not crashing, silently discarding the action, or sending Start.
   The review's specific process-death explanation was not established: the
   connection repository is in-memory, not a persisted Blocked-state journal.
4. **Quota-month rollover:** the usage editor now rechecks the UTC cycle while
   visible and on resume, clears expired used-time fields, and validates the
   draft's cycle again when saving. A previous month's hours cannot be stamped
   into the new cycle simply by pressing Update. Account-specific drafts and
   operation feedback survive rotation. Usage remains a local estimate, not
   authoritative account billing or a promise that the host stayed running.
5. **Small usability/maintenance corrections:** direct ZXing core usage is now
   declared in the version catalog, retaining the already-resolved 3.4.1 core
   and 4.3.0 embedded versions. Ping/Share help is reachable. Tile/routing
   feedback survives rotation. Home again shows verified HEV session totals
   and explicitly identifies live-route verification in progress.
6. **Server deadline completion race:** the full suite exposed a real failure,
   then an isolated delayed-parent reproduction returned success after a
   two-second EXIT cleanup under a one-second budget. Poll counts alone cannot
   determine whether a task finished on time. The wrapper now records command
   completion after its EXIT cleanup, checks elapsed timestamps as well as its
   polling backstop, and rejects late completion even if the parent resumes
   after the worker exits. An on-time fast command is not incorrectly timed out
   merely because its parent was delayed. Clock initialization failure prevents
   task launch; clock failure/backward time during a live task fails closed.
   Normal Bash 5 timestamps avoid adding a subprocess to each polling tick.
   A startup barrier prevents the nested worker from running before group
   isolation is verified; initialization failure cannot leave that worker
   running. The temporary status file remains private instead of being removed
   and recreated during initialization.
7. **Live quota-export defect:** refreshing exports on the active Codespace
   revealed a call to the nonexistent `is_running` helper. A new regression
   reproduced the error without mocking that nonexistent helper. A fresh
   snapshot exported on the live host now marks host activity independently
   of Xray: stopping the proxy does not stop billable Codespace uptime. The
   app still labels projection as conditional on the host staying running.
   Invalid or expired-cycle snapshots remain unavailable; export still does
   not mutate the uptime counters. The bundle/QR round-trip test checks this
   activity flag as well as the original links, tuning, and usage data.
8. **Cached Android config fixtures:** the next hosted Android run restored
   `testDebugUnitTest` from cache but had no generated config files, so the
   mandatory 40-variant core check correctly failed. Locally, changing the
   requested fixture path reproduced an `UP-TO-DATE` success with that path
   absent. When the fixture-export property is requested, the debug test task
   now explicitly passes it to the test JVM and declares the fixture directory
   as an output. The core check keeps its 40-file requirement and now explains
   a missing-output failure. This changes build verification, not the installed
   app's data path. See [Gradle's input/output contract](https://docs.gradle.org/current/userguide/incremental_build.html).

Four new regression tests failed against the baseline before their fixes.
The quota save guard, tile rejection path, and metadata ownership/schema rules
also have targeted unit coverage. Hardware-caused service rejection or a real
calendar rollover is not claimed as a newly observed phone incident.
The separate server delayed-parent reproduction also failed before its fix;
the production regression additionally checks on-time completion and clock
initialization failure. The deadline result is not dismissed as Windows noise.

## Test-count reconciliation

The baseline's executed JUnit XML contains **703 test executions across 48
suites**, representing **619 distinct source methods plus 84 extra SDK
executions**. Robolectric runs several methods on multiple Android versions.
Counting `@Test` annotations is not the same as counting executed cases. The
original 703 figure was correct; it was not 703 distinct source test methods.

The scanner repository is not archived: the GitHub repository API reported
`archived: false`. Its age is a maintenance consideration, not evidence that a
camera rewrite is required. Replacing a working scanner in this patch would add
unnecessary regression risk. See the [upstream repository](https://github.com/journeyapps/zxing-android-embedded)
and [release notes](https://github.com/journeyapps/zxing-android-embedded/releases).

## Platform and protocol claims

| Claim or proposal | Assessment and action |
| --- | --- |
| More transport/core names guarantee faster service | Unsupported. Keep the measured Xray path. VMess/Trojan/other cores are not universal performance upgrades, but declaring every alternative strictly dominated is also not a benchmark. |
| Public Codespaces web forwarding provides a UDP listener | False for the normal forwarded web URL. WireGuard/Hysteria are not drop-in replacements behind that URL. This is an ingress limitation, not proof that the container cannot send UDP. |
| GitHub only supports HTTP/1.1, therefore gRPC/MASQUE are physically impossible everywhere | Too broad. Ordinary HTTP forwarding is not a specification of every internal HTTP version or an authenticated forwarding path. No new gRPC/MASQUE support is claimed without an end-to-end test. |
| sing-box is an established drop-in XHTTP server | Not established. Its current documented V2Ray transport list does not include XHTTP. Do not replace the core on that premise. |
| Container kernel TCP settings only affect the nearby GitHub edge | Incorrect topology. Xray also opens the destination-side sockets. Kernel settings can affect that leg; this does not make inaccessible BBR available or justify new global tuning. The working TFO-off behavior is preserved. |
| Phone DNS only resolves the tunnel endpoint | Incorrect for the normal proxied-DoH path. DNS behavior depends on Fast DNS, app-specific resolvers, cache hits, and the configured routing. No universal doubled-RTT or halved-TTFB promise follows from config inspection. |
| Every link always has exactly one exit IP | Too strong. They share an origin, not a contractual fixed address: NAT, destination family, host restart, and provider routing can affect the observed IP. More ingress configs do not create independent exits. |
| cloudflared could provide another ingress | Plausible experiment, not a proven upgrade. It can bypass GitHub's public edge, but adds a connector/provider dependency, needs the correct hostname/account setup, and still stops with the host. No default deployment was added. |
| WARP egress is a free speed improvement | Unsupported. It changes the exit/path and may help or hurt particular destinations. It also conflicts with an assumed fixed country unless measured. Not enabled. |
| All Tailscale paths must use DERP because there is no public container port | Unsupported. NAT traversal depends on outbound UDP and both peers' networks; lack of a static public listener alone does not prove direct connections impossible. An overlay still cannot wake its stopped host. Not installed. |
| Destination-aware TLS handshake ranking should replace health tests | An optional measurement, not proof of complete HTTP responses, throughput, video playback, or AI streaming. It must retain bounded cost, success verification, and multiple samples. No unmeasured scoring change was shipped. |
| More IP links are automatically better | False. Distinct compatible, reachable paths help; duplicate permutations and low-quality tails add cost. Conversely the current bounded discovery already includes more than a single DNS result, so its entire candidate universe is not just one DNS answer. |
| Change an existing Codespace's region by rebuilding | Misleading. Region choice is associated with creating a Codespace; do not rebuild the working host expecting a region migration. Creating comparative hosts needs explicit quota/cost planning. |
| Worker custom hostname improves VPN throughput | It can improve wake-endpoint reachability; it does not carry the data tunnel or remove host boot time. No Worker change needs deployment in this follow-up. |
| Scheduled wake is the only independent recovery option | False. An external scheduler can call the same authenticated lifecycle API/Worker. Either consumes host quota when it starts the host, and scheduling is not a continuous-availability guarantee. None was added. |

Primary references:

- [GitHub forwarding](https://docs.github.com/en/codespaces/developing-in-a-codespace/forwarding-ports-in-your-codespace)
  documents TCP access and HTTP/HTTPS web forwarding, not a public UDP socket.
- [sing-box transports](https://sing-box.sagernet.org/configuration/shared/v2ray-transport/)
  lists HTTP, WebSocket, QUIC, gRPC, and HTTPUpgrade, not XHTTP.
- [Cloudflare Tunnel routing](https://developers.cloudflare.com/tunnel/concepts/routing/)
  distinguishes published HTTP services from non-HTTP client/connector setups.
- [Tailscale connection types](https://tailscale.com/docs/reference/connection-types)
  distinguishes direct, peer-relay, and DERP paths based on actual connectivity.
- [GitHub region preferences](https://docs.github.com/en/codespaces/setting-your-user-preferences/setting-your-default-region-for-github-codespaces)
  describes region selection for Codespaces creation.

The cited Cloudflare section-2.8 announcement was published **16 May 2023**, not
July 2026. The current CDN-specific terms retain restrictions on serving video
and other large content. Neither a presumed personal-volume exemption nor an
enforcement threshold is established. A second account is not a substitute for
permitted service use. Sources: [announcement](https://blog.cloudflare.com/updated-tos/),
[current terms](https://www.cloudflare.com/service-specific-terms-application-services/).

A region experiment also has to preserve the owner's US-exit requirement. A
faster non-US origin is not an equivalent default. Two current server-side
lookups reported US in this check; that is an observation, not proof that every
site will use the same location database forever.

## Follow-up validation

- Android: 712 test executions in 48 suites passed (628 distinct methods and
  84 additional SDK executions). Fourteen build-logic tests passed. The debug
  app and instrumentation APK built with the matching pinned native inputs.
- Lint: zero errors, 25 warnings. All 40 generated configuration variants were
  accepted by the real Xray 26.7.28 loader, not just parsed as JSON.
- After the fixture-output fix, all 712 Android cases executed successfully
  again and produced 40 newly validated fixtures. Moving only that generated
  fixture directory into a retained backup reproduced missing outputs. The
  identical command then restored all 40 byte-identical files `FROM-CACHE`,
  with the configuration cache reused. The cached restore is not counted as
  another 712 fresh executions. No cache or native validation was disabled.
- The installed app passed all six UI/packaged-core checks. Three additional
  small-probe idle tests passed on the actual Galaxy A52s/Android 14. Saved
  engine/DNS/IPv4 settings were retained. These tests were plugged in with the
  screen interactive; they do not establish unplugged deep Doze.

| Phone sample | Verified startup | Reused-client-pool responses after idle | Fresh responses after idle |
| --- | --- | --- | --- |
| Auto/HEV, XHTTP, UI start | 5,989 ms | 763 / **6,091** / 1,437 ms | 759 / 1,878 / 1,581 ms |
| Native, XHTTP, service start | 2,364 ms | 584 / 734 / 813 ms | 686 / 534 / 557 ms |
| HEV, WebSocket, service start | 3,080 ms | 230 / 244 / 249 ms | 1,531 / 1,522 / 1,522 ms |

The actual idle gaps were approximately 106 seconds, including the activity
relaunch harness overhead. The UI and service startup samples are different
paths and are not an engine benchmark. Reused pool does not imply every old TCP
socket survived: the XHTTP samples acquired replacement connections. The 6.1 s
Cloudflare tail is explicitly retained in the results. The quorum passed because
the other responses were responsive; that does not make the slow request good.
No speculative setting change or default engine switch follows from these few
sequential samples. They also do not establish sustained throughput or media/AI
performance.

The final local server run passed all 210 checks and exited cleanly, with no
remaining Bash processes, after adding deadline completion/clock regressions,
the startup barrier, and the live quota-export regression. Its process-group
rejection check now also proves the command never started. Static checks passed
66/66; Worker checks passed 69/69. A clean runner exit and fresh Linux CI remain
deployment requirements; merely counting printed PASS lines is insufficient.
Private raw device/test evidence is kept under the ignored
`data/verification/claim-review-20261018/` directory, not published with configs.

The installed debug APK was pulled back from the phone and matched the tested
build's SHA-256:

```text
504626fd0cf9c835f63ec8a4665aaf36f686275164bb33e9854737602b528e51
```

No package crash entry was present in the final crash-buffer check. This is
bounded observation, not a guarantee that the app cannot crash. Android
always-on/lockdown settings were not changed. This is a debug upgrade, not
signed-release upgrade acceptance.

The first follow-up rollout (`6e03c18`) was fast-forwarded into the active
Codespace, not left as a request for the owner to pull. Local and public-route
probes passed. Xray's PID/start time, config fingerprint, and UUID fingerprint
were unchanged. Both hosted workflows passed for that revision. The quota
defect above was discovered by that live export check, after the original
automated bundle test had masked it with an invalid helper mock.

The actual-phone mobile-data outage check also passed: after an eight-second
outage, the running tunnel verified in 5,052 ms after network return; a complete
HTTPS quorum finished in 7,942 ms after return. This is a mobile-data outage,
not a Wi-Fi/LTE handoff. The final Quick Settings start left the app reporting
`Route: Ready | Engine: HEV TUN`. No always-on, lockdown, bypass, or transport
default was changed for this test.

## Readiness and the supplied phone plan

The normal-day phone plan is useful: unplugged idle, real handoffs, media, and AI
sessions cover important behavior that source tests do not establish. It is not
true that only the owner can run them: controlled testing on the connected phone
can also supply evidence, with the owner's normal usage providing broader
coverage. A plugged-in screen-off test is not deep Doze. A 20-minute AI
conversation is not necessarily one continuously streaming 20-minute response.

Useful incident notes include local time/timezone, network, selected account and
transport, verified versus merely connected state, affected workload, stall
duration, and whether a fresh request worked. Do not publish live config links
or tokens with the notes. Do not deliberately exhaust the owner's quota.

Reject the supplied claims of guaranteed zero leaks, clean/unblockable
residential IPs, fixed 15-25 ms platform overhead, no head-of-line blocking,
instant imports in every QR size, or guaranteed 10-15 second account switches.
An opt-in bypass is intentionally outside the VPN. UDP/443 blocking is not
filtering QUIC advertisements and does not force every app to use HTTP/2. TCP
keepalive cannot guarantee that a retained socket or AI stream never fails.
The synthetic TUN address ranges alone are not a leak test.

Scores such as 94/100 or 100% ready are judgments, not measured guarantees.
The [workflow notes](WORKFLOWS_AND_OPTIONS_2026-10-18.md) and
[production matrix](PRODUCTION_TEST_MATRIX.md) retain the unresolved acceptance
gates. GitHub also documents a maximum active Codespace lifetime; a single host
cannot promise uninterrupted hosting:
[Codespace lifecycle](https://docs.github.com/en/codespaces/about-codespaces/understanding-the-codespace-lifecycle).

## Two additional reviews of `2fc7148`

The later 96/100 and 67/100 reports describe the same revision. Neither rating,
nor the proposed 82-85 ceiling, is a measured performance scale. The second
review explicitly did not run Android builds or phone tests. Its source review
and reported shell/Worker runs are useful independent checks, not additional
device acceptance. No new production data-path defect was established in these
reports, and no transport/DNS/default change is warranted by them.

| Claim | Assessment |
| --- | --- |
| The eight recent fixes and reconciled test count are real | Supported by the source, executed evidence, and hosted workflows recorded above. This does not establish absence of other bugs. |
| Fully done, 100% production completeness, zero leaks, no regressions anywhere | Not established. The documented acceptance gaps and observed slow response remain. Unchanged data-plane files reduce this patch's scope, but control-plane changes can still affect runtime behavior. |
| A clean residential/datacenter US IP is never blacklisted by AI providers | Unsupported. A cloud exit is not thereby a residential address. Address reputation, account policy, location databases, and destination behavior can vary. |
| Exactly 15-25 ms overhead, guaranteed 10-15 second account switches, or a constant 40-second cold-wake floor | Unsupported as universal numbers. GitHub scheduling, route readiness, quota, network conditions, and destination timing vary. Samples must be attributed to their actual test. |
| Bash 5 timestamps are monotonic | Incorrect. `EPOCHREALTIME` is Unix-epoch wall time. The wrapper additionally checks clock errors/backward time and uses a polling backstop; it is not a kernel monotonic-clock implementation. See [Bash variables](https://www.gnu.org/software/bash/manual/html_node/Bash-Variables.html). |
| The deadline defect only falsely timed out on-time tasks | Incomplete. The reproduced failure also falsely accepted late cleanup. Completion timestamps distinguish both cases. |
| Auto selects HEV for proven maximum throughput with seamless fallback | Auto preference and a bounded engine fallback exist. That is not proof HEV is always fastest or that fallback preserves every established connection. |
| IPv4-only off guarantees faster IPv6 routes to destination sites | Unsupported. It is the fresh client default, but current generated server DNS and freedom egress use `UseIPv4`. Capturing IPv6 on Android is not proof of end-to-end IPv6 egress. No default changed. |
| Route optimization continuously evaluates the best route | Misleading. Optional heavy scans are bounded, cooldown/readiness/network scoped, and prohibited while the VPN is active. It is not continuous throughput benchmarking. |
| No artificial limits or tuning anywhere | Too broad. The exported XHTTP tuning includes POST-size/interval and connection-reuse parameters. Their actual throughput/lifetime effects require the pinned core and a measurement, not an assertion of either no constraints or a fixed global speed cap. |
| Diagnostics off gives literally zero memory or battery cost | Off cancels observation and clears the bounded event history. It is not a proof of zero application overhead; normal VPN health/recovery work continues. |
| Codespaces always means exactly two CPU cores and a universal 60-hour allowance | The local 60-hour value is an estimate, not authoritative per-account billing or a claim about every machine/plan. |
| The pasted cloud-AI API EOF proves a VPN defect | Not established by the message alone. It needs timestamps, the actual request path, and correlated network/service evidence. Do not add transport changes to mask an unattributed upstream error. |

The useful portability finding was confirmed in the test source: sixteen calls
assumed a bare `python` command. The behavior runner now selects a working
Python 3 interpreter (`python3`, then `python` with version validation) and uses
it consistently. No user-installed alias is required. This is a test-runner
improvement, not a VPN speed fix; no production code or app binary is changed.
CI's behavior step deliberately rejects a bare `python` invocation so that
Python-3-only compatibility cannot silently regress on a runner with both names.
The full local run passed all 210 behavior checks with that rejection enabled
and exited cleanly. All 66 static checks passed again. Three isolated checks of
the actual selection block covered preferring `python3`, falling back after an
unusable `python3`, and failing early with neither candidate usable. No Android
rebuild or new phone acceptance is claimed for this test/docs-only follow-up.

The [reverse-proxy guide](../REVERSE_PROXY_EXPERIMENT_2026-10-18.md) separates
fronting GitHub's existing route from a local Tunnel connector, explains the
current origin-override plan requirement, and preserves the direct route for
A/B and rollback. It is an undeployed procedure, not claimed network acceptance.
