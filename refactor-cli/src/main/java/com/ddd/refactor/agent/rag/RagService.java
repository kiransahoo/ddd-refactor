package com.ddd.refactor.agent.rag;

import com.ddd.refactor.agent.embedding.EmbeddingService;
import com.ddd.refactor.agent.vectordb.VectorDbService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Retrieval Augmented Generation (RAG) service for enhancing LLM prompts
 * with relevant context from a vector database.
 */
public class RagService {

    private static final Logger logger = LoggerFactory.getLogger(RagService.class);

    private final VectorDbService vectorDbService;
    private final EmbeddingService embeddingService;
    private final int maxResults;
    private final double relevanceThreshold;
    private final boolean includeCitations;
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    /**
     * Constructs a RagService with the specified vector database and embedding services.
     *
     * @param vectorDbService The vector database service to use
     * @param embeddingService The embedding service to use
     * @param maxResults Maximum number of results to return from vector search
     * @param relevanceThreshold Minimum similarity score threshold for including results
     * @param includeCitations Whether to append source citations to RAG-enhanced responses
     */
    public RagService(VectorDbService vectorDbService, EmbeddingService embeddingService,
                      int maxResults, double relevanceThreshold, boolean includeCitations) {
        this.vectorDbService = vectorDbService;
        this.embeddingService = embeddingService;
        this.maxResults = maxResults;
        this.relevanceThreshold = relevanceThreshold;
        this.includeCitations = includeCitations;

        // Enable RAG if both services are available
        boolean isAvailable = isRagAvailable();
        this.enabled.set(isAvailable);

        if (isAvailable) {
            logger.info("RAG service initialized successfully with maxResults={}, relevanceThreshold={}, includeCitations={}",
                    maxResults, relevanceThreshold, includeCitations);
        } else {
            logger.warn("RAG service is not fully available. Check embedding and vector DB services.");
        }
    }

    /**
     * Constructs a RagService with default settings.
     *
     * @param vectorDbService The vector database service to use
     * @param embeddingService The embedding service to use
     */
    public RagService(VectorDbService vectorDbService, EmbeddingService embeddingService) {
        this(vectorDbService, embeddingService, 5, 0.7, true);
    }

    /**
     * Enhance a prompt with relevant context from the vector database.
     *
     * @param originalPrompt The original prompt to enhance
     * @param userQuery The user query to find relevant context for
     * @return The enhanced prompt with relevant context
     */
    public String enhancePromptWithContext(String originalPrompt, String userQuery) {
        if (!isEnabled() || !isRagAvailable()) {
            logger.debug("RAG is not available or disabled, returning original prompt");
            return originalPrompt;
        }

        try {
            // Generate embedding for the user query
            List<Float> queryEmbedding = embeddingService.generateEmbedding(userQuery);
            if (queryEmbedding.isEmpty()) {
                logger.warn("Failed to generate embedding for user query");
                return originalPrompt;
            }

            // Search for relevant context in the vector database
            List<Map<String, Object>> searchResults = vectorDbService.search(queryEmbedding, maxResults);
            if (searchResults.isEmpty()) {
                logger.info("No relevant context found for user query");
                return originalPrompt;
            }

            // Filter results based on relevance threshold
            List<Map<String, Object>> relevantResults = filterByRelevance(searchResults);
            if (relevantResults.isEmpty()) {
                logger.info("No results met the relevance threshold");
                return originalPrompt;
            }

            // Format the relevant context
            String formattedContext = formatRelevantContext(relevantResults);

            // Enhance the prompt
            return enhancePrompt(originalPrompt, formattedContext);

        } catch (Exception e) {
            logger.error("Error enhancing prompt with RAG: {}", e.getMessage(), e);
            return originalPrompt;
        }
    }

    /**
     * Check if the RAG functionality is available.
     *
     * @return true if RAG is available, false otherwise
     */
    public boolean isRagAvailable() {
        return vectorDbService != null &&
                embeddingService != null &&
                vectorDbService.isAvailable() &&
                embeddingService.isAvailable();
    }

