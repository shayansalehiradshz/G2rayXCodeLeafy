# Current Device and Public-Route Acceptance

Date: 14 October 2026, Asia/Tehran.

## Exact Runtime and Scope

Production Android and live server source for these measurements:
`3dfc2700fd64580f089d833bfe2520a544ec149f`. Xray on the live server remains
26.7.28 (`5ca6f4b`, Go 1.26.5); the managed origin freedom outbound has
`tcpFastOpen: false`.

The current functional debug APK was built through the unchanged libXray,
HEV ABI/hash, and pinned native-builder verification gates. It was installed
in place over the older APK, without uninstalling or clearing account data.
An independent installed-file hash matched this local SHA-256:

`5b43f2bff8c7b413284e0cbdde0b63c2aa261fd5b425bf92b9add0a6aa67660a`.

The phone is the Galaxy A52s, Android 14, arm64, using LTE. No subagents,
lock-policy change, radio toggle, or live server restart was used in this
follow-up. When the owner later authorized PIN entry, the phone was unlocked
between completed tests after inspecting the empty, focused PIN field. The
PIN was not written to project files, scripts, reports, or evidence.
Screen wake/sleep keys were used between or within the
specified test intervals. USB-powered screen-off is not unplugged deep Doze.
Only small HTTPS probes were authorized; every device VPN run explicitly
used `smallProbesOnly=true` and printed that transfer samples were skipped.
No sustained throughput or media-playback acceptance is claimed.

## Five-minute Public-Route Checks

A hash-verified, stripped Linux/amd64 Go diagnostic was compiled locally and
transferred in the approved 2,876,294-byte archive. Go is not installed in the
running Codespace; this helper is an owned diagnostic, not a production
dependency. Its verified executable is retained in private workspace data
for reuse instead of depending on an ephemeral `/tmp` copy.

Separate owned Xray clients used the current private bundle, strict TLS, and
the real public Codespaces edge. Requests completed through SOCKS, not
Android TUN. The clients preserve the exported transport tuning. Both idle
and fresh phases require two of three complete, expected HTTP/2 responses
within six seconds; no gate or timeout was weakened.

Times below are complete HTTPS requests, not ICMP ping or isolated edge RTT.
Endpoint order is gstatic, Cloudflare, and example.com.

| Transport | Actual idle | Retained-pool times (ms) | Original connection handling | Fresh times (ms) | Terminal result |
| --- | --- | --- | --- | --- | --- |
| XHTTP | 310,000 ms | 500 / 615 / 215 | All three replaced | 228 / 111 / 85 | Passed |
| WS | 310,055 ms | 176 / 33 / 29 | First replaced; other two reused | 177 / 84 / 180 | Passed |

The runner verified unchanged active config, UUID, export hashes, and engine
PID 261 at completion, and exited zero. Both transport diagnostics reached
terminal results. These are Codespace-side public-route measurements, not
the phone's ISP path. Replacement is not original-socket survival.

## Five-minute Phone Checks

The ordinary unprotected test sockets traverse Android's default full-device
VPN. Initial and fresh HTTPS are independent from retained-pool requests.
The test backgrounds the VPN activity, sends the sleep key, checks
noninteractivity throughout the elapsed-realtime interval, and does not
reopen the VPN activity before the post-idle requests.

| Requested mode / actual engine / transport | Actual screen-off idle | Retained-pool times (ms) | Original connection handling | Fresh times (ms) | Verified startup | Terminal result |
| --- | --- | --- | --- | --- | --- | --- |
| Auto / HEV / XHTTP | 310,004 ms | 1,104 / 1,093 / 2,249 | Each initially acquired the old connection, then retried on a replacement | 1,075 / 1,192 / 1,062 | 7,031 ms | Passed |
| Native / Native / XHTTP | 310,006 ms | 733 / 772 / 754 | Each initially acquired the old connection, then retried on a replacement | 761 / 779 / 793 | 4,354 ms | Passed |
| HEV / HEV / WS | 310,008 ms | 1,801 / 1,815 / 3,644 | Each initially acquired the old connection, then retried on a replacement | 1,803 / 1,903 / 2,088 | 3,395 ms | Passed |
| Native / Native / WS, final repeat | 310,007 ms | 1,497 / 297 / 295 | First replaced after trying the old connection; other two reused | 1,445 / 1,561 / 1,498 | 3,231 ms | Passed |

