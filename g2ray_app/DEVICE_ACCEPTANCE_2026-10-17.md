# Physical-device acceptance: 17 October 2026

## Scope and Installed Build

Application source: `6b21d6c56cb5d3a71d47fde1ac64ac3036b60f82`.
Device: Samsung Galaxy A52s (SM-A528B), Android 14, arm64, LTE.

The functional debug APK and instrumentation APK were packaged with the existing
libXray/HEV hash, ABI, and builder-contract gates. The previous installed APK was
preserved privately, and its signing certificate was verified to match the new
APK before an in-place `adb install -r`. No uninstall, data clearing, credential
replacement, radio toggle, or lock-policy change was performed.

Installed base APK SHA-256, independently read back from the phone:

`d7d5ae391b9c7c7d8189cc64d36ba13b53471e11a02b14000b4f83be2afc6cae`

Instrumentation APK SHA-256:

`187e613e383f87cf5a9db0dcdcdf3d20512c737a7bb80db9159250a5b4b77e76`

The owner's saved settings were preserved: Fast DNS enabled, IPv4-only enabled,
DPI compatibility disabled, seamless failover enabled. These are the settings
tested, not proposed new defaults. The Ping All check separately verified that
it did not change the selected account, profile, or key.

Each live VPN run explicitly used `smallProbesOnly=true`; no large transfer or
video sample was requested. Full-VPN checks used ordinary, unprotected HTTPS
sockets, complete expected responses, and the existing two-of-three within six
seconds acceptance gate. Response times below include the full small response;
they are not ICMP ping, isolated edge RTT, page rendering, or AI first-token time.

## Completed Device Checks

All rows in this table reached an explicit passing instrumentation result.
Endpoint order for response triples is Google gstatic, Cloudflare, example.com.

| Check | Observed result |
| --- | --- |
| Packaged native cores | All three tests passed: real ABI loading, generated DNS/TUN contract, and a real multi-outbound native start/stop. |
| Home Start VPN with stopped host | Auto selected HEV/WS. GitHub independently showed the Codespace transition from Shutdown through Queued to Available. The app's direct-GitHub recovery reached verified VPN traffic in 40,143 ms. |
| Home HEV/WS after 60,007 ms idle | VPN activity remained stopped; screen interactive. All three original HTTP/2 connections were reused, with responses in 230 / 243 / 253 ms. Fresh responses took 1,702 / 1,721 / 1,726 ms. |
| Native/XHTTP after 60,007 ms idle | Verified warm startup took 1,758 ms. VPN activity remained stopped; screen interactive. Original HTTP/2 connections were reused, with responses in 248 / 258 / 254 ms. Fresh responses took 534 / 693 / 623 ms. |
| Native/XHTTP repeated core failure | Two deliberate phone-local native core stops recovered without another manual Start. Complete recovery cycles, including independent HTTPS requests, took 7,089 / 6,869 ms. |
| HEV/WS repeated core failure | Both deliberate core stops recovered without another manual Start. Complete cycles, including independent HTTPS requests, took 12,082 / 6,816 ms. |
| Recovery control plane during HEV/WS | The production socket-protection path succeeded before connect. The configured direct-GitHub coordinator returned Ready in 811 ms while normal VPN traffic worked. |
| Home Ping All action | All 26 profiles finished in 19,740 ms: 25 real delay results and one explicit failure. Results were persisted, selection was unchanged, and the native probe core was released. This invokes the actual ViewModel action, not a rendered button click. |
| HEV/WS after 310,009 ms screen-off | The display remained noninteractive, the VPN activity stayed stopped, and the service remained Connected. Retained-pool responses took 1,478 / 2,973 / 1,965 ms; each retried on a replacement connection. Fresh responses took 1,491 / 1,496 / 1,492 ms. The run reached a passing terminal result. |

One initial combined Native test invocation omitted the explicit
`liveCoreRecovery=true` opt-in. The runner correctly marked that recovery test
skipped; it is not counted as a recovery pass. The separate Native and HEV
opt-in runs in the table above both completed their two injected failures.

## Longer Screen-Off Check

The first HEV/WS 310-second screen-off attempt stopped when the display became
interactive about 63 seconds into the controlled interval. Its unchanged
screen-off assertion failed before post-idle HTTPS requests. It is not a passing
idle check or evidence of an upstream stall. The original output remains at
`data/verification/device-final-20261017/hev-ws-310s-screen-off.txt`.

The separate repeat completed the actual 310,009 ms screen-off interval and
passed both retained-pool and fresh HTTPS gates without reopening the VPN UI.
Its terminal result is retained at
`data/verification/device-final-20261017/hev-ws-310s-screen-off-repeat.txt`.
The HTTP client replaced the expired original connections; this proves bounded
request recovery in this run, not uninterrupted survival of those sockets.
USB-powered screen-off is not unplugged deep Doze. The interrupted first
attempt remains recorded rather than being overwritten by the passing repeat.

## Live Server Cross-Check

A read-only SSH inspection after phone-triggered wake confirmed Xray 26.7.28
(`5ca6f4b`, Go 1.26.5), origin TCP Fast Open disabled, DNS `UseIPv4`, and routing
`AsIs`. Local XHTTP OPTIONS returned HTTP 200 in 0.367 ms; the public route
returned HTTP 200 in 54.515 ms. Those are Codespace-side samples, not phone
latency measurements.

The server checkout was `a9851211f947d20747dc3e9ba76d4ccbe387e4ea`, with a clean
working tree. Its server runtime, devcontainer, post-start script, and Worker
source are identical to the tested application-source revision. Config and UUID
file hashes match the previous read-only record. No production engine restart,
configuration regeneration, or UUID rotation was performed during this pass.
The existing bounded exit-location diagnostic also obtained two agreeing US
observations from ipinfo and country.is, with one observed server IPv4 exit.
This is server-egress country evidence, not an independent phone leak test or
a promise about every site's geolocation database.

## Evidence and Remaining Acceptance

Raw device output, the previous installed APK, and the server cross-check remain
private and ignored under `data/verification/device-final-20261017/`. Do not
publish private runtime configs, app preferences, credentials, or raw diagnostics.

The final local Android regression run actually reran all 653 tests across 47
suites, with zero failures, errors, or skips. Lint completed with zero errors and
25 existing warnings. No APK was replaced during an active device test. The
installed base APK hash still matched after the final run.

Android's post-install process-exit records contained eight new entries, all
normal `finished inst` stops. No new crash, native-crash, or ANR exit was observed
in this bounded pass. Test cleanup stopped the VPN and released the foreground
service; the server was left running. Phone-wide mobile-byte deltas include
other apps and are not attributed to probe payload or used as throughput data.

These bounded checks establish the observed startup, retained-response, ping,
and local-core-recovery behavior on this phone and network. They do not prove
zero stalls, uninterrupted survival of every original socket, all-app coverage,
or a universal performance advantage over another VPN client.

Current-source Native/XHTTP five-minute idle, true Wi-Fi/LTE handoff, unplugged
Doze, signed-release upgrade, multi-hour sessions, full-page rendering, sustained
video, and real cloud-AI stream acceptance remain separate requirements in
[the production matrix](PRODUCTION_TEST_MATRIX.md). No new sustained bandwidth
or video/AI benchmark is claimed from these small probes. The observed cold
startup took about 40 seconds; it must not be described as instant.