    /**
     * Check if RAG enhancement is enabled.
     *
     * @return true if enabled, false otherwise
     */
    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * Enable or disable RAG enhancement.
     *
     * @param enabled true to enable, false to disable
     */
    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }

    /**
     * Filter search results based on the relevance threshold.
     *
     * @param searchResults The search results to filter
     * @return The filtered search results
     */
    private List<Map<String, Object>> filterByRelevance(List<Map<String, Object>> searchResults) {
        List<Map<String, Object>> filteredResults = new ArrayList<>();
        for (Map<String, Object> result : searchResults) {
            Double score = (Double) result.get("score");
            if (score != null && score >= relevanceThreshold) {
                filteredResults.add(result);
            }
        }
        return filteredResults;
    }

    /**
     * Format the relevant context from search results.
     *
     * @param relevantResults The relevant search results
     * @return The formatted context
     */
    private String formatRelevantContext(List<Map<String, Object>> relevantResults) {
        StringBuilder contextBuilder = new StringBuilder();

        for (Map<String, Object> result : relevantResults) {
            Map<String, Object> metadata = (Map<String, Object>) result.get("metadata");
            if (metadata != null) {
                String title = String.valueOf(metadata.getOrDefault("title", "Untitled Document"));
                String content = String.valueOf(metadata.getOrDefault("content", ""));

                if (!content.isEmpty()) {
                    contextBuilder.append("--- ").append(title).append(" ---\n");
                    contextBuilder.append(content).append("\n\n");
                }
            }
        }

        return contextBuilder.toString().trim();
    }

    /**
     * Enhance the original prompt with the formatted context.
     *
     * @param originalPrompt The original prompt
     * @param formattedContext The formatted context
     * @return The enhanced prompt
     */
    private String enhancePrompt(String originalPrompt, String formattedContext) {
        if (formattedContext.isEmpty()) {
            return originalPrompt;
        }

        StringBuilder enhancedPrompt = new StringBuilder(originalPrompt);

        // Add a separator if needed
        if (!originalPrompt.endsWith("\n")) {
            enhancedPrompt.append("\n\n");
        } else if (!originalPrompt.endsWith("\n\n")) {
            enhancedPrompt.append("\n");
        }

        // Add the context with an appropriate header
        enhancedPrompt.append("RELEVANT CONTEXT:\n");
        enhancedPrompt.append(formattedContext);
        enhancedPrompt.append("\n\n");
        enhancedPrompt.append("Use the above context to inform your response when applicable.");

        return enhancedPrompt.toString();
    }

    /**
     * Index a document in the vector database for later retrieval.
     *
     * @param documentId The unique identifier for the document
     * @param title The title of the document
     * @param content The content of the document
     * @param additionalMetadata Additional metadata for the document
     * @return true if indexing was successful, false otherwise
     */
    public boolean indexDocument(String documentId, String title, String content, Map<String, Object> additionalMetadata) {
        if (!isEnabled() || !isRagAvailable()) {
            logger.debug("RAG is not available or disabled, skipping document indexing");
            return false;
        }

        try {
            // Generate embedding for the content
            List<Float> contentEmbedding = embeddingService.generateEmbedding(content);
            if (contentEmbedding.isEmpty()) {
                logger.warn("Failed to generate embedding for document content");
                return false;
            }

            // Prepare metadata
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("title", title);
            metadata.put("content", content);

            // Add additional metadata if provided
            if (additionalMetadata != null) {
                metadata.putAll(additionalMetadata);
            }

            // Upsert the document in the vector database
            boolean success = vectorDbService.upsert(documentId, contentEmbedding, metadata);

            if (success) {
                logger.info("Successfully indexed document: {}", title);
            } else {
                logger.warn("Failed to index document: {}", title);
            }

            return success;
        } catch (Exception e) {
            logger.error("Error indexing document: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Delete a document from the vector database.
     *
     * @param documentId The unique identifier for the document
     * @return true if deletion was successful, false otherwise
     */
    public boolean deleteDocument(String documentId) {
        if (!isEnabled() || !isRagAvailable()) {
            logger.debug("RAG is not available or disabled, skipping document deletion");
            return false;
        }

        try {
            boolean success = vectorDbService.delete(documentId);

            if (success) {
                logger.info("Successfully deleted document: {}", documentId);
            } else {
                logger.warn("Failed to delete document: {}", documentId);
            }

            return success;
        } catch (Exception e) {
            logger.error("Error deleting document: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Index code snippets for context retrieval during refactoring.
     *
     * @param snippets Map of snippet identifiers to their content
     * @param language The programming language of the snippets
     * @return Number of snippets successfully indexed
     */
    public int indexCodeSnippets(Map<String, String> snippets, String language) {
        if (!isEnabled() || !isRagAvailable()) {
            logger.debug("RAG is not available or disabled, skipping code snippet indexing");
            return 0;
        }

        int successCount = 0;
        for (Map.Entry<String, String> entry : snippets.entrySet()) {
            String snippetId = entry.getKey();
            String content = entry.getValue();

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("type", "code_snippet");
            metadata.put("language", language);

            if (indexDocument("snippet_" + snippetId, snippetId, content, metadata)) {
                successCount++;
            }
        }

        logger.info("Indexed {}/{} code snippets", successCount, snippets.size());
        return successCount;
    }

    /**
     * Retrieve a document by its ID.
     *
     * @param documentId The ID of the document to retrieve
     * @return The document with its metadata, or an empty map if not found
     */
    public Map<String, Object> getDocument(String documentId) {
        if (!isEnabled() || !isRagAvailable()) {
            return Collections.emptyMap();
        }

        return vectorDbService.getById(documentId);
    }
}