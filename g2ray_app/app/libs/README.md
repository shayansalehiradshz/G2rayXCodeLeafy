# libXray AAR

Rebuild the pinned, Android-controller-patched `libXray.aar` here using
[`scripts/build-libxray-aar.ps1`](../../scripts/build-libxray-aar.ps1) before
building a functional VPN APK. A generic upstream/latest AAR is not equivalent
to the attested build recorded in `libXray.properties`.

The Gradle build includes `app/libs/libXray.aar` automatically when the file
exists. Both `assembleDebug` and `assembleFunctionalDebug` require the AAR to
exist and match `libXray.properties`. The build also inspects the AAR's Java
API for a supported modern `invoke(String)` or legacy lifecycle contract, then
verifies the bundled HEV native libraries. Unit-test tasks remain available
without the AAR for coreless UI and storage development; the checked-in
metadata can remain as the pinned rebuild reference. APK packaging still
requires the matching AAR. The binary itself is
ignored by git because it is large and should be rebuilt or supplied from a
trusted release process.
