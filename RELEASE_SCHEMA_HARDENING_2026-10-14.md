# Android Release Persistence Schema Hardening

Reviewed baseline: `29304ec`, after the network-aware route-learning change.

## Verified Defect

The release variant enables R8 shrinking. `AccountRepository` uses Moshi
1.15.2 with `KotlinJsonAdapterFactory` to serialize five saved-data models and
three enums, but these types had no keep rules.

An actual minified baseline build renamed every persisted model and enum.
For example, `G2RayAccount` became `yi0`, its fields became `a`, `b`, etc., and
its `priority` field was removed. The packaged DEX also had a changed account
constructor and no Kotlin metadata on that model. The pinned Moshi enum
adapter uses reflection on enum field names. These transformations violate
the persistence/reflection contract, risking adapter initialization failures
and incompatibility with existing saved JSON. See the primary
[Moshi reflection guidance](https://github.com/square/moshi#r8--proguard).

This is a release-packaging defect, not evidence that it caused stalls in the
previously installed, unshrunk debug app. No production release was installed
to reproduce an on-device crash or a saved-data upgrade failure in this pass.

## Changes

- Keep precisely the five persisted models and three enum types, including
  their names, constructors, members, and enum constants. No whole-model-package
  keep rule, serialization-format migration, or runtime dependency was added.
- Add `verifyReleasePersistenceSchema`, connected to AGP's actual R8 mapping
  artifact and its kept-member (`seeds.txt`) and removed-member (`usage.txt`)
  reports. It rejects renamed/missing models, changed field or enum names,
  and removed persisted fields. Missing reports fail closed.
- Require the native-bundle and schema checks for APK and bundle packaging,
  including `packageRelease` and `signReleaseBundle`, not just aggregate tasks.
- Add 14 build-logic tests and run them in Android CI.
- Extend the production acceptance matrix with same-key, data-preserving
  release-upgrade checks.

The first checker assumed unchanged fields always appeared in the mapping.
The fixed build disproved that assumption: R8 omits identity fields. The
corrected checker combines all three reports, and a kept-member entry cannot
hide an actual removal. Tests cover both cases; the final checker was also
run independently against real broken and corrected R8 outputs.

No server, Worker, DNS, packet-engine, transport, socket, or native-source
setting changed in this fix. The network-aware measurement changes are
documented separately in `NETWORK_AWARE_ROUTE_LEARNING_2026-10-14.md`.

## Changed Files

- `g2ray_app/app/proguard-rules.pro`
- `g2ray_app/app/build.gradle.kts`
- `g2ray_app/buildSrc/build.gradle.kts`
- `g2ray_app/buildSrc/src/main/java/com/g2ray/gradle/VerifyPersistedJsonSchemaTask.java`
- `g2ray_app/buildSrc/src/test/java/com/g2ray/gradle/VerifyPersistedJsonSchemaTaskTest.java`
- `g2ray_app/buildSrc/src/test/resources/persisted-schema-valid.txt`
- `.github/workflows/android-tests.yml`
- `g2ray_app/PRODUCTION_TEST_MATRIX.md`
- This evidence note.

## Completed Local Verification

| Check | Result |
| --- | --- |
| Final checker against real old R8 output | Rejected all eight renamed types and the removed account field |
| Final checker against corrected R8 output | Passed |
| Build-logic regressions | 14 passed; zero failures, errors, or skips |
| Full Android unit suite | 391 tests / 31 suites; zero failures, errors, or skips |
| Android lint | Zero errors; 26 warnings remain |
| Functional debug APK | Passed native hash/API, HEV ABI/hash, and pinned-builder gates |
| Debug instrumentation APK | Built successfully; not executed on a device |
| Minified release APK | Built successfully with the schema gate before packaging |
| Minified release bundle | Built successfully with the schema gate before signing |
| Packaged release DEX inspection | All eight types retained Kotlin metadata; account priority, route-network keys, and key enum names were also checked directly |
| Whitespace validation | `git diff --check` passed |

Commands from `g2ray_app`:

```powershell
./gradlew.bat -p buildSrc test --offline --no-daemon --console=plain
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleFunctionalDebug :app:assembleDebugAndroidTest --offline --no-daemon --console=plain
./gradlew.bat :app:assembleRelease --offline --no-daemon --no-configuration-cache --console=plain
./gradlew.bat :app:bundleRelease --offline --no-daemon --no-configuration-cache --console=plain
```

The last two commands require the existing `G2RAY_RELEASE_*` signing settings
and a matching native AAR. They were run with isolated, short-lived test
signing keys, with configuration caching disabled. Those artifacts were not
installed, distributed, or published. No signing material is tracked.

Private before/after build logs, R8 reports, and DEX inspection output are
retained under ignored `data/verification/release-schema-*` directories.
The initial verifier's false rejection is retained there too, not presented
as a successful packaging run.

## Remaining Acceptance

- ADB showed no phone at the final local check. The new functional debug APK
  was not installed or device-tested; the release APK used a different,
  disposable signing certificate and must not replace the user's app.
- Mapping and DEX checks are artifact evidence, not a minified-runtime JSON
  round trip. A same-production-key release upgrade must preserve accounts,
  keys, modes, profiles, selection, and measurement history through relaunch.
- No speculative migration for JSON written by an earlier wrongly obfuscated
  release was added. Such data would require its exact prior schema/evidence.
- Source/Linux CI must be checked against the pushed commit independently of
  these local results. Coreless CI is not a functional native APK build.
- No large performance transfers or video sessions were run. The user's
  current mobile-data budget permits only small probes. Idle recovery,
  handoff, OEM background behavior, long media/AI sessions, and loaded
  throughput still need physical-device/ISP acceptance.

This fix improves release compatibility and prevents a concrete packaging
regression. It is not a measured throughput improvement, a zero-drop
guarantee, or evidence that the overall VPN is flawless.
