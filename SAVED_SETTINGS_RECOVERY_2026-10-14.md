# Saved-settings Recovery and Device Follow-up

Date: 14 October 2026, Asia/Tehran. Source baseline: `1b11d24`.

## Verified Defects

The pre-fix runs compiled and executed the assertions below. Their failing
XML reports are retained privately under `data/verification/`, not shipped
with accounts, keys, or raw profile links.

- Six regressions failed against the unchanged baseline: legacy migration
  write failure, migration-version read failure, shared-Worker migration write
  failure, ignored VPN-only legacy configuration, deletion of unsupported
  legacy configuration, and an unhandled ViewModel VPN-preference read error.
- Two additional storage-contract regressions failed after introducing only
  a deferred test-store factory: a failed commit acknowledgement left changed
  values in memory, and a failed remove acknowledgement erased the original.
- An initial rollback guard was too broad. A regression reproduced an optional
  scan-history failure blocking an unrelated VPN setting read. The guard was
  narrowed before publication; affected reads and all new writes still wait
  for confirmed restoration.
- Three service regressions then proved that restart-profile reads, writes,
  and removal did not use the Application's shared guarded store. Remaining
  fresh store constructions in sticky-start and Stop paths were corrected too.
- A checked storage exception reproduced a repository change remaining
  published despite its write failing. Snapshot rollback now covers checked
  exceptions as well as runtime exceptions.

These stages are distinct: not all failures were observed on the original
baseline, and the intermediate guard defect is recorded rather than hidden.
They establish specific failure paths, not the cause of every earlier stall.

Android's `SharedPreferences.commit()` changes its in-memory map before it
waits for disk-write success. This behavior was checked against the local
Android SDK source and [AOSP SharedPreferencesImpl](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/core/java/android/app/SharedPreferencesImpl.java).
The test preference wrapper models changed memory with a failed acknowledgement;
it does not fill a phone's disk or damage a real encrypted file.

## Changes

- Defer encrypted-store opening and retry a failed open on a subsequent
  explicit read. Preserve the original encrypted file, MasterKey scheme,
  account/profile/key IDs, and public ViewModel constructor. No reset or
  plaintext fallback was introduced.
- Share one synchronized store across the Application, repository, UI VPN
  preferences, and service restart-state operations. Restore only touched
  keys after an unacknowledged write; never clear the complete store.
- Parse the complete canonical snapshot before publishing it. Catch migration
  failures during construction, preserve source records, and block ordinary
  mutations until required data loads successfully.
- Migrate valid VPN-only legacy profiles without inventing an empty wake key.
  Preserve unsupported original text and expose an explicit copy action. This
  advisory does not block a valid manual import.
- Load all six VPN settings as one presentation snapshot. An unreadable
  required setting blocks new startup, wake, probes, and configuration writes,
  not Stop or a previously verified active tunnel.
- Add an explicit Reload action, with busy/error/readiness states. Reload
  requires the VPN and competing operations to stop; no background reload
  rewrites an active tunnel. Duplicate reload requests do not invent errors.
- Treat optional scan-history read failures as a paused optimizer, not as
  evidence that all VPN settings or a working route are broken.

The Application/store, repository, ViewModel, service, Home/Settings notice,
and focused tests are the only production-code boundaries changed. No server
or Worker runtime, transport default, DNS policy, MTU, native payload,
dependency, socket protection, or account endpoint changed.

