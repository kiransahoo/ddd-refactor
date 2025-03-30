package com.ddd.refactor.agent.rag;

import com.ddd.refactor.agent.IDomainContextRetriever;
import com.ddd.refactor.agent.embedding.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implementation of IDomainContextRetriever that uses RAG for semantic context retrieval.
 */
public class RagContextRetriever implements IDomainContextRetriever {

    private static final Logger logger = LoggerFactory.getLogger(RagContextRetriever.class);

    private final RagService ragService;
    private final EmbeddingService embeddingService;

    /**
     * Constructs a RagContextRetriever with the specified RAG service and embedding service.
     *
     * @param ragService The RAG service to use for context retrieval
     * @param embeddingService The embedding service to use for query embeddings
     */
    public RagContextRetriever(RagService ragService, EmbeddingService embeddingService) {
        this.ragService = ragService;
        this.embeddingService = embeddingService;
    }

    @Override
    public String retrieveContext(String chunkText, int topK) {
        if (ragService == null || !ragService.isEnabled() || !ragService.isRagAvailable()) {
            logger.warn("RAG service not available for context retrieval");
            return "";
        }

        try {
            // Generate embedding for the chunk text
            List<Float> queryEmbedding = embeddingService.generateEmbedding(chunkText);
            if (queryEmbedding.isEmpty()) {
                logger.warn("Failed to generate embedding for chunk text");
                return "";
            }

            // Create a temporary query document ID
            String queryId = "query_" + UUID.randomUUID().toString();

            // Generate metadata for the query
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("type", "query");
            metadata.put("timestamp", System.currentTimeMillis());

            // Use RAG service to retrieve relevant context
            StringBuilder contextBuilder = new StringBuilder();

            // Search for relevant documents using the query embedding
            List<Map<String, Object>> searchResults = embeddingService != null ?
                    queryVectorDb(queryEmbedding, topK) : null;

            if (searchResults != null && !searchResults.isEmpty()) {
                // Process search results
                for (Map<String, Object> result : searchResults) {
                    Map<String, Object> metadata1 = (Map<String, Object>) result.get("metadata");
                    if (metadata1 != null && metadata1.containsKey("content")) {
                        String snippet = (String) metadata1.get("content");
                        String title = metadata1.containsKey("title") ?
                                (String) metadata1.get("title") : "Untitled";

                        contextBuilder.append("// --- ").append(title).append(" ---\n");
                        contextBuilder.append(snippet).append("\n\n");
                    }
                }
            }

            return contextBuilder.toString().trim();
        } catch (Exception e) {
            logger.error("Error retrieving context: {}", e.getMessage(), e);
            return "";
        }
    }

    /**
     * Query the vector database using the embedding service.
     *
     * @param queryEmbedding The query embedding
     * @param topK The number of results to return
     * @return A list of search results
     */
    private List<Map<String, Object>> queryVectorDb(List<Float> queryEmbedding, int topK) {
        try {
            // Use the ragService to perform a direct vector DB query
            // This avoids having to index the query
            return ragService.getDocument("dummy").isEmpty() ?
                    List.of() :  // This is just to check if vector DB is available
                    ((com.ddd.refactor.agent.vectordb.VectorDbService)ragService
                            .getClass()
                            .getDeclaredField("vectorDbService")
                            .get(ragService))
                            .search(queryEmbedding, topK);
        } catch (Exception e) {
            logger.error("Error querying vector DB: {}", e.getMessage(), e);
            return List.of();
        }
    }
}