The completed runs remained Connected/verified during idle, until the
test's explicit Stop. The ordinary HTTPS client may transparently replace
an expired remote connection; this does not establish uninterrupted survival
of every original socket. HEV TUN counters advanced; Native correctly
reports its byte-counter source as unavailable, not fabricated traffic.

The first Native/WS five-minute attempt was interrupted: the screen became
interactive during the wait, and the unchanged screen-off assertion failed.
It did not reach the post-idle HTTPS phase and is not a passed idle check or
evidence of an upstream stall. Its original terminal failure is retained
privately at `data/verification/device-310-native-ws-current-20261014.txt`.
The repeat verified Native/WS startup in 8,669 ms after an initial route
retry, completed its initial HTTPS in 1,425 / 1,401 / 1,424 ms, and entered
controlled screen-off. ADB then disconnected; no terminal instrumentation
result or post-idle HTTPS was received. It is INCOMPLETE, not passed. The
record remains at
`data/verification/device-310-native-ws-repeat-current-20261014.txt`.
Current-build short-retention, repeated core-failure, and active-VPN
control-plane follow-ups had not been started at that loss. Later
reconnection allowed the completed repeats and recovery results below.
The third Native/WS five-minute attempt reached a passing terminal result,
without an idle-period reconnect or reopening the VPN activity. Both earlier
interrupted attempts remain recorded; the later pass does not erase them.

## Wake, Ping, and Harness Correctness

After reconnection, the current Native/XHTTP APK also completed a short
retention comparison with the phone interactive and the VPN activity still
stopped. After 60,008 ms, all three original HTTP/2 connections were reused;
complete responses took 296 / 344 / 321 ms, and fresh requests took
897 / 770 / 706 ms. The unchanged six-second quorum passed with a terminal
result. Verified startup took 10,250 ms and included an initial route retry;
that delay is not hidden or described as instant. This sample directly
matches the original retained-flow failure window, rather than relying only
on replacement connections after five minutes.

The current HEV/WS APK survived two deliberate phone-local native core
stops, preserving connected intent and proving complete full-VPN HTTPS after
each recovery. End-to-end cycle durations were 12,113 / 12,482 ms, including
failure detection, restoration, and three sequential independent requests.
The post-recovery HTTPS times were 1,872 / 1,836 / 1,800 ms for cycle one and
1,735 / 1,772 / 1,837 ms for cycle two. The test reached a passing terminal
result. This does not imply an existing remote socket survived a core stop.

Native/XHTTP also passed both phone-local core-stop cycles. Complete cycle
durations were 7,659 / 7,211 ms, including the independent HTTPS checks.
Post-recovery responses took 684 / 874 / 694 ms and 705 / 709 / 730 ms.
Its verified startup took 7,300 ms and included an initial route retry.

The current HEV/WS short-retention repeat passed after 60,008 ms, reusing all
three original connections with 293 / 316 / 303 ms responses; fresh sockets
took 1,804 / 1,852 / 1,820 ms. The screen was interactive at the beginning
and noninteractive at the end. This was not a controlled screen-off test.
The test kept the VPN activity stopped and printed verified startup of
7,487 ms. These new short runs do not certify always-instant startup.

The normal Home Start VPN ViewModel action also passed on the saved profile
and engine preference: Auto selected HEV with XHTTP and reached verified
startup in 4,638 ms. After 60,007 ms of interactive idle, without reopening
the VPN activity, all three original HTTP/2 connections were reused and
responded in 314 / 319 / 311 ms. Fresh requests took 1,064 / 1,089 / 950 ms.
The run reached a passing terminal result and explicitly stopped the VPN.
This exercises the actual UI action method, not a rendered button click,
controlled screen-off, or cold-host wake.

The active-VPN direct-GitHub check passed with HEV/XHTTP. The production
control-plane socket was protected before connecting; the independent
descriptor regression confirmed that protection before descriptor
materialization fails and protection after physical-network binding
succeeds. The selected account returned Ready in 983 ms while the ordinary
VPN carried complete HTTPS requests. This does not establish Worker
reachability on the phone's network or a new stopped-host wake result.

An authoritative read-only live follow-up observed source `515b76d`, the
same engine PID 261, the running process's actual 26.7.28 executable,
origin TFO off, and local XHTTP HTTP 200. The panel source hash matched
`3dfc270`; both earlier owned diagnostic core PIDs were absent. The original
source-sync command's observation handle was lost across a chat-context
transition, so its terminal result was not inferred from intent. The live
state was inspected independently instead of restarting that operation.

