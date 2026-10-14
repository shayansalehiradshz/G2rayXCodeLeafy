# Current Audit Claim Review

## Scope

Reviewed the three supplied reports against source baseline
`35a05d999f4526ca8739b6ef90f37f82b42e7369`, the five commits after their
`7ce8107` baseline, the committed acceptance records, and the pinned Xray
26.7.28 source. The canonical Android project is `g2ray_app` in this repository.
Attached scripts and suggested patches are proposals, not commands to execute.
No subagents or new phone tests were run. The user's small-probe budget remains
in force: no media streaming, large transfer, or loaded-throughput test.

## Findings And Decisions

| Claim | Assessment | Evidence / action |
| --- | --- | --- |
| A selected domain's DNS failure prevents numeric-IP backup startup. | Valid at the reviewed old revision; already fixed. | `G2RayVpnService.resolveRuntimeProfiles` now retains a bootstrap failure only when no compatible group resolves. `SocketProtector.resolveTunnelEndpoints` supplies a bounded set, rather than one address. Cancellation and fatal configuration errors retain their separate meanings. See `NETWORK_AWARE_ROUTE_LEARNING_2026-10-14.md` and the endpoint/bootstrap tests. |
| Persisted route measurements are reused across unrelated physical networks. | Valid at the old revision; already fixed. | `StoredG2RayProfile` carries delay/throughput network keys. `RouteMeasurementContext`, ViewModel generation checks, and service freshness checks gate eligibility. Legacy records are still readable, but unknown-provenance values are not current measurements. |
| TFO-off has not reached the live server, which still uses an older core. | Stale. | This follow-up read the live core as 26.7.28 / `5ca6f4b` / Go 1.26.5 and verified origin TFO false, with engine PID 258. The source/config rollout and prior final-APK identity/device evidence are recorded in `SAVED_SETTINGS_RECOVERY_2026-10-14.md`. |
| Green source CI is equivalent to a functional installed APK or successful video use. | Incorrect; the cautious reports correctly reject it. | Keep source, native artifact, installed APK, retained-pool, original-socket, public-edge, and actual-media evidence separate. This follow-up changes no APK production code and installs nothing. |
| There has been no positive phone idle acceptance. | Stale, but acceptance is still incomplete. | The final installed APK has three recorded controlled 310-second screen-off passes. Some original sockets were replaced, so those are responsiveness/recovery results, not universal socket survival. The final HEV/WS idle test was interrupted by ADB loss; repeated core failure and the final crash/ANR review remain incomplete. Historical failures are retained. |
| XHTTP 60,000-byte posts / 10-ms spacing establish a global 48-Mbit/s ceiling. | Incorrect as a global or measured claim. | The arithmetic is a nominal per-session scheduling budget, not measured aggregate throughput or a download cap. `g2ray.sh` already exports bounded POST tuning plus a three-connection xmux pool, request/reuse ranges, and HTTP/2 PING. A larger-chunk preset requires public-edge upload and loaded-latency A/B results before default changes. |
| Python/FastAPI/Uvicorn/Gunicorn or a Safe 32-KB/s mode is the current VPN bottleneck. | Wrong project / unsupported. | Tracked files contain no proposed `main.py`, `gunicorn_config.py`, `requirements.txt`, SubscriptionLab, NetLeafy, or V2Leafy application. The data plane is pinned Go Xray behind Codespaces forwarding; Bash and the Worker are control planes. Python files here are diagnostics/build tests. |
| Replace everything with Nginx + Python + Unix sockets + WARP and obtain guaranteed 150-300 Mbit/s, near-zero latency, and no Google CAPTCHAs. | Unsupported and unsafe as supplied. | No comparable network measurement establishes those promises. The proposed app lacks the `main:app` object it launches, overwrites config before validating it, has no equivalent rollback/readiness or preserved account contract, and uses a malformed `gh codespace` subcommand. Broad process killing, unpinned installs, silent account registration, and changed terms are not acceptable rollout steps. |
| Re-enable origin TFO, use `IPIfNonMatch`, rewrite sniffed destinations, maximize mux, or force `stream-one` by default. | Rejected without new controlled evidence. | TFO has recorded on/off/on stall evidence. Destination rewriting already broke explicit-IP/server-first cases. Current routing is `AsIs`, sniffing is routing-only, and default outer mux remains disabled. OPTIONS success does not validate sustained streaming through an intermediary. Preserve working defaults. |
| `maxEarlyData` / `earlyDataHeaderName` configure WS early data in the pinned core. | Incorrect field names for this version; the underlying idea is viable as an experiment. | The pinned `WebSocketConfig` has neither JSON field. Its builder parses `ed` from the WS path and its dialer sends eligible initial data in `Sec-WebSocket-Protocol`. Android already preserves the supported nested path. Added regression tests and an isolated, opt-in experiment; no generated/live link changes. |
| A Cloudflare alternate ingress or WARP exit is always faster. | Possible experiment, not a performance fact. | An extra network route can improve or worsen ISP peering and destination behavior. A WARP exit is not guaranteed residential, CAPTCHA-free, or faster. A managed Cloudflare Tunnel is a different ingress design, not a magic Codespaces port setting. Quick Tunnels are explicitly for development and lack an uptime guarantee. No registration, tunnel, or egress change was made. |
| IPv4-only DNS preferences, UDP/443 policy, aggregate XHTTP budgets, and long-session acceptance need work. | Valid remaining constraints / investigation items, not all reproduced defects. | DNS `UseIPv4` narrows name resolution; UDP/443 is blocked to encourage TCP fallback, but a drop cannot guarantee instant fallback in every app. Per-session XHTTP queues do not establish an aggregate pre-auth memory/request budget. These require representative compatibility and bounded overload/load experiments, not blanket IPv6 blocking or a speculative core fork. |
| One Codespace can guarantee uninterrupted sessions indefinitely. | False hosting assumption. | GitHub documents a maximum 12-hour active Codespace lifetime, including active use. Independent hosts and wake recovery can restore availability; they cannot migrate an already-established remote TCP socket when its host stops. |
| Numerical project ratings prove performance or completeness. | Subjective. | The supplied 38/100, 79/100, and proposed 100/100 scores are not throughput, latency, packet-loss, or acceptance measurements. No finite review proves an absence of all bugs. |

