#!/usr/bin/env bash

# -----------------------------------------------------------------------------
# This script creates a Maven multi-module project:
#
#   my-refactor-demo/
#    ├─ pom.xml
#    ├─ legacy-code/
#    │   ├─ pom.xml
#    │   └─ src/...
#    └─ refactor-cli/
#        ├─ pom.xml
#        └─ src/...
#
# The legacy-code module contains intentionally questionable hex/DDD code.
# The refactor-cli module has our "production-grade" refactor tool + LlmClient.
#
# After running this script, do:
#   cd my-refactor-demo
#   mvn clean install
#   mvn --projects refactor-cli \
#       exec:java \
#       -Dexec.mainClass=com.example.refactor.RefactorMain \
#       -Dexec.args="PATH_TO_LEGACY_SRC OPENAI_KEY OUTPUTDIR"
# -----------------------------------------------------------------------------

set -e

# 1) Create root folder
PROJECT_ROOT="my-refactor-demo"
mkdir -p "$PROJECT_ROOT"
cd "$PROJECT_ROOT"

# 2) Create top-level pom.xml
cat <<EOF > pom.xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="
            http://maven.apache.org/POM/4.0.0
            http://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>my-refactor-demo</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>legacy-code</module>
        <module>refactor-cli</module>
    </modules>

    <name>my-refactor-demo</name>
</project>
EOF

# 3) Create the legacy-code module
mkdir -p legacy-code/src/main/java/com/example/legacy

cat <<EOF > legacy-code/pom.xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="
            http://maven.apache.org/POM/4.0.0
            http://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.example</groupId>
        <artifactId>my-refactor-demo</artifactId>
        <version>1.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>legacy-code</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <!-- Example: if we used spring, we might put it here -->
    </dependencies>

</project>
EOF

# Create example "bad" domain code

cat <<EOF > legacy-code/src/main/java/com/example/legacy/LegacyService.java
package com.example.legacy;

/**
 * A service with questionable domain logic. 
 */
public class LegacyService {

    private final InventoryRepository inventoryRepository;

    public LegacyService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    public void processItem(String itemId, int quantity) {
        // domain logic in the service => not pure application logic
        if (quantity > 100) {
            System.out.println("Big quantity. Possibly domain rule not in aggregator");
        }

        // aggregator with direct DB calls => also questionable
        SomeDomainAggregate agg = inventoryRepository.loadAggregate(itemId);
        agg.setStock(agg.getStock() + quantity);

        inventoryRepository.saveAggregate(agg);
    }
}
EOF

cat <<EOF > legacy-code/src/main/java/com/example/legacy/InventoryRepository.java
package com.example.legacy;

/**
 * JPA repository that also includes domain logic => violation.
 */
public class InventoryRepository {

    public SomeDomainAggregate loadAggregate(String id) {
        // pretend to do JPA or DB
        SomeDomainAggregate agg = new SomeDomainAggregate(id, 50);
        if (agg.getStock() > 1000) {
            // domain logic inside repository
            System.out.println("Huge stock!");
        }
        return agg;
    }

    public void saveAggregate(SomeDomainAggregate agg) {
        // domain logic again
        if (agg.getStock() < 0) {
            throw new RuntimeException("Cannot have negative stock");
        }
        // pretend to do an entityManager.persist(agg)
        System.out.println("Saving aggregator => id=" + agg.getId() + ", stock=" + agg.getStock());
    }
}
EOF

cat <<EOF > legacy-code/src/main/java/com/example/legacy/SomeDomainAggregate.java
package com.example.legacy;

/**
 * A domain aggregator that calls DB code => violation.
 */
public class SomeDomainAggregate {

    private final String id;
    private int stock;

    public SomeDomainAggregate(String id, int stock) {
        this.id = id;
        this.stock = stock;
    }

    public String getId() { return id; }
    public int getStock() { return stock; }
    public void setStock(int stock) { this.stock = stock; }

