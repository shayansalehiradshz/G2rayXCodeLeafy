package com.g2ray.gradle;

import org.gradle.api.GradleException;
import org.junit.Test;
import static org.junit.Assert.assertThrows;

public class VerifyLibXrayBuildContractTaskTest {
    private final String script = """
        git -C $xrayCoreDir apply --check $XrayXhttpPatch
        git -C $xrayCoreDir apply $XrayXhttpPatch
        git -C $xrayCoreDir apply --check $XrayXhttpClosePatch
        git -C $xrayCoreDir apply $XrayXhttpClosePatch
        libxray.xray.xhttp.patch.sha256=hash
        libxray.xray.xhttp.close.patch.sha256=hash
        go test ./transport/internet/splithttp -run '^TestG2RayX'
        if ($LASTEXITCODE -ne 0) { throw "The patched XHTTP
        """;
    private final String patch = "+\t\tn := int(buff.Len())\natomic.Bool\nreader.Swap(nil)";
    private final String close = "+\t\treturn err2";
    private final String webSocketScript = """
        git -C $xrayCoreDir apply --check $XrayWebSocketPatch
        git -C $xrayCoreDir apply $XrayWebSocketPatch
        libxray.xray.websocket.patch.sha256=hash
        go test ./transport/internet/websocket -run '^TestG2RayWS'
        if ($LASTEXITCODE -ne 0) { throw "The patched WebSocket
        """;
    private final String webSocketPatch = "NetDialContext: WebsocketHandshakeContext(dialCtx) if nBytes > 0 _ = pconn.Close()";
    private final String vlessScript = """
        git -C $xrayCoreDir apply --check $XrayVlessCancelPatch
        git -C $xrayCoreDir apply $XrayVlessCancelPatch
        libxray.xray.vless.cancel.patch.sha256=hash
        go test ./common/retry ./proxy/vless/outbound
        if ($LASTEXITCODE -ne 0) { throw "The patched VLESS
        """;
    private final String vlessPatch = "ExponentialBackoffContext(ctx, 5, 200) case <-r.ctx.Done(): r.ctx.Err()";
    private final String lifecycleScript = """
        git -C $xrayCoreDir apply --check $XrayXhttpLifecyclePatch
        git -C $xrayCoreDir apply $XrayXhttpLifecyclePatch
        libxray.xray.xhttp.lifecycle.patch.sha256=hash
        g2ray_xhttp_request_lifecycle_test.go
        g2ray_vless_xhttp_cancel_test.go
        go test ./transport/internet/splithttp -run '^TestG2RayX'
        """;
    private final String lifecyclePatch = "context.WithTimeout(ctxInner, 8*time.Second) _ = rawConn.Close() case <-ctx.Done(): onClose: cancelRequest cancelStream()";

    @Test public void acceptsCompleteXhttpLifecycleContract() {
        VerifyLibXrayBuildContractTask.verifyXhttpLifecycleContract(lifecycleScript, lifecyclePatch);
    }

    @Test public void rejectsMissingXhttpLifecycleBuildStep() {
        for (String line : lifecycleScript.strip().split("\n")) {
            assertThrows(line, GradleException.class, () ->
                VerifyLibXrayBuildContractTask.verifyXhttpLifecycleContract(lifecycleScript.replace(line, ""), lifecyclePatch));
        }
    }

    @Test public void rejectsIncompleteXhttpLifecyclePatch() {
        for (String part : new String[]{"context.WithTimeout(ctxInner, 8*time.Second)", "_ = rawConn.Close()",
                "case <-ctx.Done():", "onClose: cancelRequest", "cancelStream()"}) {
            assertThrows(part, GradleException.class, () ->
                VerifyLibXrayBuildContractTask.verifyXhttpLifecycleContract(lifecycleScript, lifecyclePatch.replace(part, "")));
        }
    }

    @Test public void acceptsCompleteXhttpContract() {
        VerifyLibXrayBuildContractTask.verifyXhttpContract(script, patch, close);
    }

    @Test public void rejectsEveryMissingBuildOrAttestationStep() {
        for (String line : script.strip().split("\n")) {
            assertThrows(line, GradleException.class, () ->
                VerifyLibXrayBuildContractTask.verifyXhttpContract(script.replace(line, ""), patch, close));
        }
    }

    @Test public void rejectsIncompleteOrEmptyPatches() {
        for (String missing : new String[]{"+\t\tn := int(buff.Len())", "atomic.Bool", "reader.Swap(nil)"}) {
            assertThrows(GradleException.class, () ->
                VerifyLibXrayBuildContractTask.verifyXhttpContract(script, patch.replace(missing, ""), close));
        }
        assertThrows(GradleException.class, () ->
            VerifyLibXrayBuildContractTask.verifyXhttpContract(script, patch, ""));
    }

    @Test public void acceptsCompleteWebSocketContract() {
        VerifyLibXrayBuildContractTask.verifyWebSocketContract(webSocketScript, webSocketPatch);
    }

    @Test public void rejectsMissingWebSocketBuildOrAttestationStep() {
        for (String line : webSocketScript.strip().split("\n")) {
            assertThrows(line, GradleException.class, () ->
                VerifyLibXrayBuildContractTask.verifyWebSocketContract(webSocketScript.replace(line, ""), webSocketPatch));
        }
    }

    @Test public void rejectsIncompleteWebSocketPatch() {
        for (String missing : new String[]{"NetDialContext:", "WebsocketHandshakeContext(dialCtx)",
                "if nBytes > 0", "_ = pconn.Close()"}) {
            assertThrows(missing, GradleException.class, () ->
                VerifyLibXrayBuildContractTask.verifyWebSocketContract(webSocketScript, webSocketPatch.replace(missing, "")));
        }
    }

    @Test public void acceptsCompleteVlessCancellationContract() {
        VerifyLibXrayBuildContractTask.verifyVlessCancelContract(vlessScript, vlessPatch);
    }

    @Test public void rejectsMissingVlessCancellationBuildOrAttestationStep() {
        for (String line : vlessScript.strip().split("\n")) {
            assertThrows(line, GradleException.class, () ->
                VerifyLibXrayBuildContractTask.verifyVlessCancelContract(vlessScript.replace(line, ""), vlessPatch));
        }
    }

    @Test public void rejectsIncompleteVlessCancellationPatch() {
        for (String missing : new String[]{"ExponentialBackoffContext(ctx, 5, 200)", "case <-r.ctx.Done():", "r.ctx.Err()"}) {
            assertThrows(missing, GradleException.class, () ->
                VerifyLibXrayBuildContractTask.verifyVlessCancelContract(vlessScript, vlessPatch.replace(missing, "")));
        }
    }
}
