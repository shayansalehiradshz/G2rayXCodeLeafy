package com.g2ray.gradle;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Native bundle verification must inspect the current local artifacts")
public abstract class VerifyHevBundleTask extends DefaultTask {
    private static final List<String> ABIS = List.of(
        "armeabi-v7a", "arm64-v8a", "x86", "x86_64"
    );

    @Internal
    public abstract DirectoryProperty getJniLibsDirectory();

    @Internal
    public abstract RegularFileProperty getMetadataFile();

    @Input
    public String getJniLibsPath() {
        return getJniLibsDirectory().getAsFile().get().getAbsolutePath();
    }

    @Input
    public String getMetadataPath() {
        return getMetadataFile().getAsFile().get().getAbsolutePath();
    }

    @TaskAction
    public void verifyBundle() throws Exception {
        Path root = Path.of(getJniLibsPath());
        Path metadataPath = Path.of(getMetadataPath());
        if (!Files.isRegularFile(metadataPath)) {
            throw new GradleException("HEV metadata is missing; rebuild with scripts/build-hev-tun.ps1");
        }

        Properties metadata = new Properties();
        try (InputStream input = Files.newInputStream(metadataPath)) {
            metadata.load(input);
        }
        requireMetadata(metadata, "hev.ref");
        requireMetadata(metadata, "hev.ndk");
        requireMetadata(metadata, "hev.minSdk");
        String jniContract = requireMetadata(metadata, "hev.jni");
        if (!"boolean-runtime-v2".equals(jniContract)) {
            throw new GradleException(
                "HEV metadata does not match the app's start/stop/runtime JNI contract"
            );
        }

        for (String abi : ABIS) {
            Path library = root.resolve(abi).resolve("libhev-socks5-tunnel.so");
            if (!Files.isRegularFile(library)) {
                throw new GradleException("HEV native library is missing for " + abi);
            }
            String expected = requireMetadata(metadata, "hev." + abi + ".sha256");
            if (!sha256(library).equalsIgnoreCase(expected)) {
                throw new GradleException(
                    "HEV native library for " + abi + " does not match its pinned metadata"
                );
            }
        }
    }

    private static String requireMetadata(Properties metadata, String key) {
        String value = metadata.getProperty(key, "").trim();
        if (value.isEmpty()) {
            throw new GradleException("HEV metadata is missing " + key);
        }
        return value;
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }
}
