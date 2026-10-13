package com.g2ray.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.api.GradleException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class VerifyPersistedJsonSchemaTaskTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private String validMapping() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/persisted-schema-valid.txt")) {
            if (input == null) throw new IOException("Missing mapping fixture");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Path writeReport(String content) throws IOException {
        Path file = temporaryFolder.newFile().toPath();
        Files.writeString(file, content);
        return file;
    }

    private String validSeeds() throws IOException {
        StringBuilder seeds = new StringBuilder();
        String className = "";
        for (String line : validMapping().split("\n")) {
            if (line.startsWith("#") || line.isBlank() || line.contains("(")) continue;
            String original = line.substring(0, line.indexOf(" -> ")).strip();
            if (!line.startsWith(" ")) {
                className = original;
                seeds.append(className).append('\n');
            } else {
                seeds.append(className).append(": ").append(original).append('\n');
            }
        }
        return seeds.toString();
    }

    private void assertRejected(String content, String expected) throws IOException {
        assertRejected(content, validSeeds(), "", expected);
    }

    private void assertRejected(String mapping, String seeds, String usage, String expected) throws IOException {
        Path mappingFile = writeReport(mapping);
        Path seedsFile = writeReport(seeds);
        Path usageFile = writeReport(usage);
        GradleException error = assertThrows(
            GradleException.class, () -> VerifyPersistedJsonSchemaTask.verifyArtifacts(mappingFile, seedsFile, usageFile)
        );
        assertTrue(error.getMessage(), error.getMessage().contains(expected));
    }

    @Test public void acceptsPreservedSchemaAndUnrelatedObfuscation() throws IOException {
        String content = validMapping() + "\ncom.g2ray.client.ui.Screen -> a:\n" +
            "    java.lang.String title -> b\n";
        VerifyPersistedJsonSchemaTask.verifyArtifacts(writeReport(content), writeReport(validSeeds()), writeReport(""));
    }

    @Test public void rejectsRenamedModel() throws IOException {
        assertRejected(validMapping().replace(
            "G2RayAccount -> com.g2ray.client.model.G2RayAccount:", "G2RayAccount -> yi0:"
        ), "G2RayAccount was renamed to yi0");
    }

    @Test public void rejectsRenamedPersistedField() throws IOException {
        assertRejected(validMapping().replace("rawUri -> rawUri", "rawUri -> a"),
            "StoredG2RayProfile.rawUri was renamed to a");
    }

    @Test public void rejectsRenamedEnumConstant() throws IOException {
        assertRejected(validMapping().replace("GITHUB_DIRECT -> GITHUB_DIRECT", "GITHUB_DIRECT -> k"),
            "CodespaceWakeMode.GITHUB_DIRECT was renamed to k");
    }

    @Test public void rejectsRemovedField() throws IOException {
        assertRejected(validMapping().replace("    int priority -> priority\n", ""),
            validSeeds().replace("com.g2ray.client.model.G2RayAccount: int priority\n", ""), "",
            "G2RayAccount is missing persisted fields [priority]");
    }

    @Test public void rejectsMissingType() throws IOException {
        assertRejected(validMapping().replace(
            "com.g2ray.client.model.ActiveSelection -> com.g2ray.client.model.ActiveSelection:",
            "com.g2ray.client.ui.Unrelated -> a:"
        ), "Missing persisted type com.g2ray.client.model.ActiveSelection");
    }

    @Test public void rejectsTruncatedOrEmptyMapping() throws IOException {
        assertRejected("# R8 mapping\n", "Missing persisted type");
        assertRejected(validMapping().substring(0, validMapping().indexOf(
            "com.g2ray.client.model.WorkerControlPlane ->"
        )), "Missing persisted type com.g2ray.client.model.WorkerControlPlane");
    }

    @Test public void rejectsDuplicateTypeAndFieldMappings() throws IOException {
        assertRejected(validMapping() + "\ncom.g2ray.client.model.ActiveSelection -> a:\n",
            "Duplicate mapping for com.g2ray.client.model.ActiveSelection");
        assertRejected(validMapping().replace("    int port -> port\n",
            "    int port -> port\n    int port -> p\n"),
            "Duplicate field mapping for StoredG2RayProfile.port");
    }

    @Test public void missingFileFailsRatherThanSkippingVerification() {
        Path missing = temporaryFolder.getRoot().toPath().resolve("missing.txt");
        assertThrows(IOException.class, () -> VerifyPersistedJsonSchemaTask.verifyArtifacts(missing, missing, missing));
    }

    @Test public void acceptsIdentityFieldsOmittedFromMappingButKeptByR8() throws IOException {
        String mapping = validMapping().replaceAll("(?m)^    [^\\n(]+ -> [^\\n]+\\n?", "");
        VerifyPersistedJsonSchemaTask.verifyArtifacts(writeReport(mapping), writeReport(validSeeds()), writeReport(""));
    }

    @Test public void keptSeedCannotMaskRemovedField() throws IOException {
        assertRejected(validMapping(), validSeeds(),
            "com.g2ray.client.model.G2RayAccount:\n    public final int priority\n",
            "G2RayAccount.priority was removed by R8");
    }

    @Test public void keptSeedCannotMaskRemovedType() throws IOException {
        assertRejected(validMapping(), validSeeds(), "com.g2ray.client.model.WorkerControlPlane\n",
            "WorkerControlPlane was removed by R8");
    }

    @Test public void unrelatedRemovalsAndNonPersistentSyntheticMethodsAreAllowed() throws IOException {
        String usage = "com.g2ray.client.ui.Screen:\n    private int priority\n" +
            "com.g2ray.client.model.G2RayAccount:\n    static void <clinit>()\n";
        VerifyPersistedJsonSchemaTask.verifyArtifacts(writeReport(validMapping()), writeReport(validSeeds()), writeReport(usage));
    }

    @Test public void missingRetentionOrRemovalReportFailsClosed() throws IOException {
        Path mapping = writeReport(validMapping());
        Path seeds = writeReport(validSeeds());
        Path usage = writeReport("");
        Path missing = temporaryFolder.getRoot().toPath().resolve("missing.txt");
        assertThrows(IOException.class, () -> VerifyPersistedJsonSchemaTask.verifyArtifacts(mapping, missing, usage));
        assertThrows(IOException.class, () -> VerifyPersistedJsonSchemaTask.verifyArtifacts(mapping, seeds, missing));
    }
}
