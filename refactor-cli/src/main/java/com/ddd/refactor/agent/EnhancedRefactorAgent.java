package com.ddd.refactor.agent;

import com.ddd.refactor.agent.embedding.EmbeddingService;
import com.ddd.refactor.agent.rag.DocumentChunker;
import com.ddd.refactor.agent.rag.RagContextRetriever;
import com.ddd.refactor.agent.rag.RagFactory;
import com.ddd.refactor.agent.rag.RagService;
import com.ddd.refactor.agent.vectordb.VectorDbService;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * An enhanced version of the AdvancedRefactorAgent that leverages RAG capabilities
 * for improved context retrieval and more accurate refactoring suggestions.
 */
public class EnhancedRefactorAgent extends AdvancedRefactorAgent {

    private static final Logger logger = LoggerFactory.getLogger(EnhancedRefactorAgent.class);

    private final RagService ragService;
    private final boolean useRagForContext;
    private final int maxChunkSizeForRag;
    private final boolean indexCodeForRag;
    private final String domainContextPath;

    /**
     * Constructs an EnhancedRefactorAgent with RAG capabilities.
     *
     * @param openAiApiKey The OpenAI API key
     * @param basePrompt The base prompt to use for refactoring
     * @param legacyFile The file to refactor
     * @param maxRetries The maximum number of retries for failed LLM calls
     * @param chunkSize The size of chunks to process
     * @param parallelism The degree of parallelism for processing chunks
     * @param contextRetriever The domain context retriever to use
     * @param ragService The RAG service to use
     * @param useRagForContext Whether to use RAG for context retrieval
     * @param maxChunkSizeForRag The maximum chunk size for RAG (if different from chunkSize)
     * @param indexCodeForRag Whether to index processed code for future RAG use
     * @param domainContextPath Optional path to domain context files to index
     */
    public EnhancedRefactorAgent(String openAiApiKey,
                                 String basePrompt,
                                 Path legacyFile,
                                 int maxRetries,
                                 int chunkSize,
                                 int parallelism,
                                 IDomainContextRetriever contextRetriever,
                                 RagService ragService,
                                 boolean useRagForContext,
                                 int maxChunkSizeForRag,
                                 boolean indexCodeForRag,
                                 String domainContextPath) {
        super(openAiApiKey, basePrompt, legacyFile, maxRetries, chunkSize, parallelism, contextRetriever);
        this.ragService = ragService;
        this.useRagForContext = useRagForContext;
        this.maxChunkSizeForRag = maxChunkSizeForRag > 0 ? maxChunkSizeForRag : chunkSize;
        this.indexCodeForRag = indexCodeForRag;
        this.domainContextPath = domainContextPath;

        // If specified, index domain context files for RAG
        if (ragService != null && domainContextPath != null && !domainContextPath.isEmpty()) {
            indexDomainContext();
        }
    }

