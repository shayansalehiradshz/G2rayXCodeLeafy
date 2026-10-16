# IP and DNS leak protection: scope, fixes, and acceptance

Follow-up: the user clarified that the goal is consistent **US egress**, not VPN
invisibility, and reported no observed wrong-country incident. See
[US exit consistency and the patch review](US_EXIT_REVIEW_2026-10-16.md) for the
later capture-retention fixes and current verification. The results below are
the earlier baseline, not test results for that follow-up.

Reviewed 16 October 2026 against the user's active `z3roozsh/G2rayXCodeLeafy`
fork. Its `main` and the local baseline were both `525fc08` before this work.
No live Codespace restart, Windows route/firewall change, phone installation,
or large network transfer was performed for this review.

## What a leak means

A leak is application traffic taking the physical Internet connection instead of
the intended VPN path, revealing the ISP public address or outside DNS lookups.
A different IPv4/IPv6 address is not automatically a leak: compare it with the
authorized exit addresses, not merely with one previously observed server IP.
WebRTC/STUN, DNS, IPv6, and failure-time fallback need separate checks.

Preventing bypasses cannot make a VPN undetectable. Sites can recognize a
datacenter exit or infer VPN use, and can associate accounts/cookies, browser
characteristics, or granted location permissions with a user. Android apps can
also query the system's `TRANSPORT_VPN` network type. A Codespaces
server change cannot enforce routing in a phone or third-party Windows client.

## Confirmed source behavior

- `G2RayVpnService.establishTunInterface()` captures `0.0.0.0/0` and, by default,
  `::/0`. It neither calls `allowBypass`/`allowFamily` nor excludes/allowlists apps.
- IPv4-only compatibility omits IPv6 addresses/routes/DNS and does not enable
  `allowFamily(AF_INET6)`. Android documents that an unconfigured address family
  is blocked by default; removing IPv6 here is not itself permission to bypass.
- `XrayConfigBuilder` intercepts port-53 application DNS. The DNS module's DoH
  traffic and all ordinary application ingress use VLESS or the configured
  balancer, never the `direct` outbound. UDP/443 is intentionally blocked, not
  sent directly. That has a protocol-coverage cost and is not a QUIC guarantee.
- Both HEV and Native configurations keep the local SOCKS listener on loopback.
  Private DNS/DoH/DoT and WebRTC still need device-level routing verification.
- Outer tunnel sockets and wake/bootstrap requests deliberately use protected,
  physical-network sockets. GitHub, the Worker, bootstrap resolvers, and the
  upstream ISP can therefore see the physical source of those requests. This is
  necessary infrastructure traffic, not a claim that every app-owned socket is
  anonymized. Never use this control-plane path for arbitrary browsing.
- Closing TUN restores ordinary networking unless the OS blocks it. App-only
  logic cannot guarantee protection after process death or before startup.

## Lifecycle fixes

Android starts an always-on service with `android.net.VpnService`, not our custom
Start action. Previously the service ignored that action. The service now handles
it, enters foreground startup, and restores the selected saved account/profile.
It does not silently choose a stale session from a different Codespace if that
selection is missing. Duplicate system starts share the existing startup job.

On Android 10+, an app Stop request no longer closes an always-on interface or
discards its session behind the OS policy. Home opens Android VPN settings for
this action. The UI reports the actual OS blocking state, with an unknown label
on older Android versions or when no service is running. OEM settings-panel
restrictions fall back to general settings or report unavailability without a
crash. Actual revoke still performs cleanup.

## Required Android setup

1. Build/install the updated app, retaining its existing data and signing key.
2. Keep a working profile selected. Open Home **VPN protection settings**.
3. Select G2Ray, then enable **Always-on VPN** and **Block connections without
   VPN**. The app cannot enable these OS settings for you.
4. Return to Home and verify the blocking state is **Enabled** on Android 10+.
   On older versions, verify both switches directly in Android settings.

