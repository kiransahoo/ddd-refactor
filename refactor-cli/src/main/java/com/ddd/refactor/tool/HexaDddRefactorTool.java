package com.ddd.refactor.tool;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
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
 *   3) Calls LlmClient with your advanced DDD/Hex prompt on each chunk
 *   4) Aggregates chunk "suggestedFix" if violations are found
 *   5) Writes or merges the final refactored code
 *   6) Uses concurrency, caching, retries
 *   @author kiransahoo
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

        /**
         * The "safe prompt". We read it from a config file, but you can override if needed.
         */
        public String basePrompt;

        public RefactorConfig(Path source, Path output, String apiKey) {
            this.sourceDir = source;
            this.outputDir = output;
            this.openAiApiKey = apiKey;
            this.cacheDir = output.resolve("cache");
        }
    }

    protected final RefactorConfig config;
    private final ExecutorService executor;

    /**
     * Construct the tool with a config that has already loaded values.
     */
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

    /**
     * Overload that loads configuration from an InputStream
     * (for reading from resources).
     */
    public static void loadConfigFromProperties(RefactorConfig cfg, InputStream inStream) {
        Properties props = new Properties();
        try {
            props.load(inStream);
        } catch (IOException e) {
            throw new RuntimeException("Cannot load config from InputStream", e);
        }
        applyProperties(cfg, props);
    }


    public static void loadConfigFromProperties(RefactorConfig cfg, String propertiesFilePath) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(propertiesFilePath))) {
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("Cannot load config file: " + propertiesFilePath, e);
        }
        applyProperties(cfg, props);
    }

    /**
     * Applies parsed properties to the RefactorConfig.
     */
    private static void applyProperties(RefactorConfig cfg, Properties props) {
        cfg.maxParallel = Integer.parseInt(props.getProperty("maxParallel", "4"));
        cfg.maxLinesPerChunk = Integer.parseInt(props.getProperty("maxLinesPerChunk", "300"));
        cfg.maxPromptRetries = Integer.parseInt(props.getProperty("maxPromptRetries", "3"));
        cfg.cacheEnabled = Boolean.parseBoolean(props.getProperty("cacheEnabled", "true"));
        cfg.basePrompt = props.getProperty("basePrompt", getDefaultSafePrompt());
    }

    /**
     * A default "safe prompt" string in case not found in config file.
     */
    private static String getDefaultSafePrompt() {
        return """
You are an expert in Java, Spring Boot, Hexagonal Architecture, and advanced DDD frameworks.

Your job:
1) Read the provided Java CHUNK below.
2) Identify any Hex/DDD violations:
   - JPA Repositories containing domain logic,
   - Domain objects (Aggregates) calling DB or HTTP,
   - Saga logic in domain,
   - concurrency anti-patterns, etc.
3) If you find violations, set "violation": true; otherwise false.
4) "reason": short explanation.
5) "suggestedFix": the entire corrected Java code if violation=true, else "".

**Output**:
Exactly ONE JSON object:
{
  "violation": boolean,
  "reason": "...",
  "suggestedFix": "..."
}

No triple backticks or extraneous text. The "suggestedFix" must be valid Java parseable by JavaParser.
""";
    }

    /**
     * The main runner method that scans for .java files and processes them.
     */
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

    /**
     * Processes a single file by chunkifying and sending to LLM.
     */
    protected void processFile(Path file) {
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
            List<String> lines = Arrays.asList(content.split("\r?\n"));
            List<List<String>> chunks = chunkify(lines, config.maxLinesPerChunk);

            boolean anyViolation = false;
            StringBuilder aggregatedFix = new StringBuilder();
            StringBuilder reasons = new StringBuilder();

            for (int i = 0; i < chunks.size(); i++) {
                String chunkText = String.join("\n", chunks.get(i));
                JSONObject chunkResp = callLlmWithRetry(chunkText);
                if (chunkResp == null) {
                    reasons.append("Chunk ").append(i + 1).append(": LLM returned null.\n");
                    continue;
                }
                boolean violation = chunkResp.optBoolean("violation", false);
                String reason = chunkResp.optString("reason", "");
                String fix = chunkResp.optString("suggestedFix", "");

                if (violation) {
                    anyViolation = true;
                    reasons.append("Chunk ").append(i + 1).append(" => ").append(reason).append("\n");
                    aggregatedFix.append("//--- fix for chunk ").append(i + 1).append(" ---\n");
                    aggregatedFix.append(fix).append("\n");
                } else {
                    reasons.append("Chunk ").append(i + 1).append(" => no violation\n");
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

    /**
     * Called after all chunks are processed to handle final "violation" result.
     * Default implementation simply writes the "suggestedFix" if present.
     * Subclasses can override to do AST merges, etc.
     */
    protected void handleFinalResponse(Path originalFile, JSONObject resp) throws IOException {
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

    /**
     * Calls the LLM with retry attempts if it fails or returns incomplete data.
     */
    private JSONObject callLlmWithRetry(String chunk) {
        for (int attempt = 1; attempt <= config.maxPromptRetries; attempt++) {
            try {
                // Insert chunk after the base prompt
                String prompt = config.basePrompt
                        + "\n\n=== CODE CHUNK ===\n"
                        + chunk;

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

    /**
     * Splits a file's lines into N-size chunks to keep the token usage manageable.
     */
    protected List<List<String>> chunkify(List<String> lines, int chunkSize) {
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i += chunkSize) {
            out.add(lines.subList(i, Math.min(i + chunkSize, lines.size())));
        }
        return out;
    }

    /**
     * Creates a stable hash of the file content so we can cache LLM responses.
     */
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

    /**
     * Tries to load a previously cached JSON response for the file.
     */
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

    /**
     * Saves the final JSON to disk for future reference, skipping re-calls to the LLM.
     */
    private void saveToCache(String fileHash, JSONObject obj) {
        Path p = config.cacheDir.resolve(fileHash + ".json");
        try {
            Files.writeString(p, obj.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("Failed to write cache => " + p + ": " + e.getMessage());
        }
    }

    /**
     * Recursively finds all *.java files under the sourceDir.
     */
    protected List<Path> findJavaFiles(Path start) {
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
