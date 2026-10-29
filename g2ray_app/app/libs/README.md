# libXray AAR

Rebuild the pinned, Android-controller-, Xray-observer-, and measurement-patched `libXray.aar` here using
[`scripts/build-libxray-aar.ps1`](../../scripts/build-libxray-aar.ps1) before
building a functional VPN APK. A generic upstream/latest AAR is not equivalent
to the attested build recorded in `libXray.properties`.

The builder uses dedicated, ownership-marked clones under
`g2ray_app/build/native-bundle/`. A custom `-WorkDir` must be new/empty or already
owned by this builder, with a matching upstream origin. Existing unmarked
checkouts are deliberately rejected before reset/cleanup. Choose a fresh build
directory instead of pointing it at personal source, a Git worktree, or a symlink.
`scripts/test-native-build-workspace.ps1` checks these safeguards.

Before packaging, the builder runs the real single and batch measurement
fixtures and observer cleanup/snapshot fixtures against the patched local core,
not an unpatched module-cache copy. CI also runs these with the Go race detector.

The Gradle build includes `app/libs/libXray.aar` automatically when the file
exists. Both `assembleDebug` and `assembleFunctionalDebug` require the AAR to
exist and match `libXray.properties`, including the pinned Xray observer and measurement patches.
The build also inspects the AAR's Java
API for a supported modern `invoke(String)` or legacy lifecycle contract, then
verifies the bundled HEV native libraries. Unit-test tasks remain available
without the AAR for coreless UI and storage development; the checked-in
metadata can remain as the pinned rebuild reference. APK packaging still
requires the matching AAR. The binary itself is
ignored by git because it is large and should be rebuilt or supplied from a
trusted release process.
