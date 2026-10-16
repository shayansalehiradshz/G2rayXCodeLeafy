# US exit consistency and external patch review

## Goal and Evidence

The goal is US Internet egress for ordinary applications while using the VPN,
not hiding VPN use or making all device/location metadata look American.
The user confirmed that no site/app has actually shown a wrong country. No
wrong-country incident, physical-IP leak, or European active Codespace is
therefore claimed as reproduced.

Baseline: `b5130c0`, matching `active-vpn/main` in the active
`z3roozsh/G2rayXCodeLeafy` repository. The supplied patch targets older `525fc08`.
Its SHA-256 is
`f6ede665c2f6cccc29f5deb9ca77055d8509864a13e5ce6ea462a009d7bcfa3f`.
Its claimed local commit and test results are not our verification results.
No patch instructions were treated as permission to reset to the old baseline.

## Claim Decisions

| Claim | Decision and evidence |
| --- | --- |
| The new active account probably runs in Europe. | Unverified. GitHub can select a default based on location, but no evidence establishes this host's region or exit. We did not recreate or move it. |
| Changing to a US region guarantees every site shows US. | Too strong. Select a US hosting region, then measure the actual egress IP. Geolocation databases may disagree about that same IP. |
| IPv4-only mode leaks the phone's IPv6. | Incorrect for this configuration. `establishTunInterface()` omits IPv6 and never calls `allowFamily(AF_INET6)`. Android blocks an unconfigured family. Default dual-stack mode captures `::/0`. |
| No explicit port-853 rule means Private DNS bypasses VPN. | Incorrect. `XrayConfigBuilder` catches ordinary ingress with proxy/balancer rules, including DoT and STUN. We did not block port 853 or assume strict Private DNS will fall back. |
| A foreign DNS resolver/CDN answer changes the source exit country. | Layer confusion. DNS resolver and destination/CDN addresses are not the source IP a website observes. Resolver placement can affect performance/localization, not establish a phone-IP leak. |
| Azure `168.63.129.16` is specifically a Virginia/US resolver. | Incorrect. Microsoft documents the same platform virtual IP in all regions. We did not retune working DNS based on that premise. |
| Always-on startup is ignored. | Stale against our current tree. `6c47974` already handles `VpnService.SERVICE_INTERFACE` and restores the saved selected account/profile. Those fixes were preserved. |
| UI preflight/wake delays capture. | Verified in the old `startVpn()` flow. Removed the duplicate UI preflight; service-owned route-first verification and wake recovery remain. |
| Terminal errors and incomplete-runtime repairs release capture. | Verified in the old `failAndStop()`/`cleanupTunnel()` paths and lifecycle fixtures. Fixed by retaining a guard and reporting Blocked, not Connected. |
| Destruction can leave a queued repair/start. | Verified lifecycle risk. Destruction now rejects new starts and suppresses queued restarts. Stop/revoke still release capture. |
| An unused freedom outbound proves app traffic bypassed the tunnel. | Unsupported. There were no application rules selecting it. Removed as defense in depth, with all generated variants checked. |
| App code can guarantee containment after process death/reboot. | Incorrect. Android Always-on + Block connections without VPN and independent-device testing remain necessary. |
| 80/85 or any other score proves a release is stable. | No. Subjective scores are not measurements or acceptance. We record actual checks and limits below. |

## Changes

- `G2RayViewModel`: dispatch Start promptly, cancel optional UI probes/wake,
  prevent duplicate starts, preserve Blocked on launch/configuration errors,
  and leave a newer service state intact if dispatch fails. The service remains
  responsible for waking the selected Codespace after a real route failure.
- `G2RayVpnService`: capture before explicit-start configuration/native/wake
  waits; hold a guard on terminal errors and repair; reuse an idle guard; replace
  an address-family configuration only after a new interface is established.
  Late Stop/destruction closes obsolete descriptors and cannot queue a restart.
  Current account selection, network-generation checks, payload-based health,
  native ownership, real speed counters, and transport settings remain intact.
- Home/Settings: Blocked has retry/disconnect controls, saved settings can be
  reloaded, and Android policy can refresh without retrying a configuration
  error automatically. Known always-on disconnect remains system-managed.
- `XrayConfigBuilder`: no unused direct/freedom outbound in VPN configurations.
- Server option 12 and `--exit-location [--json]`: compare IPinfo and Country.is
  observations over bounded, direct, strict-HTTPS IPv4 requests, matching the
  generated server's normal `UseIPv4` policy. Report `us_observed`,
  `non_us_observed`, `country_disagreement`, `partial`, or `unavailable`.
  Different reported exit IPs are displayed, not automatically called a leak.
  Checks are on demand only, not startup/recovery/per-connection dependencies.

This is a selective port, not the supplied patch applied wholesale. In
particular, it does not replace the newer import/account validation or infer
unknown pre-Android-10 system blocking policy. No core pin, MTU, DNS provider,
Cloudflare feature, live UUID/configuration, or Worker runtime was changed.

## Keeping a US Exit