    /**
     * Factory method to create an EnhancedRefactorAgent from properties.
     *
     * @param props The properties containing configuration
     * @param legacyFile The file to refactor
     * @return A configured EnhancedRefactorAgent
     */
    public static EnhancedRefactorAgent fromProperties(Properties props, Path legacyFile) {
        // Basic properties
//        String apiKey = props.getProperty("openai.api.key", "");
//        String basePrompt = props.getProperty("prompt.base", "You are an expert in Java, Spring Boot, Hexagonal Architecture, and advanced DDD frameworks.");
//        int maxRetries = Integer.parseInt(props.getProperty("refactor.max.retries", "3"));
//        int chunkSize = Integer.parseInt(props.getProperty("refactor.chunk.size", "300"));
//        int parallelism = Integer.parseInt(props.getProperty("refactor.parallelism", "4"));

        String apiKey = props.getProperty("openAiApiKey", props.getProperty("openai.api.key", ""));
        String basePrompt = props.getProperty("basePrompt", props.getProperty("prompt.base", "You are an expert in Java, Spring Boot, Hexagonal Architecture, and advanced DDD frameworks."));
        int maxRetries = Integer.parseInt(props.getProperty("maxPromptRetries", props.getProperty("refactor.max.retries", "3")));
        int chunkSize = Integer.parseInt(props.getProperty("maxLinesPerChunk", props.getProperty("refactor.chunk.size", "300")));
        int parallelism = Integer.parseInt(props.getProperty("maxParallel", props.getProperty("refactor.parallelism", "4")));

        // RAG properties
        boolean ragEnabled = Boolean.parseBoolean(props.getProperty("rag.enabled", "false"));
        int maxChunkSizeForRag = Integer.parseInt(props.getProperty("rag.chunk.size", String.valueOf(chunkSize)));
        boolean indexCodeForRag = Boolean.parseBoolean(props.getProperty("rag.index.code", "false"));
        String domainContextPath = props.getProperty("rag.domain.context.path", "");

        // Create RAG service if enabled
        RagService ragService = null;
        boolean useRagForContext = false;

        if (ragEnabled) {
            try {
                ragService = RagFactory.createRagService(props);
                useRagForContext = ragService != null && Boolean.parseBoolean(props.getProperty("rag.use.for.context", "true"));
            } catch (Exception e) {
                logger.error("Error creating RAG service: {}", e.getMessage(), e);
            }
        }

        // Create context retriever
        IDomainContextRetriever contextRetriever;

        if (useRagForContext && ragService != null) {
            contextRetriever = RagFactory.createContextRetriever(ragService);
        } else {
            // Fallback to basic vector DB context retriever
            OpenAiEmbedder embedder = new OpenAiEmbedder();
            VectorDb vectorDb = new InMemoryVectorDb();

            // Add default snippets
            String aggSnippet = props.getProperty("domain.default.snippet", """
                    package com.example.ddd;
                    
                    public abstract class AbstractAggregate<ID> {
                       protected ID id;
                       public ID getId() { return id; }
                    }
                    
                    @interface CommandHandler {}
                    """);

            float[] aggEmb = embedder.embed(aggSnippet);
            ((InMemoryVectorDb)vectorDb).addSnippet(new DomainSnippet("default_agg", aggSnippet, aggEmb));

            contextRetriever = new VectorDbContextRetriever(vectorDb, embedder);
        }

        return new EnhancedRefactorAgent(
                apiKey,
                basePrompt,
                legacyFile,
                maxRetries,
                chunkSize,
                parallelism,
                contextRetriever,
                ragService,
                useRagForContext,
                maxChunkSizeForRag,
                indexCodeForRag,
                domainContextPath
        );
    }

    /**
     * Enhanced version of the master refactoring method that uses RAG capabilities
     * for improved context retrieval and more accurate refactoring suggestions.
     */
    @Override
    public JSONObject runRefactoring() throws IOException, InterruptedException {
        List<String> lines = Files.readAllLines(legacyFile);

        // If using RAG for chunking, use the document chunker
        List<List<String>> chunks;
        if (useRagForContext && ragService != null) {
            String content = String.join("\n", lines);
            List<String> javaChunks = DocumentChunker.chunkJavaCode(content, maxChunkSizeForRag);
            chunks = javaChunks.stream()
                    .map(chunk -> Arrays.asList(chunk.split("\n")))
                    .collect(Collectors.toList());
        } else {
            chunks = chunkify(lines, chunkSize);
        }

        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        List<Future<JSONObject>> futures = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            final int chunkIndex = i;
            final List<String> chunk = chunks.get(i);
            futures.add(executor.submit(() -> processOneChunk(chunkIndex + 1, chunk)));
        }