## Focused Changes

No production transport default or native dependency changed.
No new production defect was reproduced in this pass. Existing fixes were
verified instead of reapplying stale patches or replacing the working system.
Returning several DNS addresses is not proof of immediate success when the
first answer is blackholed; that remains a separate device/network scenario.

- Android parser regression: preserve an encoded WS early-data path, nested
  query values, SNI/Host, ALPN, and route-template identity during IP discovery.
- Android builder regression: preserve the same supported WS path in normal,
  delay-test, HEV, and native-TUN configurations. Do not invent unsupported
  early-data JSON properties or weaken TLS.
- Python diagnostic regressions: preserve the nested WS path and ensure an
  experiment changes only a copied WS link. Keep XHTTP and the source bundle
  unchanged; reject invalid limits and XHTTP-only early-data runs.
- The existing isolated generated-server/client diagnostic accepts optional
  `--ws-early-data-bytes 1..4096`. This is test-only, off by default, and does
  not edit production files. CI additionally exercises `2560` through the real
  pinned core, strict fixture TLS, owned HTTPS origins, and a 70-second idle.

For a Linux host with the verified core and Go installed:

```bash
go build -o /tmp/g2ray-retained-http-probe tests/xray_retained_http_check.go
python3 tests/xray_isolated_server_pair_check.py /usr/local/bin/xray \
  /tmp/g2ray-retained-http-probe --loopback-baseline --idle-seconds 70 \
  --transport ws --ws-early-data-bytes 2560
```

This verifies compatibility and retained-response behavior in a controlled
fixture. It does not measure phone latency, prove the header survives the
Codespaces public edge, or justify enabling early data by default. A phone/
public-edge A/B with the same route is required for that decision.

## Verification And Remaining Work

Current local verification:

- Android: 419 tests in 34 suites; zero failures, errors, or skips.
- Android lint: zero errors, 25 warnings.
- Server static checks: 66 passed locally under Windows Git Bash; Linux
  behavior/runtime evidence is a separate check, not inferred from this.
