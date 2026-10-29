# Pinned Android XHTTP backport verification

Date: 29 October 2026 (Asia/Tehran). Application base: `d44f98f`.

## Decision

The new audit's three XHTTP concurrency findings are valid in the pinned Android
core. They were independently reproduced locally, then corrected using the
reviewed upstream backport. The separate reader-close error return is also
corrected. This does not establish that XHTTP caused every reported phone stall,
and cannot explain failures carried over WebSocket or diagnose the Windows
browser's quickly rejected ChatGPT upload.

The duplicated prior stall-review Markdown and ZIP are byte-identical to their
earlier supplied copies. Their diagnostic findings were handled separately;
they are not new evidence of another production transport defect.

## Confirmed findings

| Finding | Evidence | Change |
| --- | --- | --- |
| Buffer length read after ownership transfer | Unpatched Go race detector reports `Buffer.Clear` versus `uploadWriter.Write`; four of 20 ordinary byte/hash tests duplicated data | Record length before `WriteMultiBuffer` |
| Concurrent unsynchronized client closed flag | Unpatched `PostPacket` / `IsClosed` race reproduced | Use upstream `atomic.Bool` |
| Concurrent response-body publication/close | Unpatched Set/Read/Close race reproduced; the fixture observed a double close immediately | Use upstream atomic body ownership and single-closer logic |
| Lost reader close error | The source returned the writer's nil error when only reader close failed | Return `err2`; keep writer error precedence |