This intentionally trades direct-Internet fallback for blocking during outages.
Recovery still needs a reachable configured Worker or direct-GitHub backend.
Always-on/lockdown behavior remains device acceptance work, not a source-test
certificate. See [the production matrix](g2ray_app/PRODUCTION_TEST_MATRIX.md#always-on-and-lockdown).

## Windows and other clients

A read-only Windows snapshot found v2rayN/Xray processes but no active TUN
adapter; the visible default IPv4 route was Wi-Fi. The Windows system-proxy flag
was off. This does not rule out a per-browser manual proxy, and it is not a packet
capture proving a particular leak. It does mean whole-device protection was
not established. No live setting was changed.

- Use the client's full-device TUN/global routing, not just an HTTP/SOCKS system
  proxy. Ensure IPv6 and DNS are tunneled or blocked; remove deliberate direct
  or bypass rules when the requirement is all application traffic through VPN.
- If that client uses sing-box TUN, its documented Windows `strict_route` option
  addresses multihomed DNS leakage and can affect VirtualBox. Confirm the actual
  generated configuration and compatibility; it is not a persistent kill switch
  after the client process exits.
- Crash/stop-time protection needs an independently enforced OS/firewall policy.
  Do not install broad firewall rules blindly: they can disconnect the VPN's own
  endpoint, DNS bootstrap, wake backend, or remote-management connection.
- Repeat the failure tests below for every client. A VLESS URI does not carry
  Windows routing or kill-switch policy, so changing the exported links alone
  cannot provide it.

## Verification and remaining acceptance

The added regression tests cover Android system-start dispatch on API 24/31,
duplicate starts, API-31 always-on Stop handling, saved-profile restoration,
missing selections, truthful OS status, and settings-panel errors. Builder-call
tests check full default routes, IPv4-only family containment, and no app/bypass
exceptions. JSON contract tests cover both packet engines, both transports,
FakeDNS on/off, IPv4-only on/off, and single/balanced routing (32 combinations).

Before the lifecycle fix, the corrected fixtures reproduced five failures in
system-start/Stop handling; normal Stop and routing controls passed. Unit tests
record platform calls/configurations, not real packet routing. Full test/build
outputs are retained locally under `data/verification/leak-protection-*`.

Local verification completed:

| Check | Result |
| --- | --- |
| Android unit tests | 562 tests / 43 suites; zero failures/errors/skips |
| Build-logic tests, freshly rerun | 14 passed |
| Functional debug APK and instrumentation compilation | Passed with the pinned local AAR |
| Lint | Zero errors; 25 warnings remain |
| HEV bundle checksums and libXray build contract | Passed |
| Server syntax/static checks | Passed; 66 static checks |
| Worker syntax/behavior/dry-run packaging | Passed; 65 behavior checks; no deployment |
| Deterministic mocked benchmark | All six budgets passed; not network performance |

The Windows Git Bash server behavior attempt failed at the deadline/EXIT-cleanup
test (`deadline reported 0 while exit cleanup exceeded its budget`). Server code
was unchanged. This local result is not reported as a pass. The independent
Linux run subsequently passed all 197 server behavior checks, including
deadline cleanup, plus 66 static and 65 Worker checks. Codespaces runs Linux,
not Git Bash; no server-runtime workaround or test weakening was applied.

Hosted source verification also completed successfully:

- [Android CI 38944954452](https://github.com/z3roozsh/G2rayXCodeLeafy/actions/runs/38944954452)
  at `6c47974`: downloaded evidence confirms 562 tests / 43 suites with zero
  failures, errors, or skips. Native payload/restart fixtures, build-logic tests,
  instrumentation compilation, lint, and pinned build-input checks passed. Hosted
  lint has zero errors and 26 warnings; the extra warning versus local lint is
  `AndroidGradlePluginVersion`.
- [Static/Linux CI 38945187786](https://github.com/z3roozsh/G2rayXCodeLeafy/actions/runs/38945187786)
  at `2eb3edb`: all three jobs passed, including real pinned-Xray config/upload
  expiry and retained-connection loopback fixtures, standalone diagnostic tests,
  ShellCheck critical checks, mocked budgets, and Worker dry-run packaging.
  The only change after the Android CI revision was a manual dispatch entrypoint
  for the Linux workflow. These fixtures do not test the user's ISP or phone.

The local functional debug APK SHA-256 is
`faff1e3b55c8530f33f958a911103c79d69034462faf5081ac831e246a51f4de`.
It was not installed on a phone during this review.

Physical-device and Windows acceptance is still required:

1. On a controlled network, record the non-VPN ISP public IPv4/IPv6 privately.
   While connected, compare website, DNS, and WebRTC public results with the
   intended VPN exit. A browser's local private/mDNS candidate is not by itself
   evidence that the ISP public address bypassed the tunnel.
2. Observe packets from a separate controlled access point/host while opening
   uncached websites and WebRTC/DoH/DoT connections. Test-site destinations and
   application DNS must not appear directly on the physical path; distinguish
   authorized tunnel/control-plane/bootstrap traffic.
3. Repeat during loss of upstream, native core failure, process death, reboot,
   idle resume, and Wi-Fi/mobile handoff, with blocking enabled. Test Force stop
   separately because it is an explicit OS/user action, not a normal crash.
4. Repeat both packet engines and transports, IPv6-enabled/IPv4-only settings,
   and every third-party client. Verify wake requests still work during outages.

None of these live failure/capture checks was newly run in this review. No
zero-leak, undetectability, flawless-release, or sustained-performance claim is
made from passing source tests.

## Primary references

- [Android VPN guide: routing, bypass, always-on, and lockdown](https://developer.android.com/develop/connectivity/vpn)
- [VpnService.Builder: routes, family blocking, and interface lifecycle](https://developer.android.com/reference/android/net/VpnService.Builder)
- [VpnService: `isAlwaysOn` and `isLockdownEnabled` API availability](https://developer.android.com/reference/android/net/VpnService)
- [Android NetworkCapabilities: the VPN transport is visible to apps](https://developer.android.com/reference/android/net/NetworkCapabilities#TRANSPORT_VPN)
- [AOSP system always-on start implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/connectivity/Vpn.java)
- [sing-box TUN: Windows strict-route behavior](https://sing-box.sagernet.org/configuration/inbound/tun/)
