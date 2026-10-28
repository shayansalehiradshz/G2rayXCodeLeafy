package com.g2ray.gradle;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "The source contract is inexpensive and should be checked on every verification run")
public abstract class VerifyLibXrayBuildContractTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getBuildScript();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getControllerPatch();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getObserverPatch();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getMeasurePatch();

    @TaskAction
    public void verifyContract() throws Exception {
        String script = Files.readString(
            getBuildScript().getAsFile().get().toPath(),
            StandardCharsets.UTF_8
        );
        require(!script.contains("SkipGomobileUpdate"),
            "The production libXray build must not make reproducibility optional.");
        require(!script.matches("(?is).*(?:gomobile|gobind)@latest.*"),
            "The production libXray build must not resolve gomobile or gobind from @latest.");
        require(script.contains("cmd/gomobile@$GomobileVersion"),
            "The production libXray build does not pin gomobile.");
        require(script.contains("cmd/gobind@$GomobileVersion"),
            "The production libXray build does not pin gobind to the gomobile revision.");
        int upstreamBuild = script.indexOf("build\\main.py");
        int finalToolVerification = script.lastIndexOf("Assert-PinnedMobileTool");
        require(upstreamBuild >= 0 && finalToolVerification > upstreamBuild,
            "The production libXray build must attest the mobile tools after the upstream build.");
        require(script.contains("libxray.gobind="),
            "The libXray artifact metadata does not record the gobind revision.");
        require(script.contains("git -C $WorkDir apply --check $AndroidControllerPatch"),
            "The production libXray build does not validate the Android controller patch.");
        require(script.contains("\"-trimpath\""),
            "The production libXray build does not remove local source paths.");
        require(script.contains("-buildid="),
            "The production libXray build does not clear the nondeterministic Go build ID.");
        require(script.contains("--build-id=none"),
            "The production libXray build does not disable the nondeterministic external linker build ID.");
        require(script.contains("libxray.android.controller.patch.sha256="),
            "The libXray artifact metadata does not attest the Android controller patch.");
        require(script.contains("git -C $xrayCoreDir apply --check $XrayObserverPatch") &&
                script.contains("git -C $xrayCoreDir apply $XrayObserverPatch"),
            "The production libXray build does not validate and apply the Xray observer patch.");
        require(script.contains("git -C $WorkDir apply --check $LibXrayMeasurePatch") &&
                script.contains("git -C $WorkDir apply $LibXrayMeasurePatch"),
            "The production libXray build does not validate and apply the measurement-status patch.");
        require(script.contains("libxray.xray.ref=") &&
                script.contains("libxray.xray.observer.patch.sha256=") &&
                script.contains("libxray.measure.patch.sha256="),
            "The libXray artifact metadata does not attest all native source patches.");
        String controllerPatch = Files.readString(
            getControllerPatch().getAsFile().get().toPath(),
            StandardCharsets.UTF_8
        );
        require(!controllerPatch.isBlank(),
            "The Android controller patch is empty.");
        require(controllerPatch.contains("func RegisterDialerController(controller func(fd uintptr) bool)") &&
                controllerPatch.contains("func RegisterListenerController(controller func(fd uintptr) bool)"),
            "The Android controller patch must preserve the boolean socket-controller contract.");
        String observerPatch = Files.readString(
            getObserverPatch().getAsFile().get().toPath(),
            StandardCharsets.UTF_8
        );
        require(!observerPatch.isBlank() && observerPatch.contains("GetObservation"),
            "The Xray observer patch is empty or does not cover GetObservation.");
        String measurePatch = Files.readString(
            getMeasurePatch().getAsFile().get().toPath(),
            StandardCharsets.UTF_8
        );
        require(!measurePatch.isBlank() && measurePatch.contains("PingHTTPRequest") &&
                measurePatch.contains("CheckRedirect") && measurePatch.contains("StatusCode"),
            "The libXray measurement patch is empty or does not validate HTTP results.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new GradleException(message);
        }
    }
}
