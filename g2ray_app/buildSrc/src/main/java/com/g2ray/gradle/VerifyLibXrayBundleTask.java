package com.g2ray.gradle;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Native bundle verification must inspect the current local artifact")
public abstract class VerifyLibXrayBundleTask extends DefaultTask {
    private static final String APPROVED_LIBXRAY_REF =
        "80263da83e96b2972455b0a94b13ee1a10e51391";
    private static final String APPROVED_GO_VERSION = "go1.26.10";
    private static final String APPROVED_GOMOBILE_VERSION =
        "v0.0.0-20260611195102-4dd8f1dbf5d2";
    private static final String APPROVED_XRAY_REF =
        "5ca6f4b7d4dc20a881d4330e498892697627ec0c";
    private static final List<String> REQUIRED_NATIVE_LIBS = List.of(
        "jni/armeabi-v7a/libgojni.so",
        "jni/arm64-v8a/libgojni.so",
        "jni/x86/libgojni.so",
        "jni/x86_64/libgojni.so"
    );

    @Internal
    public abstract RegularFileProperty getAarFile();

    @Internal
    public abstract RegularFileProperty getMetadataFile();

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

    @Input
    public abstract Property<Boolean> getRequired();

    @Input
    public String getAarPath() {
        return getAarFile().getAsFile().get().getAbsolutePath();
    }

    @Input
    public String getMetadataPath() {
        return getMetadataFile().getAsFile().get().getAbsolutePath();
    }

    @TaskAction
    public void verifyBundle() throws Exception {
        verifyArtifacts(Path.of(getAarPath()), Path.of(getMetadataPath()),
            getControllerPatch().getAsFile().get().toPath(),
            getObserverPatch().getAsFile().get().toPath(),
            getMeasurePatch().getAsFile().get().toPath(),
            getXhttpPatch().getAsFile().get().toPath(),
            getXhttpClosePatch().getAsFile().get().toPath(), getRequired().get());
    }

    static void verifyArtifacts(Path aar, Path metadata, Path controllerPatch, Path observerPatch,
                                Path measurePatch, Path xhttpPatch, Path xhttpClosePatch,
                                boolean required) throws Exception {
        boolean aarExists = Files.isRegularFile(aar);
        boolean metadataExists = Files.isRegularFile(metadata);

        if (!aarExists) {
            if (required) {
                throw new GradleException("This build requires app/libs/libXray.aar and its metadata");
            }
            return;
        }
        if (!metadataExists) {
            throw new GradleException(
                "app/libs/libXray.aar requires app/libs/libXray.properties"
            );
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(metadata)) {
            properties.load(input);
        }
        String expectedHash = properties.getProperty("libxray.sha256", "").trim();
        if (expectedHash.isEmpty()) {
            throw new GradleException("libXray metadata is missing libxray.sha256");
        }

        String actualHash = sha256(aar);
        if (!actualHash.equalsIgnoreCase(expectedHash)) {
            throw new GradleException(
                "app/libs/libXray.aar does not match the pinned SHA-256; rebuild it with scripts/build-libxray-aar.ps1"
            );
        }

        String expectedPatchHash = properties
            .getProperty("libxray.android.controller.patch.sha256", "")
            .trim();
        String actualPatchHash = sha256(controllerPatch);
        if (!actualPatchHash.equalsIgnoreCase(expectedPatchHash)) {
            throw new GradleException(
                "The bundled libXray AAR was not built with the current Android controller patch"
            );
        }

        requireProperty(properties, "libxray.ref", APPROVED_LIBXRAY_REF);
        requireProperty(properties, "libxray.go", APPROVED_GO_VERSION);
        requireProperty(properties, "libxray.gomobile", APPROVED_GOMOBILE_VERSION);
        requireProperty(properties, "libxray.gobind", APPROVED_GOMOBILE_VERSION);
        requireProperty(properties, "libxray.xray.ref", APPROVED_XRAY_REF);
        String expectedObserverPatchHash = properties
            .getProperty("libxray.xray.observer.patch.sha256", "")
            .trim();
        String actualObserverPatchHash = sha256(observerPatch);
        if (!actualObserverPatchHash.equalsIgnoreCase(expectedObserverPatchHash)) {
            throw new GradleException(
                "The bundled libXray AAR was not built with the current Xray observer patch"
            );
        }
        String expectedMeasurePatchHash = properties
            .getProperty("libxray.measure.patch.sha256", "")
            .trim();
        String actualMeasurePatchHash = sha256(measurePatch);
        if (!actualMeasurePatchHash.equalsIgnoreCase(expectedMeasurePatchHash)) {
            throw new GradleException(
                "The bundled libXray AAR was not built with the current measurement-status patch"
            );
        }
        requirePatchHash(properties, "libxray.xray.xhttp.patch.sha256", xhttpPatch);
        requirePatchHash(properties, "libxray.xray.xhttp.close.patch.sha256", xhttpClosePatch);

        verifyNativeLibraries(aar);
        verifyJavaApi(aar);
    }