- The three packaged-core device tests passed: actual ABI loading, the
  generated DNS/TUN contract, and a real multi-outbound native start/stop.
- Direct GitHub on the physical network returned Ready in 1,021 ms. The host
  was already available and `route_ready=false` did not claim a proven VPN
  path. This is warm control-plane access, not a new cold-start or Worker
  reachability result. Saved wake preferences were not rewritten.
- The real Home ping-all ViewModel action completed all 26 profiles in
  26,371 ms: 25 genuine delay results and one explicit failure. The test
  required persisted results, unchanged selection, and release of the
  native core. This is action-method acceptance, not a rendered button click
  or proof that every config works.
- A harness defect was reproduced: `-e engine native` silently selected
  Auto. Its original run is correctly classified above as Auto/HEV, not
  Native. The new device regression failed before the fix. Test arguments
  now accept only `auto`, `hev`, and `xray_native`; unknown values fail. The
  regression passes on the phone after the fix. App defaults are unchanged.

The harness correction is committed as `d99c121`. The corrected test APK
SHA-256 is:
`03e5d2a32a2996170e9f9fb3919debc76561e2c3a0a10e7279a407aeb0016be9`.
The functional APK was not replaced between these device measurements.

## Automated Evidence and Remaining Acceptance

The local full Android unit suite completed 487 tests in 36 suites, with
zero failures, errors, or skips. Lint completed with zero errors and 25
warnings. Functional debug and instrumentation packaging passed. The
test-only correction was also recompiled, repackaged, and linted.

Both hosted workflows completed successfully for the exact harness commit
`d99c1210b83df41f80af12c6c8634e3ddffab587`:
[Android Tests](https://github.com/z2roozsh/G2rayXCodeLeafy/actions/runs/38553661314)
and [Static Tests](https://github.com/z2roozsh/G2rayXCodeLeafy/actions/runs/38553661333).
Hosted source/native fixtures are separate from installed-APK and device
acceptance; they do not package or install a functional phone build.

Before testing this APK, the latest two historical crash records were
inspected through Android's DropBox. Both were AndroidJUnitRunner
`UiAutomation.disconnect` / `DeadObjectException` teardown failures on ADB
loss, not an ordinary browsing-session stack. Older records included the
previously fixed main-thread TLS pool eviction. The current source queues
that eviction on its IO maintenance scope. On reconnection, the new
14:43:14 phone crash record was inspected: it has
the same AndroidJUnitRunner `UiAutomation.disconnect` /
`DeadObjectException` teardown stack after ADB loss. No ordinary application
stack or native-engine crash is established by that entry. This distinction
does not convert the interrupted instrumentation run into a pass.

After the final UI-start and active-VPN checks, a fresh Android process
exit-info inspection found seven subsequent exits, all attributed to
`finished inst`, with no newer crash, native-crash, or ANR exit. The installed
base APK hash was independently checked again and still matched the hash
above. No instrumentation or VPN service remained running after explicit
test cleanup. These bounded observations are not a crash-free guarantee
for untested long browsing sessions or all devices.

Unchanged server/Worker source passed 197 behavior checks, 66 static checks,
65 Worker checks, Worker syntax/packaging dry-run, and all six deterministic
mock benchmark budgets. The standalone Go diagnostic tests passed locally.
Windows Git Bash results are distinct from hosted Linux CI and the actual
live route checks above.

Raw device and public-route records remain private and ignored under
`data/verification/`; raw runtime configs/logs, keys, and UUIDs are not
published. The earlier failed and interrupted runs in
[the rollout record](DEVICE_TFO_ROLLOUT_2026-10-14.md) and
[the original investigation](PERFORMANCE_INVESTIGATION_2026-10-13.md) remain
visible. These new observations do not erase them or isolate the contribution
of each intervening source fix.

Sustained media/AI streaming, loaded latency, upload/download capacity,
actual Wi-Fi/LTE handoff, unplugged Doze, battery impact, other OEMs, and
IPv6/UDP-dependent applications still require acceptance. Issue #1 must not
be closed solely because a retained pool responds through replacement
connections. No flawless, zero-loss, instant-response, or comparative
best-client rating is established by these checks.