    public void directDbCall() {
        // aggregator calling DB => not hex
        System.out.println("Pretending to do entityManager.persist(...) inside aggregator => violation");
    }
}
EOF


# 4) Create the refactor-cli module
mkdir -p refactor-cli/src/main/java/com/example/refactor

# pom.xml
cat <<EOF > refactor-cli/pom.xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="
            http://maven.apache.org/POM/4.0.0
            http://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.example</groupId>
        <artifactId>my-refactor-demo</artifactId>
        <version>1.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>refactor-cli</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <!-- For JSON library -->
        <dependency>
            <groupId>org.json</groupId>
            <artifactId>json</artifactId>
            <version>20220320</version>
        </dependency>

        <!-- If you want to do HTTP calls in LlmClient (Java 11's HttpClient is built-in) -->
        <!-- No extra dependency needed for standard java.net.http -->
    </dependencies>

    <build>
        <plugins>
            <!-- For running the CLI -->
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.1.0</version>
            </plugin>
        </plugins>
    </build>
</project>
EOF

# LlmClient.java
cat <<EOF > refactor-cli/src/main/java/com/example/refactor/LlmClient.java
package com.example.refactor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Minimal client that calls OpenAI's ChatCompletions API.
 * Expects the prompt to produce JSON with "violation", "reason", "suggestedFix".
 */
public class LlmClient {

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    public static JSONObject callLlm(String userPrompt, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("No OpenAI API key. Skipping LLM call.");
            return null;
        }
        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", "gpt-4"); // or "gpt-3.5-turbo"
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject()
                .put("role", "user")
                .put("content", userPrompt));
            requestBody.put("messages", messages);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OPENAI_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            String body = response.body();

            if (statusCode < 200 || statusCode >= 300) {
                System.err.println("LLM call failed. status=" + statusCode + ", body=" + body);
                return null;
            }

            JSONObject fullResp = new JSONObject(body);
            JSONArray choices = fullResp.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                System.err.println("No choices returned in LLM response.");
                return null;
            }

            JSONObject firstChoice = choices.getJSONObject(0);
            JSONObject messageObj = firstChoice.optJSONObject("message");
            if (messageObj == null) {
                System.err.println("No 'message' object in LLM response choices[0].");
                return null;
            }
            String content = messageObj.optString("content", "{}");

            // Attempt to parse from the first '{' in the content
            int braceIndex = content.indexOf('{');
            if (braceIndex == -1) {
                return null;
            }
            String maybeJson = content.substring(braceIndex).trim();
            return new JSONObject(maybeJson);

        } catch (Exception e) {
            System.err.println("Error calling LLM => " + e.getMessage());
            return null;
        }
    }
}
EOF

# HexaDddRefactorTool.java
cat <<EOF > refactor-cli/src/main/java/com/example/refactor/HexaDddRefactorTool.java
package com.example.refactor;

import org.json.JSONObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * A robust tool that:
 *   1) Recursively scans .java files in a source directory
 *   2) Splits large files into chunks to avoid token limit issues
 *   3) Calls LlmClient with your DDD/hex prompt on each chunk
 *   4) Aggregates chunk "suggestedFix" if violations are found
 *   5) Writes the final refactored code
 *   6) Uses concurrency, caching, retries
 */
public class HexaDddRefactorTool {

    public static class RefactorConfig {
        public Path sourceDir;
        public Path outputDir;
        public Path cacheDir;
        public String openAiApiKey;

        // concurrency
        public int maxParallel = 4;
        // chunking
        public int maxLinesPerChunk = 300;
        // LLM retries
        public int maxPromptRetries = 3;
        // caching
        public boolean cacheEnabled = true;

        // base prompt
        public String basePrompt = """
            You are an expert in Java, Spring Boot, Hexagonal Architecture, and advanced DDD frameworks.
            Please analyze the following Java code CHUNK for potential Hex/DDD violations:
            1) JPA Repositories in adapter layer only
            2) Domain cannot do direct DB/HTTP
            3) Sagas in application layer, not domain
            4) Aggregates no direct external calls

            Return JSON:
            {
              "violation": boolean,
              "reason": "...",
              "suggestedFix": "..."
            }
            """;

