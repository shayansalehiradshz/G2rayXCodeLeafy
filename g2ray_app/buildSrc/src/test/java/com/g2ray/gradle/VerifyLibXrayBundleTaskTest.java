package com.g2ray.gradle;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.ToolProvider;
import org.gradle.api.GradleException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class VerifyLibXrayBundleTaskTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();
    private Path aar, metadata, controller, observer, measure, xhttp, close, webSocket, vlessCancel, xhttpLifecycle;
    private Properties properties;

    @Before public void prepare() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        aar = root.resolve("libXray.aar");
        metadata = root.resolve("libXray.properties");
        controller = Files.writeString(root.resolve("controller.patch"), "controller fixture\n");
        observer = Files.writeString(root.resolve("observer.patch"), "observer fixture\n");
        measure = Files.writeString(root.resolve("measure.patch"), "measure fixture\n");
        xhttp = Files.writeString(root.resolve("xhttp.patch"), "XHTTP fixture\n");
        close = Files.writeString(root.resolve("close.patch"), "close fixture\n");
        webSocket = Files.writeString(root.resolve("websocket.patch"), "WebSocket fixture\n");
        vlessCancel = Files.writeString(root.resolve("vless-cancel.patch"), "VLESS cancellation fixture\n");
        xhttpLifecycle = Files.writeString(root.resolve("xhttp-lifecycle.patch"), "XHTTP lifecycle fixture\n");
        writeAar(true);
        properties = new Properties();
        properties.setProperty("libxray.ref", "80263da83e96b2972455b0a94b13ee1a10e51391");
        properties.setProperty("libxray.xray.ref", "5ca6f4b7d4dc20a881d4330e498892697627ec0c");
        properties.setProperty("libxray.go", "go1.26.10");
        properties.setProperty("libxray.gomobile", "v0.0.0-20260611195102-4dd8f1dbf5d2");
        properties.setProperty("libxray.gobind", properties.getProperty("libxray.gomobile"));
        for (var entry : Map.of("libxray.sha256", aar,
                "libxray.android.controller.patch.sha256", controller,
                "libxray.xray.observer.patch.sha256", observer,
                "libxray.measure.patch.sha256", measure,
                "libxray.xray.xhttp.patch.sha256", xhttp,
                "libxray.xray.xhttp.close.patch.sha256", close,
                "libxray.xray.websocket.patch.sha256", webSocket,
                "libxray.xray.vless.cancel.patch.sha256", vlessCancel,
                "libxray.xray.xhttp.lifecycle.patch.sha256", xhttpLifecycle).entrySet()) {
            properties.setProperty(entry.getKey(), hash(entry.getValue()));
        }
        saveMetadata();
    }

    private static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private void saveMetadata() throws Exception {
        try (OutputStream output = Files.newOutputStream(metadata)) { properties.store(output, "synthetic test metadata"); }
    }

    private void verify(boolean required) throws Exception {
        VerifyLibXrayBundleTask.verifyArtifacts(aar, metadata, controller, observer, measure, xhttp, close, webSocket, vlessCancel, xhttpLifecycle, required);
    }

    private void rejected(String expected) {
        GradleException error = assertThrows(GradleException.class, () -> verify(true));
        assertTrue(error.getMessage(), error.getMessage().contains(expected));
    }

    @Test public void rejectsMissingXhttpLifecycleAttestation() throws Exception {
        properties.remove("libxray.xray.xhttp.lifecycle.patch.sha256"); saveMetadata();
        rejected("libxray.xray.xhttp.lifecycle.patch.sha256");
    }

    @Test public void rejectsChangedXhttpLifecyclePatch() throws Exception {
        Files.writeString(xhttpLifecycle, "changed lifecycle patch");
        rejected("libxray.xray.xhttp.lifecycle.patch.sha256");
    }

    // Structural metadata/API fixture only: these native entries are not a functional Android library.
    private void writeAar(boolean includeNative) throws Exception {
        Path root = temporaryFolder.newFolder().toPath();
        Path source = Files.writeString(root.resolve("LibXray.java"), """
            package libXray;
            public final class LibXray {
                public static String invoke(String request) { return request; }
                public static void registerDialerController(Object callback) {}
                public static void registerListenerController(Object callback) {}
            }
            """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-d", root.toString(), source.toString()));
        Path classes = root.resolve("classes.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(classes))) {
            jar.putNextEntry(new JarEntry("libXray/LibXray.class"));
            jar.write(Files.readAllBytes(root.resolve("libXray/LibXray.class")));
            jar.closeEntry();
        }
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(aar))) {
            zip.putNextEntry(new ZipEntry("classes.jar"));
            zip.write(Files.readAllBytes(classes));
            zip.closeEntry();
            if (includeNative) for (String abi : new String[]{"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}) {
                zip.putNextEntry(new ZipEntry("jni/" + abi + "/libgojni.so"));
                zip.write(new byte[]{0x7f, 0x45, 0x4c, 0x46});
                zip.closeEntry();
            }
        }
    }

    @Test public void acceptsMatchingPatchMetadataAndApi() throws Exception { verify(true); }

    @Test public void rejectsOldBundleWithoutXhttpAttestation() throws Exception {
        properties.remove("libxray.xray.xhttp.patch.sha256"); saveMetadata();
        rejected("libxray.xray.xhttp.patch.sha256");
    }

    @Test public void rejectsMissingCloseAttestation() throws Exception {
        properties.remove("libxray.xray.xhttp.close.patch.sha256"); saveMetadata();
        rejected("libxray.xray.xhttp.close.patch.sha256");
    }

    @Test public void rejectsChangedXhttpPatchAndWrongMetadataHash() throws Exception {
        Files.writeString(xhttp, "changed patch"); rejected("libxray.xray.xhttp.patch.sha256");
        properties.setProperty("libxray.xray.xhttp.patch.sha256", "wrong"); saveMetadata();
        rejected("libxray.xray.xhttp.patch.sha256");
    }

    @Test public void rejectsChangedClosePatch() throws Exception {
        Files.writeString(close, "changed close patch"); rejected("libxray.xray.xhttp.close.patch.sha256");
    }

    @Test public void rejectsMissingWebSocketAttestation() throws Exception {
        properties.remove("libxray.xray.websocket.patch.sha256"); saveMetadata();
        rejected("libxray.xray.websocket.patch.sha256");
    }

    @Test public void rejectsMissingVlessCancellationAttestation() throws Exception {
        properties.remove("libxray.xray.vless.cancel.patch.sha256"); saveMetadata();
        rejected("libxray.xray.vless.cancel.patch.sha256");
    }

    @Test public void rejectsChangedVlessCancellationPatchAndWrongMetadataHash() throws Exception {
        Files.writeString(vlessCancel, "changed patch"); rejected("libxray.xray.vless.cancel.patch.sha256");
        properties.setProperty("libxray.xray.vless.cancel.patch.sha256", "wrong"); saveMetadata();
        rejected("libxray.xray.vless.cancel.patch.sha256");
    }

    @Test public void rejectsChangedWebSocketPatchAndWrongMetadataHash() throws Exception {
        Files.writeString(webSocket, "changed patch"); rejected("libxray.xray.websocket.patch.sha256");
        properties.setProperty("libxray.xray.websocket.patch.sha256", "wrong"); saveMetadata();
        rejected("libxray.xray.websocket.patch.sha256");
    }

    @Test public void rejectsMismatchedAarEvenWithCurrentPatchHashes() throws Exception {
        Files.write(aar, new byte[]{1,2,3}); rejected("pinned SHA-256");
    }

    @Test public void rejectsUnapprovedCoreAndToolchain() throws Exception {
        properties.setProperty("libxray.go", "go1.0"); saveMetadata(); rejected("libxray.go");
        properties.setProperty("libxray.go", "go1.26.10");
        properties.setProperty("libxray.xray.ref", "invalid"); saveMetadata(); rejected("libxray.xray.ref");
    }

    @Test public void matchingHashesCannotReplaceMissingNativeLibraries() throws Exception {
        writeAar(false); properties.setProperty("libxray.sha256", hash(aar)); saveMetadata();
        rejected("missing a required native library");
    }

    @Test public void permitsMissingOptionalAarButNotRequiredAar() throws Exception {
        Files.delete(aar); verify(false); rejected("requires app/libs/libXray.aar");
    }
}