Upstream references: [Xray PR 6665](https://github.com/XTLS/Xray-core/pull/6665)
and [Xray PR 6694](https://github.com/XTLS/Xray-core/pull/6694). Neither reference
proves that the user's particular phone failure had this cause.

## Red/green reproduction

Two separate disposable checkouts used the exact core pin
`5ca6f4b7d4dc20a881d4330e498892697627ec0c`, Go 1.26.10, and MSYS2 UCRT GCC.
The baseline fixture required only the old WaitReadCloser constructor spelling
and removal of its now-unused `done` import; its assertions were not weakened.

- Baseline race run: all three focused concurrency cases failed. The first
  Windows compiler attempt failed because the compiler DLL directory was not
  in PATH; that is retained separately and is not counted as a defect repro.
- Baseline ordinary byte-integrity run, 20 repetitions: four mismatches. Each
  intended 81,920,000 bytes but produced 81,928,192, with the wrong SHA-256.
  This is a laboratory scheduling result, not an everyday upload failure rate.
- Patched race run: all four focused tests passed ten repetitions, including
  byte/hash integrity and none/writer/reader/both close-error cases.
- Patched ordinary byte-integrity run: all 20 repetitions passed.

Commands, with the appropriate checkout as working directory:

```powershell
$env:Path = 'C:\msys64\ucrt64\bin;' + $env:Path
$env:GOTOOLCHAIN = 'go1.26.10'
$env:CGO_ENABLED = '1'
$env:CC = 'C:\msys64\ucrt64\bin\gcc.exe'
go test -race -count=10 -timeout=180s -run '^TestG2RayXHTTP' ./transport/internet/splithttp
go test -count=20 -timeout=180s -run '^TestG2RayXHTTPUploadExactBytes$' ./transport/internet/splithttp
```

No full upstream package race pass is claimed. These are the named fixtures,
not acceptance of unrelated upstream TLS-server or QUIC tests.

## Build integration

- Kept libXray `80263da83e96b2972455b0a94b13ee1a10e51391`, core `5ca6f4b`,
  Go 1.26.10, mobile tools and existing controller/observer/measurement patches.
- Added two LF-normalized patches and the reviewed, now-executed regression
  fixture. No core upgrade, XHTTP tuning, DNS, MTU or recovery threshold change.
- The production builder checks and applies both patches to its owned pinned
  checkout and fails on fixture errors. libXray fixtures resolve that same
  local patched core through an asserted module replacement.
- Metadata separately attests both new patch hashes. Configuration-time and
  task-time native gates reject missing, stale or changed patch attestations.
  The old functional AAR was intentionally tested and rejected by the new gate.
- CI runs the focused native race fixtures three times on Linux, followed by
  existing libXray tests against the actual patched local core. Existing app,
  server, Worker and generated configuration checks remain in place.
- Added 12 build-logic tests for native metadata, missing native libraries,
  incompatible pins, stale AARs, altered patches and required build steps.

Metadata/input hashes are consistency checks, not an independent cryptographic
proof that arbitrary supplied native bytes came from those source inputs.
The native gate tests use explicitly synthetic structural fixtures, not fake
functional Android binaries or claimed device tests.

## Local artifact verification

The AAR was built into a separate candidate directory. The previous AAR,
metadata, main APK and instrumentation APK were retained under ignored
`data/verification/xhttp-2026-10-29/rollback/` before replacing local build inputs.

| Artifact | SHA-256 |
| --- | --- |
| Previous AAR | `13C661AF821ECCB49014A6D25A5DF425DB6B1BBA16DC1F03267D4FF17D087A39` |
| Rebuilt AAR | `7B878E018EC30EB53508EA87A4894F2E79F1BB38491AE2DAC3CFB9C3CA235F44` |
| New debug APK | `E60C8B25AB0B4673B74609172186D308F79D5ADE54E68733169B60308C05D3F1` |
| New instrumentation APK | `BC3B6FCEFFF3EACCC5FCBD6EF061947BF10291ECBFC73E1A34697EB6532AD007` |
| XHTTP concurrency patch | `69EC938B5D186749BFC554A5170C20B6B5464FA86F9F6F67DE6B74C937A7E942` |
| XHTTP close-error patch | `9C3E018016B4592D617BD5FB4FB30C1D78B0F949E32361F6C01E3688CAA150F1` |

All four AAR native entries match Gradle's merged native inputs. All four APK
entries match Gradle's stripped outputs. Raw AAR/packaged library hashes differ
because Android strips debug symbols; that difference was checked, not ignored.

Local checks completed:

- 786 Android unit tests, 56 suites: zero failures, errors or skips.
- 26 build-logic tests: zero failures or errors.
- Debug app and instrumentation APK builds; bundled-core, HEV and native-build
  contract gates passed. Lint: zero errors, 24 warnings.
- 41 Python tests, including 15 analyzer tests; 67 static server checks and
  69 Worker checks passed. Go fixture formatting and Git whitespace checked.

## Diagnostic work retained

The earlier local diagnostic changes now give synthetic requests a shared run
identity and unique request IDs, bounded first/last byte-progress observations,
and bounded object-identity connection IDs. Identity maps are released after a
test; normal browsing is not instrumented. Strict synthetic clients do not hide
redirects or connection retries, with loopback regressions.

The analyzer reconciles partially captured legacy/terminal events, distinguishes
unknown reuse from false, joins the new identities, rejects mixed run clocks,
and reports controlled upload paths separately. Writer completion proves local
acceptance, not acknowledgement of every byte by a remote service.

Before this native fix, the matched phone upload control had 31/32 successful
2 MiB attempts, with one response timeout in the HEV/XHTTP/failover-on condition.
Telegram subsequently sent the 20 KiB and 1.5 MiB files, but those tests used the
old AAR. Neither result is credited as acceptance of the rebuilt native library.

## Deployment and remaining acceptance

At the final local build check ADB listed no phone. The new APK has therefore
not been installed or tested on the physical device in this pass. Installed
APK byte identity, matched before/after uploads, full-VPN engine/transport
acceptance and retained-stream behavior still need the connected phone.

No live Codespace core restart or transport/configuration change was made.
Independent WebSocket failures and the Windows browser's ChatGPT upload error
remain open. No universal speed, no-stall, leak-free or flawless claim follows
from the source-level fixes or passing build checks.