        public RefactorConfig(Path source, Path output, String apiKey) {
            this.sourceDir = source;
            this.outputDir = output;
            this.openAiApiKey = apiKey;
            this.cacheDir = output.resolve("cache");
        }
    }

    private final RefactorConfig config;
    private final ExecutorService executor;

    public HexaDddRefactorTool(RefactorConfig config) {
        this.config = config;
        try {
            Files.createDirectories(config.outputDir);
            Files.createDirectories(config.cacheDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create output/cache dirs", e);
        }
        this.executor = Executors.newFixedThreadPool(config.maxParallel);
    }

    public void runRefactoring() throws InterruptedException {
        List<Path> javaFiles = findJavaFiles(config.sourceDir);
        System.out.println("Discovered " + javaFiles.size() + " .java files in " + config.sourceDir);

        List<Future<?>> futures = new ArrayList<>();
        for (Path javaFile : javaFiles) {
            futures.add(executor.submit(() -> processFile(javaFile)));
        }

        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                System.err.println("Error in processing: " + e.getCause());
            }
        }

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);
        System.out.println("Refactoring is complete.");
    }

    private void processFile(Path file) {
        try {
            String content = Files.readString(file);
            String fileHash = sha256(content);

            // check cache
            if (config.cacheEnabled) {
                JSONObject cached = loadFromCache(fileHash);
                if (cached != null) {
                    handleFinalResponse(file, cached);
                    return;
                }
            }

            // chunkify
            List<String> lines = Arrays.asList(content.split("\\r?\\n"));
            List<List<String>> chunks = chunkify(lines, config.maxLinesPerChunk);

            boolean anyViolation = false;
            StringBuilder aggregatedFix = new StringBuilder();
            StringBuilder reasons = new StringBuilder();

            for (int i = 0; i < chunks.size(); i++) {
                String chunkText = String.join("\\n", chunks.get(i));
                JSONObject chunkResp = callLlmWithRetry(chunkText);
                if (chunkResp == null) {
                    reasons.append("Chunk ").append(i+1).append(": LLM returned null.\\n");
                    continue;
                }
                boolean violation = chunkResp.optBoolean("violation", false);
                String reason = chunkResp.optString("reason", "");
                String fix = chunkResp.optString("suggestedFix", "");

                if (violation) {
                    anyViolation = true;
                    reasons.append("Chunk ").append(i+1).append(" => ").append(reason).append("\\n");
                    aggregatedFix.append("//--- fix for chunk ").append(i+1).append(" ---\\n");
                    aggregatedFix.append(fix).append("\\n");
                } else {
                    reasons.append("Chunk ").append(i+1).append(" => no violation\\n");
                }
            }

            JSONObject finalJson = new JSONObject();
            finalJson.put("violation", anyViolation);
            finalJson.put("reason", reasons.toString().trim());
            finalJson.put("suggestedFix", anyViolation ? aggregatedFix.toString() : "");

            if (config.cacheEnabled) {
                saveToCache(fileHash, finalJson);
            }

            handleFinalResponse(file, finalJson);

        } catch (IOException e) {
            System.err.println("IO error reading file=" + file + " => " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Unexpected error => " + e.getMessage());
        }
    }

    private void handleFinalResponse(Path originalFile, JSONObject resp) throws IOException {
        boolean violation = resp.optBoolean("violation", false);
        if (!violation) {
            System.out.println("[OK] " + originalFile);
            return;
        }
        System.out.println("[VIOLATION] " + originalFile);
        String fix = resp.optString("suggestedFix", "");
        if (fix.isBlank()) {
            System.out.println("But no suggested fix provided, skipping refactor file generation.");
            return;
        }

        // mirror subdirs
        Path relative = config.sourceDir.relativize(originalFile.getParent());
        Path outDir = config.outputDir.resolve(relative);
        Files.createDirectories(outDir);

        String baseName = originalFile.getFileName().toString().replace(".java", "");
        Path outFile = outDir.resolve(baseName + "_Refactored.java");
        Files.writeString(outFile, fix, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("Refactor => " + outFile);
    }

    private JSONObject callLlmWithRetry(String chunk) {
        for (int attempt=1; attempt <= config.maxPromptRetries; attempt++) {
            try {
                String prompt = config.basePrompt + "\\n\\n=== CODE CHUNK ===\\n" + chunk;
                JSONObject resp = LlmClient.callLlm(prompt, config.openAiApiKey);
                if (resp != null && resp.has("violation")) {
                    return resp;
                }
                System.err.println("LLM returned incomplete or null. attempt=" + attempt);
            } catch (Exception e) {
                System.err.println("Error calling LLM. attempt=" + attempt + " => " + e.getMessage());
            }
        }
        return null;
    }

    private List<List<String>> chunkify(List<String> lines, int chunkSize) {
        List<List<String>> out = new ArrayList<>();
        for (int i=0; i<lines.size(); i+=chunkSize) {
            out.add(lines.subList(i, Math.min(i+chunkSize, lines.size())));
        }
        return out;
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] data = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private JSONObject loadFromCache(String fileHash) {
        Path p = config.cacheDir.resolve(fileHash + ".json");
        if (Files.exists(p)) {
            try {
                String raw = Files.readString(p);
                return new JSONObject(raw);
            } catch (IOException ignored) {}
        }
        return null;
    }

    private void saveToCache(String fileHash, JSONObject obj) {
        Path p = config.cacheDir.resolve(fileHash + ".json");
        try {
            Files.writeString(p, obj.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("Failed to write cache => " + p + ": " + e.getMessage());
        }
    }

    private List<Path> findJavaFiles(Path start) {
        try {
            return Files.walk(start)
                    .filter(f -> !Files.isDirectory(f))
                    .filter(f -> f.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
EOF

# RefactorMain.java
cat <<EOF > refactor-cli/src/main/java/com/example/refactor/RefactorMain.java
package com.example.refactor;

import java.nio.file.Path;

/**
 * Simple CLI to run the refactoring.
 *
 * Usage:
 *   mvn --projects refactor-cli exec:java \\
 *     -Dexec.mainClass=com.example.refactor.RefactorMain \\
 *     -Dexec.args="/path/to/legacy-code/src/main/java sk-OPENAIKEY /desired/outputDir"
 */
public class RefactorMain {

    public static void main(String[] args) throws InterruptedException {
        if (args.length < 2) {
            System.err.println("Usage: RefactorMain <sourceDir> <openAiApiKey> [<outputDir>]");
            System.exit(1);
        }

        Path sourceDir = Path.of(args[0]);
        String apiKey = args[1];
        Path outputDir = (args.length >= 3) ? Path.of(args[2]) : Path.of("refactorOutput");

        HexaDddRefactorTool.RefactorConfig cfg =
            new HexaDddRefactorTool.RefactorConfig(sourceDir, outputDir, apiKey);

        // Optionally tune:
        cfg.maxParallel = 4;
        cfg.maxLinesPerChunk = 250;
        cfg.maxPromptRetries = 3;
        cfg.cacheEnabled = true;

        HexaDddRefactorTool tool = new HexaDddRefactorTool(cfg);
        tool.runRefactoring();
    }
}
EOF

# Done
echo "Maven multi-module 'my-refactor-demo' created successfully."
echo
echo "Next steps:"
echo "  cd my-refactor-demo"
echo "  mvn clean install"
echo "  mvn --projects refactor-cli exec:java \\"
echo "      -Dexec.mainClass=com.example.refactor.RefactorMain \\"
echo "      -Dexec.args=\"../legacy-code/src/main/java <YOUR_OPENAI_KEY> /tmp/refactorOutput\""
EOF

echo "Shell script created the my-refactor-demo project. Enjoy!"