## Completed Source and Artifact Checks

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleFunctionalDebug :app:assembleDebugAndroidTest --offline --no-daemon --console=plain
```

- 417 Android tests in 34 suites: zero failures, errors, or skips.
- Lint: zero errors, 25 warnings. Warnings remain; this is not a zero-warning
  release claim.
- Functional debug and instrumentation APK packaging passed the existing
  libXray hash/API, HEV ABI/hash, and pinned-builder verification gates.
- Minified `bundleRelease` passed the same native gates and the R8 mapping,
  kept-member, and removal checks for all eight persisted JSON types. A
  disposable verification-only signing key was used; that bundle was not
  installed or distributed. Real release-signing upgrade acceptance remains
  separate. The unchanged buildSrc verifier task also passed with its 14
  checks reused from the valid local Gradle result.
- The focused tests cover simulated open/read/write/rollback failures,
  migration replay and source preservation, complete snapshot reload, all-six
  preference recovery, shared Application/service wiring, duplicate reload,
  active-tunnel state preservation, and Stop-intent dispatch.

Functional debug APK SHA-256:

`7577b1a086e7da7dd35766e1614cab42fb8e63c21c8f2650b5477cca02fa2d52`.

Source commit `5bbb277469abacb5b11049c7d84c7f58b1845404` passed both hosted
[Android Tests](https://github.com/z2roozsh/G2rayXCodeLeafy/actions/runs/38510066585)
and [Static Tests](https://github.com/z2roozsh/G2rayXCodeLeafy/actions/runs/38510056104).
The latter includes Linux server/Worker behavior, deterministic benchmarks,
the pinned real-core fixtures, retained-helper tests, ShellCheck critical
checks, and Worker packaging dry-run. These are not phone/ISP acceptance.

The clean live checkout fast-forwarded from `1b11d24` to that exact source
revision without an engine restart. Before/after engine PID 258 and the
server-script, config, UUID, and export hashes matched. The running core
reported 26.7.28 (`5ca6f4b`, Go 1.26.5), origin TCP Fast Open remained off,
and the local XHTTP OPTIONS probe returned HTTP 200.

## Phone Evidence

The connected Galaxy A52s runs Android 14, arm64. Both debug APKs installed
in place without clearing user data. The final functional APK's three
packaged-core tests passed, including actual multi-outbound core start/stop.
The installed base APK's independently computed SHA-256 matched the value
above.

The following final-APK tests used controlled screen-off throughout the idle
interval and did not reopen the VPN activity before making requests. Normal
standby routes remained enabled, so they are not forced single-outbound tests.
Every retained-pool and fresh request below met the unchanged six-second
responsiveness gate; the assertion requires at least two of three successes.

| Engine / selected transport | Verified startup | Actual screen-off idle | Retained-pool response times | Original sockets | Fresh response times | Result |
| --- | --- | --- | --- | --- | --- | --- |
| Auto/HEV / XHTTP, ordinary UI Start | 9,277 ms | 310,009 ms | 1,069 / 2,276 / 1,385 ms | All replaced | 1,050 / 994 / 1,114 ms | Passed |
| Native / WS, service Start | 2,711 ms | 310,007 ms | 1,417 / 303 / 313 ms | First replaced, other two reused | 1,472 / 1,562 / 1,817 ms | Passed |
| Native / XHTTP, service Start, repeat | 2,998 ms | 310,007 ms | 657 / 709 / 895 ms | All replaced | 655 / 780 / 837 ms | Passed |

Successful replacement is recovery, not survival of the original socket or
an instant-resume claim. The first UI startup's phase records showed 16 ms in
core startup and 5,711 ms in full-path verification; its 9.3-second total is
not presented as a startup-speed improvement.

The first Native/XHTTP five-minute attempt failed the test's strict screen-off
assertion after an application wake, before post-idle requests were reached.
Its terminal test result was one failure, not a pass. OS power history logged
the wake's caller UID, and a primary-user package lookup mapped it to Samsung
Messaging rather than the VPN app. No messages were inspected or notification
settings changed. The harness's `finally` cleanup then stopped the VPN; that
cleanup is not evidence of a spontaneous tunnel drop. The original log is
preserved alongside the repeat, with no assertion weakened or removed.

The final HEV/WebSocket attempt verified startup in 6,669 ms and passed its
initial HTTPS checks in 1,653 / 2,258 / 2,097 ms. HEV counters advanced after
those verified upstream requests. It then entered controlled 310-second
screen-off idle, but ADB lost the phone before any terminal instrumentation
result was received. This idle result is INCOMPLETE, not passed. The positive
counters in this small-probe mode do not establish transferred-byte accuracy
or throughput.

The planned final-APK repeated-core-failure tests, protected direct-GitHub
check while VPN is active, and final ping-all repeat were not started after
that disconnect. Their earlier-runtime results remain separate. On
reconnection, first inspect whether instrumentation is still running, then
repeat the missing acceptance without clearing app data. The inherited
temporary `log.tag.G2RayVpnService=I` override was still present during this
follow-up; restore its prior empty value before ordinary use. The global
logging property was not changed. A final post-run crash/ANR exit-record
check also remains unavailable while ADB is disconnected.

Before the final service-store/checked-exception corrections, the intermediate
APK (`7a05320aa4baaba6c903a2a15b01d09b5b8b0fe46dbda99f27f3cc8dae10ec71`)
also passed the following checks. They are not attributed to the final APK:

- Ordinary UI Start VPN: Auto/HEV, XHTTP, verified in 4,980 ms. After 60,011 ms
  of background idle without reopening the VPN activity, original HTTP/2
  connections were reused with 320 / 317 / 341 ms responses. Fresh responses
  took 1,056 / 1,113 / 2,124 ms. The screen remained interactive in this run.
- Home's actual ViewModel ping-all action: all 26 configs completed with real
  successful measurements in 26,525 ms; selection remained unchanged and the
  native lease was released. This is action-method/native-path evidence, not
  a rendered button-click test or throughput benchmark.
- Direct GitHub wake/control-plane access with VPN off returned Ready in
  919 ms. The Codespace was already available; `route_ready=false` was not
  misrepresented as authenticated tunnel readiness or cold-start acceptance.

## Acceptance Boundaries

Only small HTTPS/native probes are authorized for this follow-up. No video,
large download/upload, loaded-latency benchmark, or throughput comparison was
run. Screen-off while USB-powered is not unplugged deep Doze. Successful
fresh sockets do not erase retained-pool failures; original connection IDs
and the unchanged two-of-three-within-six-seconds gate must be recorded.

No real Keystore key or encrypted preferences were destroyed to manufacture
errors. Unit tests and rollback guards do not establish power-loss-proof
storage or physical-disk/Keystore fault tolerance. Correctly signed release
upgrade/relaunch acceptance, sustained media/AI sessions, handoff, deep Doze,
battery behavior, and other devices remain separate release requirements.
See [the production matrix](g2ray_app/PRODUCTION_TEST_MATRIX.md). Issue #1
remains open; no flawless, zero-drop, 100/100, or superiority claim is made.
