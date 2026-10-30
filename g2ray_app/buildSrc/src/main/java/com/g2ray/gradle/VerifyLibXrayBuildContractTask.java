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

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getXhttpPatch();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getXhttpClosePatch();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getWebSocketPatch();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getVlessCancelPatch();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getXhttpLifecyclePatch();

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
        verifyXhttpContract(script,
            Files.readString(getXhttpPatch().getAsFile().get().toPath(), StandardCharsets.UTF_8),
            Files.readString(getXhttpClosePatch().getAsFile().get().toPath(), StandardCharsets.UTF_8));
        verifyWebSocketContract(script,
            Files.readString(getWebSocketPatch().getAsFile().get().toPath(), StandardCharsets.UTF_8));
        verifyVlessCancelContract(script,
            Files.readString(getVlessCancelPatch().getAsFile().get().toPath(), StandardCharsets.UTF_8));
        verifyXhttpLifecycleContract(script,
            Files.readString(getXhttpLifecyclePatch().getAsFile().get().toPath(), StandardCharsets.UTF_8));
    }

    static void verifyXhttpLifecycleContract(String script, String patch) {
        require(script.contains("git -C $xrayCoreDir apply --check $XrayXhttpLifecyclePatch") &&
                script.contains("git -C $xrayCoreDir apply $XrayXhttpLifecyclePatch") &&
                script.contains("libxray.xray.xhttp.lifecycle.patch.sha256="),
            "The native build must apply and attest the XHTTP lifecycle patch.");
        require(script.contains("g2ray_xhttp_request_lifecycle_test.go") &&
                script.contains("g2ray_vless_xhttp_cancel_test.go") &&
                script.contains("go test ./transport/internet/splithttp -run '^TestG2RayX'"),
            "The native build must test cancellation through real XHTTP and VLESS.");
        require(patch.contains("context.WithTimeout(ctxInner, 8*time.Second)") &&
                patch.contains("_ = rawConn.Close()") && patch.contains("case <-ctx.Done():") &&
                patch.contains("onClose: cancelRequest") && patch.contains("cancelStream()"),
            "The XHTTP patch must bound setup, close failed sockets and cancel owned requests.");
    }

    static void verifyVlessCancelContract(String script, String patch) {
        require(script.contains("git -C $xrayCoreDir apply --check $XrayVlessCancelPatch") &&
                script.contains("git -C $xrayCoreDir apply $XrayVlessCancelPatch"),
            "The native build must validate and apply the VLESS cancellation patch.");
        require(script.contains("libxray.xray.vless.cancel.patch.sha256="),
            "The native build must attest the VLESS cancellation patch.");
        require(script.contains("go test ./common/retry ./proxy/vless/outbound") &&
                script.contains("if ($LASTEXITCODE -ne 0) { throw \"The patched VLESS"),
            "The native build must fail when the actual outbound or retry fixtures fail.");
        require(patch.contains("ExponentialBackoffContext(ctx, 5, 200)") &&
                patch.contains("case <-r.ctx.Done():") && patch.contains("r.ctx.Err()"),
            "The patch must fence outbound attempts and interrupt retry backoff on cancellation.");
    }

    static void verifyWebSocketContract(String script, String patch) {
        require(script.contains("git -C $xrayCoreDir apply --check $XrayWebSocketPatch") &&
                script.contains("git -C $xrayCoreDir apply $XrayWebSocketPatch"),
            "The native build must validate and apply the WebSocket patch.");
        require(script.contains("libxray.xray.websocket.patch.sha256="),
            "The native build must attest the WebSocket patch.");
        require(script.contains("go test ./transport/internet/websocket -run '^TestG2RayWS'") &&
                script.contains("if ($LASTEXITCODE -ne 0) { throw \"The patched WebSocket"),
            "The native build must fail when the WebSocket fixtures fail.");
        require(patch.contains("NetDialContext:") && patch.contains("WebsocketHandshakeContext(dialCtx)") &&
                patch.contains("if nBytes > 0") && patch.contains("_ = pconn.Close()"),
            "The WebSocket patch is missing the deadline, byte-integrity or socket-cleanup fix.");
    }

    static void verifyXhttpContract(String script, String patch, String closePatch) {
        require(script.contains("git -C $xrayCoreDir apply --check $XrayXhttpPatch") &&
                script.contains("git -C $xrayCoreDir apply $XrayXhttpPatch") &&
                script.contains("git -C $xrayCoreDir apply --check $XrayXhttpClosePatch") &&
                script.contains("git -C $xrayCoreDir apply $XrayXhttpClosePatch"),
            "The native build must validate and apply both XHTTP patches.");
        require(script.contains("libxray.xray.xhttp.patch.sha256=") &&
                script.contains("libxray.xray.xhttp.close.patch.sha256="),
            "The native build must attest both XHTTP patches.");
        require(script.contains("go test ./transport/internet/splithttp -run '^TestG2RayX'") &&
                script.contains("if ($LASTEXITCODE -ne 0) { throw \"The patched XHTTP"),
            "The native build must fail when the focused XHTTP regression tests fail.");
        require(patch.contains("+\t\tn := int(buff.Len())") && patch.contains("atomic.Bool") &&
                patch.contains("reader.Swap(nil)"), "The XHTTP concurrency patch is missing its ownership fixes.");
        require(closePatch.contains("+\t\treturn err2"), "The XHTTP close-error patch is empty or incomplete.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new GradleException(message);
        }
    }
}
