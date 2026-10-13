# Network-aware Route Measurements

Date: 14 October 2026, Asia/Tehran. Baseline: `feeb7b2`.

## Confirmed Problem

Saved route latency, jitter, failures, and throughput previously carried only
timestamps. A result from Wi-Fi could qualify a standby route on mobile data,
and an old-network transfer sample could influence route optimization for six
hours. The service already cleared its in-memory session health on handoff,
but persisted samples and the account-wide optimizer cooldown were not scoped
to the physical network. This is a source-level correctness finding, not a
measured explanation of every earlier video stall.

Two added regressions failed against the baseline: a fresh unscoped timestamp
qualified as current-path health in the UI and as verified standby evidence
in the service. Their failing XML reports remain private under `data/verification/`.

## Implementation

- Delay and throughput records each carry an optional physical-network key.
  Old records still load without rewriting accounts, credentials, profiles,
  selection, or URI contents. Untagged history is retained but is not current
  network evidence.
- The key uses Android's opaque network handle plus the boot count. It contains
  no SSID, carrier identifier, device identifier, IP address, or location.
  If reading the boot count fails, an application-context process epoch keeps
  measurements useful within that process without trusting them after restart.
- With VPN active, context comes from the service-selected physical network,
  not the VPN default-network overlay. Unknown, offline, and VPN-only contexts
  cannot qualify a saved result as current.
- In-flight probes additionally carry default-network and underlying-network
  generations. A switch away and back invalidates the run even if its ending
  network key is unchanged. Native calls are never force-interrupted; checks
  run between calls and before persistence/import/selection.
- Observation uses passive callbacks, without polling or new permissions.
  The ViewModel owns observation for its lifetime and unregisters on cleanup.
  If registration fails, no uninterrupted-measurement snapshot is issued.
- Home/Settings delay display, sort scores, throughput freshness, service
  standby eligibility, and backup ordering require matching network provenance.
  The existing temporal and sample-confidence gates remain in force.
- Failure counts restart on a different network. Bulk unavailable-config
  cleanup requires repeated fresh failures on the current network and rechecks
  its snapshot before a queued mutation. Individual manual deletion remains.
- Throughput keeps its own provenance: a fresh delay on a new network does not
  certify an old transfer sample. A failure on one network does not erase
  another network's retained throughput history.
- Optimizer cooldowns retain one bounded last-network slot per account. A
  cooldown from another network does not suppress a new-network scan. Visible
  activity network changes request normal automatic optimization, subject to
  the existing readiness, busy, user-preference, and stopped-VPN guards.

This is not an unbounded per-ISP history database: each profile retains its
existing latest delay/throughput records with provenance. It does not claim
that a network handle identifies a permanent ISP path or that an old sample
predicts current congestion/server load.

The platform APIs are documented at
[Network.getNetworkHandle](https://developer.android.com/reference/android/net/Network#getNetworkHandle())
and [Settings.Global.BOOT_COUNT](https://developer.android.com/reference/android/provider/Settings.Global#BOOT_COUNT).
They are available at API 23 and 24 respectively; the app minimum is API 24.

## Verification

Completed locally:

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleFunctionalDebug :app:assembleDebugAndroidTest --no-daemon --console=plain
```

- 391 Android tests in 31 suites: zero failures, errors, or skips.
- Lint: zero errors, 26 warnings. Functional/debug instrumentation APKs built
  with the unchanged libXray and HEV hash/toolchain verification gates.
- Context tests run on both Android API 24 and 31: boot/network replacement,
  VPN overlay rejection, missing boot counter fallback, callback failure and
  cleanup, switch-away-and-back, and conflated underlying-network transitions.
- Workflow tests exercise the actual ViewModel ping-all method and repository
  with an injected test-only native bridge and in-memory secure preferences.
  Stable-network samples persist with matching provenance; a mid-scan handoff
  leaves saved profiles and selection unchanged and clears testing state.
  These deterministic 100 ms fixture values are not real latency measurements.
- Persistence tests cover legacy optional fields, exact profile/selection
  preservation, failure-count isolation, and separate throughput provenance.
- The standard public `G2RayViewModel(Application)` constructor remains present
  in compiled bytecode and still creates real encrypted preferences/native
  managers. The internal constructor enables deterministic workflow testing.

Built functional APK SHA-256:

`9587a0d290e6a45c07c266e932c9bb6085121764f0369c8c07e1b11b5919d653`.

The phone is not currently visible to ADB. This APK has not been installed or
device-tested. No video, large transfer, Wi-Fi/mobile handoff, or battery test
was run in this pass. Earlier TFO rollout/device results are preserved in
[the separate rollout record](DEVICE_TFO_ROLLOUT_2026-10-14.md), not attributed
to this app revision. Hosted CI must be checked for the exact pushed revision.

## Remaining Device Acceptance

1. Install the matching functional and test APKs without clearing saved data.
   Confirm imported legacy links/accounts/keys/selection remain intact.
2. Run ping-all on one physical network, switch to another, and confirm old
   values become stale rather than current successes or deletion candidates.
3. Interrupt a scan with a handoff, including a switch away and back. Confirm
   no mixed-path samples, stuck Testing entries, or selection changes.
4. Verify new-network optimization and cooldown behavior with VPN stopped;
   with VPN connected, verify normal service recovery without a scan/restart
   being triggered merely by the UI's network observer.
5. Repeat small-probe idle, actual core recovery, protected direct-GitHub access,
   and a later sustained-use acceptance when the data budget permits.

No server/Worker runtime, transport default, DNS policy, MTU, socket protection,
credential, native binary, or dependency changed in this pass. Better provenance
prevents an incorrect routing decision; it is not a claimed Mbps/ping gain or
a certification of flawless multi-hour use.
