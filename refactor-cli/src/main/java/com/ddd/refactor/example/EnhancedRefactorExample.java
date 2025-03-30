package com.ddd.refactor.example;

import com.ddd.refactor.agent.embedding.EmbeddingService;
import com.ddd.refactor.agent.embedding.OpenAiEmbeddingService;
import com.ddd.refactor.agent.IDomainContextRetriever;
import com.ddd.refactor.agent.InMemoryVectorDb;
import com.ddd.refactor.agent.OpenAiEmbedder;
import com.ddd.refactor.agent.rag.DocumentProcessor;
import com.ddd.refactor.agent.rag.RagConfig;
import com.ddd.refactor.agent.rag.RagFactory;
import com.ddd.refactor.agent.rag.RagService;
import com.ddd.refactor.agent.EnhancedRefactorAgent;
import com.ddd.refactor.agent.vectordb.EnhancedInMemoryVectorDb;
import com.ddd.refactor.agent.vectordb.PineconeVectorDbService;
import com.ddd.refactor.agent.vectordb.VectorDbService;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Example of how to use the enhanced refactoring agent with RAG capabilities.
 */
public class EnhancedRefactorExample {

    private static final Logger logger = LoggerFactory.getLogger(EnhancedRefactorExample.class);

    public static void main(String[] args) {
        try {
            // Parse command line arguments
            if (args.length < 2) {
                System.err.println("Usage: EnhancedRefactorExample <config_file> <source_file> [<output_dir>]");
                System.exit(1);
            }

            String configFile = args[0];
            String sourceFile = args[1];
            String outputDir = args.length > 2 ? args[2] : "refactor_output";

            // Load configuration
            Properties config = loadConfig(configFile);

            // Set up services and refactor agent
            setupAndRunRefactoring(config, sourceFile, outputDir);
        } catch (Exception e) {
            logger.error("Error in refactoring process: {}", e.getMessage(), e);
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Load configuration from a properties file.
     *
     * @param configFile Path to the properties file
     * @return Properties object with loaded configuration
     * @throws IOException If an I/O error occurs
     */
    private static Properties loadConfig(String configFile) throws IOException {
        Properties config = new Properties();
        try (InputStream in = new FileInputStream(configFile)) {
            config.load(in);
        }
        logger.info("Loaded configuration from {}", configFile);
        return config;
    }

    /**
     * Set up services and run the refactoring process.
     *
     * @param config Configuration properties
     * @param sourceFile Path to the source file to refactor
     * @param outputDir Path to the output directory for refactored code
     * @throws Exception If an error occurs during the refactoring process
     */
    private static void setupAndRunRefactoring(Properties config, String sourceFile, String outputDir) throws Exception {
        // Create output directory if it doesn't exist
        Path outputPath = Paths.get(outputDir);
        Files.createDirectories(outputPath);

        // Check if RAG is enabled
        boolean ragEnabled = Boolean.parseBoolean(config.getProperty("rag.enabled", "false"));

        // Set up RAG services if enabled
        RagService ragService = null;
        if (ragEnabled) {
            ragService = setupRagServices(config);
        }

        // Create the refactor agent
        EnhancedRefactorAgent agent = createRefactorAgent(config, sourceFile, ragService);

        // Run the refactoring process
        logger.info("Starting refactoring process for {}", sourceFile);
        JSONObject result = agent.runRefactoring();

        // Save the result
        Path resultFile = outputPath.resolve("refactor_result.json");
        Files.writeString(resultFile, result.toString(2));
        logger.info("Refactoring complete. Result saved to {}", resultFile);

        // Print summary
        printRefactoringSummary(result);
    }

    /**
     * Set up RAG services based on configuration.
     *
     * @param config Configuration properties
     * @return A configured RagService
     */
    private static RagService setupRagServices(Properties config) {
        logger.info("Setting up RAG services");

        try {
            // Create RAG configuration
            RagConfig ragConfig = RagConfig.fromProperties(config);

            // Create the vector database service
            VectorDbService vectorDbService;
            String vectorDbProvider = ragConfig.getVectorDbProvider();

            if ("pinecone".equalsIgnoreCase(vectorDbProvider)) {
                vectorDbService = new PineconeVectorDbService(
                        ragConfig.getPineconeApiKey(),
                        ragConfig.getPineconeEnvironment(),
                        ragConfig.getPineconeProjectId(),
                        ragConfig.getPineconeIndexName(),
                        ragConfig.getPineconeNamespace()
                );
                logger.info("Using Pinecone vector database");
            } else {
                // Default to in-memory vector database
                vectorDbService = new EnhancedInMemoryVectorDb();
                logger.info("Using in-memory vector database");
            }

            // Create the embedding service
            EmbeddingService embeddingService;
            String embeddingProvider = ragConfig.getEmbeddingProvider();

            if ("openai".equalsIgnoreCase(embeddingProvider)) {
                embeddingService = new OpenAiEmbeddingService(
                        ragConfig.getOpenAiApiKey(),
                        ragConfig.getOpenAiEmbeddingModel(),
                        ragConfig.getOpenAiEmbeddingUrl(),
                        ragConfig.getEmbeddingBatchSize(),
                        ragConfig.getEmbeddingDimension()
                );
                logger.info("Using OpenAI embedding service with model: {}", ragConfig.getOpenAiEmbeddingModel());
            } else {
                // Default to OpenAI embedder
                embeddingService = new OpenAiEmbeddingService(ragConfig.getOpenAiApiKey());
                logger.info("Using default OpenAI embedding service");
            }

            // Create the RAG service
            RagService ragService = new RagService(
                    vectorDbService,
                    embeddingService,
                    ragConfig.getMaxResults(),
                    ragConfig.getRelevanceThreshold(),
                    ragConfig.isIncludeCitations()
            );

            // Index domain context if configured
            if (ragConfig.isEnabled() && ragConfig.getDomainContextPath() != null && !ragConfig.getDomainContextPath().isEmpty()) {
                indexDomainContext(ragService, ragConfig);
            }

            return ragService;
        } catch (Exception e) {
            logger.error("Error setting up RAG services: {}", e.getMessage(), e);
            logger.info("Continuing without RAG capabilities");
            return null;
        }
    }

    /**
     * Index domain context files for RAG.
     *
     * @param ragService The RAG service
     * @param ragConfig RAG configuration
     */
    private static void indexDomainContext(RagService ragService, RagConfig ragConfig) {
        try {
            String domainContextPath = ragConfig.getDomainContextPath();
            Path path = Paths.get(domainContextPath);

            if (!Files.exists(path)) {
                logger.warn("Domain context path does not exist: {}", domainContextPath);
                return;
            }

            DocumentProcessor processor = new DocumentProcessor(
                    ragService,
                    ragConfig.getChunkSize(),
                    ragConfig.getChunkOverlap()
            );

            if (Files.isDirectory(path)) {
                List<String> extensions = Arrays.asList(".java", ".md", ".txt");
                int processedCount = processor.processDirectory(path, extensions, true);
                logger.info("Indexed {} domain context files from {}", processedCount, domainContextPath);
            } else {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("type", "domain_context");
                boolean success = processor.processFile(path, metadata);
                logger.info("Indexed domain context file {}: {}", path, success ? "successful" : "failed");
            }
        } catch (Exception e) {
            logger.error("Error indexing domain context: {}", e.getMessage(), e);
        }
    }

    /**
     * Create a refactor agent based on configuration.
     *
     * @param config Configuration properties
     * @param sourceFile Path to the source file to refactor
     * @param ragService RAG service (can be null if not enabled)
     * @return A configured EnhancedRefactorAgent
     */
    private static EnhancedRefactorAgent createRefactorAgent(Properties config, String sourceFile, RagService ragService) {
        Path sourcePath = Paths.get(sourceFile);
        String apiKey = config.getProperty("openai.api.key", "");
        String basePrompt = config.getProperty("prompt.base", "You are an expert in Java, Spring Boot, Hexagonal Architecture, and advanced DDD frameworks.");
        int maxRetries = Integer.parseInt(config.getProperty("refactor.max.retries", "3"));
        int chunkSize = Integer.parseInt(config.getProperty("refactor.chunk.size", "300"));
        int parallelism = Integer.parseInt(config.getProperty("refactor.parallelism", "4"));

        boolean useRagForContext = ragService != null &&
                Boolean.parseBoolean(config.getProperty("rag.useForContext", "true"));
        int maxChunkSizeForRag = Integer.parseInt(config.getProperty("rag.chunk.size", String.valueOf(chunkSize)));
        boolean indexCodeForRag = ragService != null &&
                Boolean.parseBoolean(config.getProperty("rag.indexCodeSnippets", "false"));
        String domainContextPath = config.getProperty("rag.domainContextPath", "");

        // Create context retriever
        IDomainContextRetriever contextRetriever;

        if (useRagForContext && ragService != null) {
            contextRetriever = RagFactory.createContextRetriever(ragService);
            logger.info("Using RAG-based context retriever");
        } else {
            // Fallback to basic vector DB context retriever
            OpenAiEmbedder embedder = new OpenAiEmbedder();
            InMemoryVectorDb vectorDb = new InMemoryVectorDb();

            // Add default snippets
            String aggSnippet = config.getProperty("domain.default.snippet", """
                    package com.example.ddd;
                    
                    public abstract class AbstractAggregate<ID> {
                       protected ID id;
                       public ID getId() { return id; }
                    }
                    
                    @interface CommandHandler {}
                    """);

            float[] aggEmb = embedder.embed(aggSnippet);
            vectorDb.addSnippet(new com.ddd.refactor.agent.DomainSnippet("default_agg", aggSnippet, aggEmb));

            contextRetriever = new com.ddd.refactor.agent.VectorDbContextRetriever(vectorDb, embedder);
            logger.info("Using basic vector DB context retriever");
        }

        EnhancedRefactorAgent agent = new EnhancedRefactorAgent(
                apiKey,
                basePrompt,
                sourcePath,
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

        logger.info("Created enhanced refactor agent");
        return agent;
    }

    /**
     * Print a summary of the refactoring results.
     *
     * @param result The refactoring result JSON
     */
    private static void printRefactoringSummary(JSONObject result) {
        String file = result.optString("file", "unknown");
        int chunksProcessed = result.optInt("chunksProcessed", 0);
        org.json.JSONArray results = result.optJSONArray("results");

        int violations = 0;
        if (results != null) {
            for (int i = 0; i < results.length(); i++) {
                JSONObject chunkResult = results.optJSONObject(i);
                if (chunkResult != null && chunkResult.optBoolean("violation", false)) {
                    violations++;
                }
            }
        }

        System.out.println("\n=== Refactoring Summary ===");
        System.out.println("File: " + file);
        System.out.println("Chunks Processed: " + chunksProcessed);
        System.out.println("Violations Found: " + violations);
        System.out.println("Violation Rate: " +
                (chunksProcessed > 0 ? String.format("%.1f%%", (violations * 100.0 / chunksProcessed)) : "0%"));

        if (violations > 0) {
            System.out.println("\nSuggested fixes have been generated. Check the output directory for the refactored code.");
        } else {
            System.out.println("\nNo DDD violations were found in the code!");
        }
    }
}