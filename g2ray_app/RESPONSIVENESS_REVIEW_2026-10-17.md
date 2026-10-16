# Browsing-first responsiveness and consistency

Baseline: `a985121`. User priorities, in order:

1. Web browsing and search: response time and complete page loads.
2. Social feeds and video: refresh time, thumbnails, buffering and continuity.
3. Cloud AI: first response and long-lived streaming continuity.

This pass changes Android optional route measurement/selection, not the server,
Worker, MTU, DNS, fragmentation, socket keepalive, or recovery thresholds.

## Confirmed Gaps and Fixes

The broad route scan previously selected profiles using quick summaries. A
successful quick result normally contains one sample, so it cannot show tail
responses or repeatability. Preserving an older multi-sample history in storage
did not fix this: the selection still used the new single-sample summary.

Discovery remains fast and bounded. Before selecting a replacement, the app now
confirms at most three finalists: the responding incumbent, an alternate
transport when available, and the best remaining quick candidate. Each gets
three response samples. Only a majority-success, multi-sample summary is
eligible for selection or a new throughput test. New confirmation results
replace older statistics; an unconfirmed quick result cannot win the final
comparison. If nothing qualifies, the selection is unchanged.

The normal batch path adds three rounds of at most three small HTTPS HEAD
requests. Rounds use the existing Cloudflare, Google and Apple health URLs;
one unreachable provider does not defeat the majority. Failed confirmation
samples are counted, not retried until they appear successful. Older or
malformed batch APIs fall back to the existing single-profile implementation.
This is optional work while stopped, not an added prerequisite for Start VPN.
Cancellation, native-runtime ownership and network-generation checks remain
active between calls. The selected profile retains the existing 10%/25 ms
switching hysteresis. The scan cooldown and transfer budgets are unchanged.

Individual ping and Ping All also allowed the new Blocked state, even though
that state retains a VPN capture interface after a terminal error. Optional
native tests now require Disabled, Disconnected or Error, matching the route
optimizer. They do not try to measure through a retained protective interface.
Standalone protected Worker/direct-GitHub wake remains available while Blocked.

## What the Measurements Mean

The pinned [libXray measurement implementation](https://github.com/XTLS/libXray/blob/80263da83e96b2972455b0a94b13ee1a10e51391/nodep/measure.go)
times an HTTPS HEAD request through the configured outbound. This is response
latency, not ICMP ping, page-completion time, or sustained throughput. It does
not validate a response body or HTTP status. Therefore these samples are only
selection evidence: startup/recovery must still pass the existing complete-body
VPN data-path health checks. Three samples are not a long-duration percentile
study, and endpoint variation is not an isolated measurement of network jitter.

No flat-throughput promise is made. Video fetching is often bursty; improvement
means fewer stalls, lower response tails and fewer unnecessary switches, not
removing all variation from a speed graph. This pass does not claim a measured
phone TTFB, bandwidth, video or AI-stream improvement.

## Regression Evidence

Three new assertions failed against the baseline: selection accepted an
unconfirmed response, and both optional ping entrypoints allowed Blocked.
Their pre-fix XML is retained locally in ignored
`data/verification/responsiveness-2026-10-17/before-fixes/`.

Focused post-fix runs passed the measurement/selection classes and the existing
health-monitor/handoff classes. Full optimizer fixtures on Android 7 and 12
verify that a one-off fast candidate loses to a steady incumbent, a repeatedly
faster candidate wins, failed confirmations leave selection unchanged, fresh
confirmation replaces old statistics, and a handoff prevents profile writes.
Native-wrapper fixtures cover batching, endpoint failures, fallback, bounded
work, cancellation and temporary-file cleanup.

The final local run passed **653 Android tests across 47 suites**, with zero
failures, errors or skips. Lint reported zero errors and 25 warnings. Functional
debug and instrumentation APK packaging, HEV bundle verification and the
libXray build contract passed. The 32 generated configuration fixtures are
unchanged in structure; real-core validation is a separate hosted gate.
Server static checks and Worker syntax/behavior checks passed locally. No
production server or Worker configuration was changed or restarted.

Reproduce the Android checks from `g2ray_app/` using the installed Java/SDK:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:verifyHevBundle :app:verifyLibXrayBuildContract --console=plain --no-build-cache --max-workers=2
```

Debug APK SHA-256:
`d7d5ae391b9c7c7d8189cc64d36ba13b53471e11a02b14000b4f83be2afc6cae`.
It was built with the existing matching AAR, not installed on a phone. Public
signed-release acceptance is separate from this debug-build verification.

## Phone Acceptance

ADB listed no device during this pass. No APK installation, mobile throughput,
media, physical handoff or long-session acceptance is claimed. Use the existing
[production matrix](PRODUCTION_TEST_MATRIX.md) with these priorities:

1. Same phone/network/config: alternate baseline and candidate, repeat cold and
   reused web requests; record median/p95 response and full-page completion.
2. Refresh Instagram/X/Telegram and play YouTube; record time to visible content,
   time to first frame and rebuffer duration, not only the displayed speed.
3. Run representative ChatGPT/Claude/Gemini streams through idle/resume and a
   physical network change. Record first token, interruption and recovery time.
4. Repeat idle-resume, screen-off, network loss/restore and long sessions for both
   packet engines and transports. Optional scans must not interrupt a live VPN.

Do not run larger transfer/media acceptance without an agreed data budget.
Do not infer phone improvement from Codespace-side HTTP 200 or passing tests.