- Worker syntax and 65 behavior checks passed; no Worker deployment made.
- Authenticated diagnostic unit tests: 14 passed.
- Isolated diagnostic unit tests: 9 passed.
- `git diff --check`: passed.

The first new Android test attempt failed compilation because its delay-config
call omitted the required SOCKS port; that test call was corrected and the
full suite rerun. The first Python attempt incorrectly assumed strict TLS
must serialize `allowInsecure=false`; Xray also defaults an absent field to
false. The assertion now accepts the strict default and still rejects true.
Neither test-harness mistake was a new production defect.

A live-host attempt ran both Python unit suites and read the pinned core/TFO
state, but stopped before the real pair because no Go compiler was installed.
No toolchain was installed and no active engine was restarted. Use the hosted
real-core job for the new fixture rather than claiming this attempt passed.

### Completed Hosted And Live Checks

Both workflows completed successfully for the exact diagnostic/test commit
`2dd9e7497266740a4f971f277d2818601f912ff6`:

- [Static Tests 38515869467](https://github.com/z2roozsh/G2rayXCodeLeafy/actions/runs/38515869467):
  66 static, 197 server behavior, 65 Worker behavior checks (counts independently
  read from their job logs); benchmark budgets, Go diagnostic race tests,
  Python 14 + 9 tests, ShellCheck error-level, and Worker dry-run passed.
- The real-core job passed saturated-upload expiry, three client-validation
  cases, and both generated-server/client runs. Each run also validated all
  12 generated/migrated profile and WS-toggle cases. Normal XHTTP/WS had a
  zero-second idle compatibility check. The WS early-data experiment measured
  a 70,040-ms gap, reused all three original HTTP/2 connections, and returned
  three HTTP 204 retained responses reported as 0 ms at millisecond resolution.
  Fresh responses were 6 / 6 / 6 ms. These are loopback fixture timings, not
  phone latency or a before/after speedup.
- [Android Tests 38515869424](https://github.com/z2roozsh/G2rayXCodeLeafy/actions/runs/38515869424):
  source checks, instrumentation compilation, native build/schema contract
  gates, and native-payload fixtures passed. Hosted source CI does not package
  a functional private-AAR APK or perform a physical-device run.

The clean live checkout then fast-forwarded from `35a05d9` to that exact tested
commit. Engine PID 258 and the script/config/UUID/export hashes remained
unchanged; no engine restart occurred. The installed core remained 26.7.28,
origin TFO remained false, and local XHTTP OPTIONS returned HTTP 200. Direct
GitHub wake preferences were not edited. The final report-only follow-up does
not change any runtime source or test implementation.

Remaining acceptance is explicit: final HEV/WS idle completion, repeated core
recovery, current APK crash/ANR review, real media/AI sessions, sustained
uploads/downloads and latency under load, physical network handoff, unplugged
Doze/battery, signed release upgrades, and broader devices/address families.
The user currently declined more device tests and authorized only small
probes. Issue #1 remains open. No 100/100, instantaneous-everywhere, zero-drop,
zero-CAPTCHA, or universal-client-superiority claim is made.

## Primary References

- [Pinned Xray WS JSON builder](https://github.com/XTLS/Xray-core/blob/v26.7.28/infra/conf/transport_method.go)
  and [WS dialer](https://github.com/XTLS/Xray-core/blob/v26.7.28/transport/internet/websocket/dialer.go).
- [Pinned libXray dependency manifest](https://github.com/XTLS/libXray/blob/80263da83e96b2972455b0a94b13ee1a10e51391/go.mod).
- [GitHub Codespace lifecycle](https://docs.github.com/en/codespaces/about-codespaces/understanding-the-codespace-lifecycle)
  and [TCP port forwarding](https://docs.github.com/en/codespaces/developing-in-a-codespace/forwarding-ports-in-your-codespace).
- [Cloudflare Quick Tunnel limits](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)
  and [WARP performance/location caveats](https://developers.cloudflare.com/warp-client/known-issues-and-faq/).

The linked primary-source schemas and platform limits were checked during this
review. Network-speed conclusions require this project's actual network
measurements, not extrapolation from those documents.