        JSONArray results = new JSONArray();
        StringBuilder aggregatedFix = new StringBuilder();
        for (Future<JSONObject> f : futures) {
            try {
                JSONObject chunkResp = f.get();
                results.put(chunkResp);
                boolean violation = chunkResp.optBoolean("violation", false);
                if (violation) {
                    aggregatedFix.append("//--- fix for chunk ")
                            .append(chunkResp.optInt("chunkIndex", -1))
                            .append(" ---\n")
                            .append(chunkResp.optString("suggestedFix", ""))
                            .append("\n");
                } else {
                    aggregatedFix.append("//--- chunk ")
                            .append(chunkResp.optInt("chunkIndex", -1))
                            .append(" => no violation\n");
                }
            } catch (ExecutionException e) {
                logger.error("Chunk future error => {}", e.getCause().getMessage(), e.getCause());
            }
        }
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);

        JSONObject finalJson = new JSONObject();
        finalJson.put("file", legacyFile.toString());
        finalJson.put("chunksProcessed", chunks.size());
        finalJson.put("results", results);
        finalJson.put("aggregatedFix", aggregatedFix.toString());

        // Index the processed file if enabled
        if (indexCodeForRag && ragService != null) {
            indexProcessedFile(finalJson);
        }

        return finalJson;
    }

    /**
     * Enhanced version of processOneChunk that uses RAG for context retrieval.
     */
    @Override
    protected JSONObject processOneChunk(int index, List<String> chunkLines) throws IOException, InterruptedException {
        String chunkText = String.join("\n", chunkLines);

        // Retrieve domain context - use RAG if enabled, otherwise use the provided context retriever
        String domainContext;
        if (useRagForContext && ragService != null) {
            // Use RAG to enhance the prompt with relevant context
            String ragQuery = "Java code for DDD refactoring: " + chunkText.substring(0, Math.min(500, chunkText.length()));
            domainContext = ((RagContextRetriever)contextRetriever).retrieveContext(ragQuery, 3);

            // If RAG didn't return anything useful, fall back to the original context retriever
            if (domainContext == null || domainContext.trim().isEmpty()) {
                domainContext = contextRetriever.retrieveContext(chunkText, 2);
            }
        } else {
            // Use the original context retriever
            domainContext = contextRetriever.retrieveContext(chunkText, 2);
        }

        // Build conversation with enhanced context
        List<JSONObject> miniConv = new ArrayList<>();
        miniConv.add(new JSONObject()
                .put("role", "system")
                .put("content", "You are an advanced DDD refactoring agent. Follow instructions strictly."));

        // Enhance the user prompt with RAG if available
        String userContent;
        if (useRagForContext && ragService != null) {
            // Use RAG to enhance the base prompt
            String enhancedPrompt = ragService.enhancePromptWithContext(basePrompt,
                    "DDD refactoring for: " + chunkText.substring(0, Math.min(200, chunkText.length())));

            userContent = enhancedPrompt
                    + "\n\n//=== Domain Code Snippets ===\n" + domainContext
                    + "\n\n//=== Legacy Code Chunk ===\n" + chunkText;
        } else {
            userContent = basePrompt
                    + "\n\n//=== Domain Code Snippets ===\n" + domainContext
                    + "\n\n//=== Legacy Code Chunk ===\n" + chunkText;
        }

        miniConv.add(new JSONObject()
                .put("role", "user")
                .put("content", userContent));

        JSONObject lastValid = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            JSONObject assistantMsg = callOpenAiChat(miniConv);
            if (assistantMsg == null) {
                // error from LLM
                miniConv.add(new JSONObject()
                        .put("role", "user")
                        .put("content", "LLM returned null, please produce valid JSON {\"violation\":..., \"reason\":..., \"suggestedFix\":...}"));
                continue;
            }
            String content = assistantMsg.optString("content", "{}");
            JSONObject parsed = extractJson(content);
            if (parsed == null) {
                miniConv.add(new JSONObject()
                        .put("role", "user")
                        .put("content", "Your response wasn't valid JSON. Return exactly one JSON object."));
                continue;
            }

            parsed.put("chunkIndex", index);
            boolean violation = parsed.optBoolean("violation", false);
            String fix = parsed.optString("suggestedFix", "");
            if (!violation || fix.isEmpty()) {
                lastValid = parsed;
                break;
            }
            // parse-check
            if (tryParseJava(fix)) {
                lastValid = parsed;
                break;
            } else {
                miniConv.add(new JSONObject()
                        .put("role", "user")
                        .put("content", "Your suggestedFix is not valid Java. Please correct syntax and ensure it can parse in JavaParser."));
            }
        }

        if (lastValid == null) {
            // fallback: embed snippet as comment
            JSONObject fallback = new JSONObject();
            fallback.put("violation", true);
            fallback.put("reason", "Max parse attempts reached, fallback comment only.");
            fallback.put("chunkIndex", index);
            // embed snippet
            fallback.put("suggestedFix",
                    "// fallback refactor, snippet unparseable\n/*\n" + chunkText + "\n*/");
            return fallback;
        }
        return lastValid;
    }

    /**
     * Index domain context files for RAG if specified.
     */
    private void indexDomainContext() {
        if (ragService == null || domainContextPath == null || domainContextPath.isEmpty()) {
            return;
        }

        try {
            Path path = Paths.get(domainContextPath);
            if (!Files.exists(path)) {
                logger.warn("Domain context path does not exist: {}", domainContextPath);
                return;
            }

            if (Files.isDirectory(path)) {
                // Index all files in the directory
                Files.walk(path)
                        .filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java") ||
                                p.toString().endsWith(".md") ||
                                p.toString().endsWith(".txt"))
                        .forEach(this::indexFile);
            } else {
                // Index a single file
                indexFile(path);
            }
        } catch (IOException e) {
            logger.error("Error indexing domain context: {}", e.getMessage(), e);
        }
    }

    /**
     * Index a single file for RAG.
     *
     * @param filePath The path to the file to index
     */
    private void indexFile(Path filePath) {
        try {
            String content = Files.readString(filePath);
            String fileName = filePath.getFileName().toString();
            String documentId = "domain_" + UUID.randomUUID().toString();

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("type", "domain_context");
            metadata.put("path", filePath.toString());
            metadata.put("fileType", getFileType(fileName));

            boolean success = ragService.indexDocument(documentId, fileName, content, metadata);
            if (success) {
                logger.info("Successfully indexed domain context file: {}", fileName);
            } else {
                logger.warn("Failed to index domain context file: {}", fileName);
            }
        } catch (IOException e) {
            logger.error("Error reading file {}: {}", filePath, e.getMessage(), e);
        }
    }

    /**
     * Get the file type from a file name.
     *
     * @param fileName The file name
     * @return The file type
     */
    private String getFileType(String fileName) {
        if (fileName.endsWith(".java")) {
            return "java";
        } else if (fileName.endsWith(".md")) {
            return "markdown";
        } else if (fileName.endsWith(".txt")) {
            return "text";
        } else {
            return "unknown";
        }
    }

    /**
     * Index the processed file and its refactoring suggestions for future RAG use.
     *
     * @param finalJson The final JSON containing the refactoring results
     */
    private void indexProcessedFile(JSONObject finalJson) {
        if (ragService == null) {
            return;
        }

        try {
            // Index the original file
            String originalContent = Files.readString(legacyFile);
            String originalFileName = legacyFile.getFileName().toString();
            String originalDocId = "original_" + UUID.randomUUID().toString();

            Map<String, Object> originalMetadata = new HashMap<>();
            originalMetadata.put("type", "original_code");
            originalMetadata.put("path", legacyFile.toString());

            ragService.indexDocument(originalDocId, originalFileName, originalContent, originalMetadata);

            // Index the refactoring suggestions
            String aggregatedFix = finalJson.optString("aggregatedFix", "");
            if (!aggregatedFix.isEmpty()) {
                String fixDocId = "fix_" + UUID.randomUUID().toString();
                String fixTitle = "Refactoring for " + originalFileName;

                Map<String, Object> fixMetadata = new HashMap<>();
                fixMetadata.put("type", "refactoring_suggestion");
                fixMetadata.put("originalFile", originalFileName);
                fixMetadata.put("originalPath", legacyFile.toString());

                ragService.indexDocument(fixDocId, fixTitle, aggregatedFix, fixMetadata);
                logger.info("Indexed refactoring suggestions for: {}", originalFileName);
            }
        } catch (Exception e) {
            logger.error("Error indexing processed file: {}", e.getMessage(), e);
        }
    }
}