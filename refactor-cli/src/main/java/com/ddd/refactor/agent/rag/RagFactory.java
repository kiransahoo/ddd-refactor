package com.ddd.refactor.agent.rag;

import com.ddd.refactor.agent.IDomainContextRetriever;
import com.ddd.refactor.agent.embedding.EmbeddingService;
import com.ddd.refactor.agent.embedding.OpenAiEmbeddingService;
import com.ddd.refactor.agent.vectordb.EnhancedInMemoryVectorDb;
import com.ddd.refactor.agent.vectordb.PineconeVectorDbService;
import com.ddd.refactor.agent.vectordb.VectorDbService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Factory class for creating and configuring RAG components.
 */
public class RagFactory {

    private static final Logger logger = LoggerFactory.getLogger(RagFactory.class);

    /**
     * Create a RAG service using configuration from the provided properties.
     *
     * @param props The properties containing RAG configuration
     * @return A configured RagService or null if configuration is invalid
     */
    public static RagService createRagService(Properties props) {
        boolean ragEnabled = Boolean.parseBoolean(props.getProperty("rag.enabled", "false"));
        if (!ragEnabled) {
            logger.info("RAG is disabled in configuration");
            return null;
        }

        try {
            // Create embedding service
            EmbeddingService embeddingService = createEmbeddingService(props);
            if (embeddingService == null) {
                logger.error("Failed to create embedding service");
                return null;
            }

            // Create vector database service
            VectorDbService vectorDbService = createVectorDbService(props);
            if (vectorDbService == null) {
                logger.error("Failed to create vector database service");
                return null;
            }

            // Create RAG service
            int maxResults = Integer.parseInt(props.getProperty("rag.maxResults", "5"));
            double relevanceThreshold = Double.parseDouble(props.getProperty("rag.relevanceThreshold", "0.7"));
            boolean includeCitations = Boolean.parseBoolean(props.getProperty("rag.includeCitations", "true"));

            RagService ragService = new RagService(vectorDbService, embeddingService, maxResults, relevanceThreshold, includeCitations);

            // Seed the vector database with initial documents if specified
            String seedDocsPath = props.getProperty("rag.seedDocuments.path", "");
            if (!seedDocsPath.isEmpty()) {
                try {
                    seedVectorDatabase(ragService, seedDocsPath);
                } catch (Exception e) {
                    logger.error("Error seeding vector database: {}", e.getMessage(), e);
                    // Continue even if seeding fails
                }
            }

            return ragService;
        } catch (Exception e) {
            logger.error("Error creating RAG service: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Create an embedding service using configuration from the provided properties.
     *
     * @param props The properties containing embedding service configuration
     * @return A configured EmbeddingService or null if configuration is invalid
     */
    public static EmbeddingService createEmbeddingService(Properties props) {
        String embeddingProvider = props.getProperty("embedding.provider", "openai");

        if ("openai".equalsIgnoreCase(embeddingProvider)) {
            String apiKey = props.getProperty("embedding.openai.apiKey", props.getProperty("llm.api.key", ""));
            if (apiKey.isEmpty()) {
                logger.error("No API key provided for OpenAI embedding service");
                return null;
            }

            String model = props.getProperty("embedding.openai.model", "text-embedding-ada-002");
            String apiUrl = props.getProperty("embedding.openai.apiUrl", "https://api.openai.com/v1/embeddings");
            int batchSize = Integer.parseInt(props.getProperty("embedding.openai.batchSize", "20"));
            int dimension = Integer.parseInt(props.getProperty("embedding.openai.dimension", "1536"));

            return new OpenAiEmbeddingService(apiKey, model, apiUrl, batchSize, dimension);
        } else {
            logger.error("Unsupported embedding provider: {}", embeddingProvider);
            return null;
        }
    }

    /**
     * Create a vector database service using configuration from the provided properties.
     *
     * @param props The properties containing vector database configuration
     * @return A configured VectorDbService or null if configuration is invalid
     */
    public static VectorDbService createVectorDbService(Properties props) {
        String vectorDbProvider = props.getProperty("vectordb.provider", "inmemory");

        if ("inmemory".equalsIgnoreCase(vectorDbProvider)) {
            return new EnhancedInMemoryVectorDb();
        } else if ("pinecone".equalsIgnoreCase(vectorDbProvider)) {
            String apiKey = props.getProperty("vectordb.pinecone.apiKey", "");
            if (apiKey.isEmpty()) {
                logger.error("No API key provided for Pinecone vector database");
                return null;
            }

            String environment = props.getProperty("vectordb.pinecone.environment", "");
            String projectId = props.getProperty("vectordb.pinecone.projectId", "");
            String indexName = props.getProperty("vectordb.pinecone.indexName", "");
            String namespace = props.getProperty("vectordb.pinecone.namespace", "");

            return new PineconeVectorDbService(apiKey, environment, projectId, indexName, namespace);
        } else {
            logger.error("Unsupported vector database provider: {}", vectorDbProvider);
            return null;
        }
    }

    /**
     * Create a domain context retriever using the specified RAG service.
     *
     * @param ragService The RAG service to use
     * @return A configured IDomainContextRetriever
     */
    public static IDomainContextRetriever createContextRetriever(RagService ragService) {
        if (ragService == null) {
            logger.warn("No RAG service provided for context retriever, returning null");
            return null;
        }

        try {
            // Get the embedding service from the RAG service
            EmbeddingService embeddingService = (EmbeddingService) ragService
                    .getClass()
                    .getDeclaredField("embeddingService")
                    .get(ragService);

            return new RagContextRetriever(ragService, embeddingService);
        } catch (Exception e) {
            logger.error("Error creating context retriever: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Seed the vector database with initial documents from the specified path.
     *
     * @param ragService The RAG service to use
     * @param seedDocsPath The path to the seed documents
     * @throws IOException If an I/O error occurs
     */
    private static void seedVectorDatabase(RagService ragService, String seedDocsPath) throws IOException {
        Path docsDir = Paths.get(seedDocsPath);
        if (!Files.exists(docsDir) || !Files.isDirectory(docsDir)) {
            logger.warn("Seed documents path does not exist or is not a directory: {}", seedDocsPath);
            return;
        }

        // Walk through the directory and index all files
        Files.walk(docsDir)
                .filter(Files::isRegularFile)
                .forEach(file -> {
                    try {
                        String content = Files.readString(file);
                        String documentId = "seed_" + file.getFileName().toString();
                        String title = file.getFileName().toString();

                        Map<String, Object> metadata = new HashMap<>();
                        metadata.put("source", "seed");
                        metadata.put("path", file.toString());

                        boolean success = ragService.indexDocument(documentId, title, content, metadata);
                        if (success) {
                            logger.info("Successfully indexed seed document: {}", title);
                        } else {
                            logger.warn("Failed to index seed document: {}", title);
                        }
                    } catch (IOException e) {
                        logger.error("Error reading seed document {}: {}", file, e.getMessage(), e);
                    }
                });
    }

    /**
     * Load RAG configuration from a properties file.
     *
     * @param propertiesPath The path to the properties file
     * @return The loaded properties
     * @throws IOException If an I/O error occurs
     */
    public static Properties loadRagProperties(String propertiesPath) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(propertiesPath))) {
            props.load(in);
        }
        return props;
    }

    /**
     * Load RAG configuration from a properties file in the classpath.
     *
     * @param resourcePath The path to the properties file in the classpath
     * @return The loaded properties
     * @throws IOException If an I/O error occurs
     */
    public static Properties loadRagPropertiesFromResource(String resourcePath) throws IOException {
        Properties props = new Properties();
        try (InputStream in = RagFactory.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            props.load(in);
        }
        return props;
    }
}