    private static void requirePatchHash(Properties properties, String name, Path patch) throws Exception {
        if (!sha256(patch).equalsIgnoreCase(properties.getProperty(name, "").trim())) {
            throw new GradleException("The bundled libXray AAR does not attest the current " + name);
        }
    }

    private static void requireProperty(Properties properties, String name, String expected) {
        String actual = properties.getProperty(name, "").trim();
        if (!expected.equals(actual)) {
            throw new GradleException(
                "libXray metadata " + name + " must be the approved value " + expected
            );
        }
    }

    private static void verifyNativeLibraries(Path aar) throws Exception {
        try (ZipFile zip = new ZipFile(aar.toFile())) {
            for (String path : REQUIRED_NATIVE_LIBS) {
                ZipEntry entry = zip.getEntry(path);
                if (entry == null || entry.isDirectory() || entry.getSize() <= 0) {
                    throw new GradleException(
                        "The bundled libXray AAR is missing a required native library: " + path
                    );
                }
            }
        }
    }

    private static void verifyJavaApi(Path aar) throws Exception {
        Path classesJar = Files.createTempFile("g2ray-libxray-classes-", ".jar");
        try {
            try (ZipFile zip = new ZipFile(aar.toFile())) {
                ZipEntry entry = zip.getEntry("classes.jar");
                if (entry == null) {
                    throw new GradleException("The bundled libXray AAR does not contain classes.jar");
                }
                try (InputStream input = zip.getInputStream(entry)) {
                    Files.copy(input, classesJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }

            try (URLClassLoader loader = new URLClassLoader(
                new URL[] { classesJar.toUri().toURL() },
                ClassLoader.getPlatformClassLoader()
            )) {
                Class<?> libXray = Class.forName("libXray.LibXray", false, loader);
                boolean controllers = hasMethod(libXray, "registerDialerController", 1) &&
                    hasMethod(libXray, "registerListenerController", 1);
                boolean modern = hasStringMethod(libXray, "invoke", 1);
                boolean legacy = hasNumericMethod(libXray, "setTunFd") &&
                    hasStringMethod(libXray, "newXrayRunFromJSONRequest", 2) &&
                    hasStringMethod(libXray, "runXrayFromJSON", 1) &&
                    hasStringMethod(libXray, "stopXray", 0) &&
                    hasMethod(libXray, "getXrayState", 0) &&
                    hasStringMethod(libXray, "ping", 1);
                if (!controllers || (!modern && !legacy)) {
                    throw new GradleException(
                        "The bundled libXray Java API is incompatible with the app bridge; " +
                        "expected invoke(String) or the complete legacy lifecycle API"
                    );
                }
            }
        } finally {
            Files.deleteIfExists(classesJar);
        }
    }

    private static boolean hasMethod(Class<?> type, String name, int parameterCount) {
        return Arrays.stream(type.getMethods()).anyMatch(method ->
            method.getName().equalsIgnoreCase(name) &&
                method.getParameterCount() == parameterCount
        );
    }

    private static boolean hasStringMethod(Class<?> type, String name, int parameterCount) {
        return Arrays.stream(type.getMethods()).anyMatch(method ->
            method.getName().equalsIgnoreCase(name) &&
                method.getParameterCount() == parameterCount &&
                method.getReturnType().equals(String.class)
        );
    }

    private static boolean hasNumericMethod(Class<?> type, String name) {
        return Arrays.stream(type.getMethods()).anyMatch(method -> {
            if (!method.getName().equalsIgnoreCase(name) || method.getParameterCount() != 1) {
                return false;
            }
            Class<?> parameter = method.getParameterTypes()[0];
            return parameter.equals(int.class) || parameter.equals(Integer.class) ||
                parameter.equals(long.class) || parameter.equals(Long.class);
        });
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
