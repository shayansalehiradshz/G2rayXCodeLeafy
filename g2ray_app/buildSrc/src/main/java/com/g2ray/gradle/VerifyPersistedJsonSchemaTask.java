package com.g2ray.gradle;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Release persistence verification must inspect the current R8 mapping")
public abstract class VerifyPersistedJsonSchemaTask extends DefaultTask {
    private static final Pattern CLASS_MAPPING = Pattern.compile("^(\\S+) -> (\\S+):$");
    private static final Pattern FIELD_MAPPING = Pattern.compile("^\\s+\\S+ (\\S+) -> (\\S+)$");
    private static final String MODEL_PACKAGE = "com.g2ray.client.model.";
    private static final Pattern KEPT_FIELD = Pattern.compile(
        "^" + Pattern.quote(MODEL_PACKAGE) + "([^ :]+):\\s+\\S+ (\\S+)$"
    );
    private static final Map<String, Set<String>> SCHEMAS = Map.of(
        "G2RayAccount", Set.of(
            "id", "displayName", "workerBaseUrl", "fallbackWorkerBaseUrl", "codespaceName",
            "workerCodespaceId", "wakeMode", "priority", "enabled", "lastSelectedProfileId",
            "lastHealthAt", "lastRouteReady", "quotaResetEstimateUtc", "retentionExpiresAt",
            "lastWorkerMessage", "isDefault", "createdAt", "updatedAt"
        ),
        "G2RayApiKey", Set.of(
            "id", "accountId", "label", "kind", "encryptedValue", "enabled", "priority",
            "createdAt", "updatedAt", "lastUsedAt"
        ),
        "StoredG2RayProfile", Set.of(
            "id", "accountId", "displayName", "rawUri", "transport", "address", "port", "sni",
            "hostHeader", "path", "isDefault", "createdAt", "updatedAt", "lastDelayMs",
            "lastDelayTestedAt", "lastDelayP95Ms", "lastDelayJitterMs", "lastDelaySuccessfulSamples",
            "lastDelaySampleCount", "delayFailureCount", "lastDelayError", "lastDownloadBps",
            "lastUploadBps", "lastThroughputTestedAt", "lastThroughputError", "lastDelayNetworkKey",
            "lastThroughputNetworkKey"
        ),
        "ActiveSelection", Set.of("accountId", "profileId", "apiKeyId"),
        "WorkerControlPlane", Set.of(
            "workerBaseUrl", "fallbackWorkerBaseUrl", "encryptedWakeSecret", "updatedAt"
        ),
        "CodespaceWakeMode", Set.of("AUTO", "WORKER", "GITHUB_DIRECT"),
        "ApiKeyKind", Set.of("WORKER_WAKE_SECRET", "GITHUB_TOKEN"),
        "Transport", Set.of("XHTTP", "WS")
    );

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getMappingFile();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getSeedsFile();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getUsageFile();

    @TaskAction
    public void verifySchema() throws IOException {
        verifyArtifacts(
            getMappingFile().get().getAsFile().toPath(),
            getSeedsFile().get().getAsFile().toPath(),
            getUsageFile().get().getAsFile().toPath()
        );
        getLogger().lifecycle("Verified R8 names, kept fields and removals for {} persisted JSON types", SCHEMAS.size());
    }

    public static void verifyArtifacts(Path mappingFile, Path seedsFile, Path usageFile) throws IOException {
        Map<String, Map<String, String>> fields = new HashMap<>();
        Set<String> seenClasses = new HashSet<>();
        List<String> problems = new ArrayList<>();
        try (BufferedReader input = Files.newBufferedReader(mappingFile)) {
            String currentSchema = null;
            String line;
            while ((line = input.readLine()) != null) {
                if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
                Matcher classMapping = CLASS_MAPPING.matcher(line);
                if (classMapping.matches()) {
                    String original = classMapping.group(1);
                    String simpleName = original.startsWith(MODEL_PACKAGE)
                        ? original.substring(MODEL_PACKAGE.length()) : "";
                    currentSchema = SCHEMAS.containsKey(simpleName) ? simpleName : null;
                    if (currentSchema != null) {
                        if (!seenClasses.add(currentSchema)) {
                            problems.add("Duplicate mapping for " + original);
                        }
                        if (!original.equals(classMapping.group(2))) {
                            problems.add(original + " was renamed to " + classMapping.group(2));
                        }
                        fields.putIfAbsent(currentSchema, new HashMap<>());
                    }
                    continue;
                }
                if (currentSchema == null || line.indexOf('(') >= 0) continue;
                Matcher fieldMapping = FIELD_MAPPING.matcher(line);
                if (fieldMapping.matches()) {
                    String original = fieldMapping.group(1);
                    String renamed = fieldMapping.group(2);
                    String previous = fields.get(currentSchema).putIfAbsent(original, renamed);
                    if (previous != null) {
                        problems.add("Duplicate field mapping for " + currentSchema + "." + original);
                    }
                    if (!original.equals(renamed)) {
                        problems.add(currentSchema + "." + original + " was renamed to " + renamed);
                    }
                }
            }
        }
        // R8 omits unchanged fields from mapping.txt. seeds.txt identifies the
        // kept originals; usage.txt must also rule out an optimized-away field.
        try (BufferedReader input = Files.newBufferedReader(seedsFile)) {
            String line;
            while ((line = input.readLine()) != null) {
                if (line.indexOf('(') >= 0) continue;
                Matcher kept = KEPT_FIELD.matcher(line);
                if (kept.matches() && SCHEMAS.containsKey(kept.group(1))) {
                    fields.computeIfAbsent(kept.group(1), ignored -> new HashMap<>())
                        .putIfAbsent(kept.group(2), kept.group(2));
                }
            }
        }
        try (BufferedReader input = Files.newBufferedReader(usageFile)) {
            String currentSchema = null;
            String line;
            while ((line = input.readLine()) != null) {
                if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
                if (!Character.isWhitespace(line.charAt(0))) {
                    String original = line.endsWith(":") ? line.substring(0, line.length() - 1) : line;
                    String simpleName = original.startsWith(MODEL_PACKAGE)
                        ? original.substring(MODEL_PACKAGE.length()) : "";
                    currentSchema = SCHEMAS.containsKey(simpleName) ? simpleName : null;
                    if (currentSchema != null && !line.endsWith(":")) {
                        problems.add(currentSchema + " was removed by R8");
                    }
                    continue;
                }
                if (currentSchema == null || line.indexOf('(') >= 0) continue;
                String member = line.strip();
                String field = member.substring(member.lastIndexOf(' ') + 1);
                if (SCHEMAS.get(currentSchema).contains(field)) {
                    problems.add(currentSchema + "." + field + " was removed by R8");
                }
            }
        }
        for (Map.Entry<String, Set<String>> schema : new TreeMap<>(SCHEMAS).entrySet()) {
            if (!seenClasses.contains(schema.getKey())) {
                problems.add("Missing persisted type " + MODEL_PACKAGE + schema.getKey());
                continue;
            }
            Set<String> missingFields = new TreeSet<>(schema.getValue());
            missingFields.removeAll(fields.get(schema.getKey()).keySet());
            if (!missingFields.isEmpty()) {
                problems.add(schema.getKey() + " is missing persisted fields " + missingFields);
            }
        }
        if (!problems.isEmpty()) {
            throw new GradleException(
                "R8 changed the reflection-based persisted JSON schema. " +
                    "Restore the model/enum keep rules before releasing:\n" + String.join("\n", problems)
            );
        }
    }
}