1. In GitHub Settings > Codespaces > Region, select a US region before creating
   a Codespace. Changing the default does not relocate an existing Codespace.
   Do not delete a working Codespace merely because one database labels it wrong.
2. On the selected Codespace run `bash ./g2ray.sh --exit-location --json`, or
   open option 12. Two US observations are useful evidence, not universal proof.
3. Through the active VPN, open `https://api.country.is/` and
   `https://ipinfo.io/json` in a browser. Compare actual IP addresses as well as
   country labels. A third-party client must really route that browser's traffic.
4. A different country for the *same IP* is a database/localization issue, not
   evidence of direct fallback. A different IP may also be destination-dependent
   server NAT or IPv6 egress; compare it with the physical ISP IP before deciding.
5. If a site shows a different country despite a US-observed exit, inspect its
   saved account region, language, cookies, and granted location permissions.
   Do not disable the mobile radio, change system location/timezone, or spoof
   GPS as an alleged network fix. Those changes do not control IP databases.
6. For startup/process-death/reboot protection, enable Android Always-on VPN and
   Block connections without VPN. The in-process capture guard is not an OS
   firewall. Other clients need their own whole-device/bypass/failure policy.

The shared Worker only wakes hosts; it is not the browsing exit. Edge/route
selection is ingress selection, not country selection. Current recovery limits
fallback profiles to the selected account; choosing another Codespace yourself
may change the exit. No automatic region/account migration was introduced.
Control-plane/outer-tunnel providers necessarily see the physical connection
used to reach them; this is separate from ordinary application egress.

## Verification and Remaining Work

The pre-change lifecycle run had 33 cases: 11 passed and 22 failed. Eight failures
were tests for newly proposed helper behavior (missing helper methods), not eight
additional bugs. The meaningful old-code failures include terminal capture
release, malformed-profile/preference failure without a guard, repair capture
release, and queued repair after destruction, exercised on API 24 and 31.
The unmodified result XML is retained under
`data/verification/us-exit-2026-10-16/before-capture-tests/`.

Focused post-fix service/always-on/saved-settings checks passed. A subsequent
full run exposed a pre-existing fixture timing assumption: Robolectric could
spend the short DNS fault deadline loading the legacy shadow before entering
the simulated stuck resolver. The test now warms that exact path first. Its
100 ms timeout, worker ceiling, zero-backlog, rejection and call-count assertions
remain unchanged; production DNS code was not altered.

Local verification completed:

| Check | Result |
| --- | --- |
| Full Android unit run | 622 tests / 47 suites, zero failures/errors/skips |
| Build-logic tests, rerun | 14 passed |
| Functional debug APK | Built with the pinned real AAR; not installed |
| Lint / instrumentation compilation | Zero lint errors, 25 warnings; compilation passed |
| Native build inputs | HEV hash/ABI checks and libXray build contract passed |
| Server static / syntax | 66 static checks and Bash syntax passed |
| Exit diagnostic fixtures | Nine groups passed without Internet requests |
| Worker syntax / behavior | Passed; runtime unchanged |
| Isolated mock benchmark | All six budgets passed, not network-performance measurements |

The functional debug APK SHA-256 is
`97be6a8c038635590ee3f20635bcc8635af3ae7ab107c0781b039a56a900e05e`.
Hosted Linux behavior/ShellCheck/native checks and real-core validation of all
32 Android-generated configurations are tracked separately after source push.
Four tests for removed UI-only planning helpers were replaced by actual dispatch,
duplicate-start, rejection and service recovery/capture behavior tests; backend
authentication/quota/rate-limit recovery tests remain intact.

No live Codespace
restart, Worker deployment, phone APK installation, independent-app packet
capture, or new throughput/video test is claimed. Signed-release upgrade,
process death/reboot/lockdown, both real packet engines during repair and changed
IPv6 settings, long media/AI streams, handoff and Doze remain device acceptance
work. The historical intermittent idle-stall issue is not closed by this review.

No code can force all services to share one geolocation database, guarantee a
static Codespaces NAT address, or keep a host alive past platform lifetime/quota
limits. A stable dedicated US egress address can improve control over identity,
but even that cannot dictate every third party's country data.

## Primary Sources

- [Android Builder: family blocking, bypass, replacement and close](https://developer.android.com/reference/android/net/VpnService.Builder)
- [Android VPN: always-on and blocking non-VPN connections](https://developer.android.com/develop/connectivity/vpn)
- [GitHub: choosing a default Codespaces region](https://docs.github.com/en/codespaces/setting-your-user-preferences/setting-your-default-region-for-github-codespaces)
- [Microsoft: 168.63.129.16 applies to all Azure regions](https://learn.microsoft.com/en-us/azure/virtual-network/what-is-ip-address-168-63-129-16)
- [MaxMind: geolocation limits and disagreement between databases](https://support.maxmind.com/knowledge-base/articles/maxmind-geolocation-accuracy)
- [Country.is: caller-IP endpoint, data sources and limits](https://country.is/)
- [IPinfo: caller-IP lookup and API limits](https://ipinfo.io/developers)
