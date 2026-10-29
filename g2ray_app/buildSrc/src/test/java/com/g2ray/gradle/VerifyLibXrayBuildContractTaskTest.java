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
        go test ./transport/internet/splithttp -run '^TestG2RayXHTTP'
        if ($LASTEXITCODE -ne 0) { throw "The patched XHTTP
        """;
    private final String patch = "+\t\tn := int(buff.Len())\natomic.Bool\nreader.Swap(nil)";
    private final String close = "+\t\treturn err2";

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
}
