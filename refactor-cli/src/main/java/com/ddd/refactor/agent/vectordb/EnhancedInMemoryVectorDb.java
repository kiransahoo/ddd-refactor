package com.ddd.refactor.agent.vectordb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Enhanced in-memory implementation of VectorDbService with thread-safety and additional features.
 */
public class EnhancedInMemoryVectorDb extends AbstractVectorDbService {

    private static final Logger logger = LoggerFactory.getLogger(EnhancedInMemoryVectorDb.class);

    // Thread-safe map to store documents by ID
    private final Map<String, DocumentEntry> documents = new ConcurrentHashMap<>();

    /**
     * Document entry class to encapsulate a document's vector and metadata.
     */
    private static class DocumentEntry {
        final List<Float> vector;
        final Map<String, Object> metadata;

        DocumentEntry(List<Float> vector, Map<String, Object> metadata) {
            this.vector = new ArrayList<>(vector);
            this.metadata = new HashMap<>(metadata);
        }
    }

    @Override
    public boolean initialize() {
        logger.info("Initializing enhanced in-memory vector database");
        return true; // Always succeeds for in-memory implementation
    }

    @Override
    protected void closeResources() {
        logger.info("Cleaning up in-memory vector database resources");
        documents.clear();
    }

    @Override
    protected List<Map<String, Object>> performSearch(List<Float> queryVector, int topK) {
        if (documents.isEmpty()) {
            logger.info("Search performed on empty vector database");
            return Collections.emptyList();
        }

        // Calculate similarity scores for all documents
        List<Map.Entry<String, Double>> scores = documents.entrySet().stream()
                .map(entry -> Map.entry(
                        entry.getKey(),
                        cosineSimilarity(queryVector, entry.getValue().vector)
                ))
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .collect(Collectors.toList());

        // Convert to result format
        return scores.stream()
                .map(entry -> {
                    String docId = entry.getKey();
                    Double score = entry.getValue();
                    DocumentEntry doc = documents.get(docId);
                    Map<String, Object> result = new HashMap<>();
                    result.put("id", docId);
                    result.put("score", score);
                    result.put("metadata", doc.metadata);
                    return result;
                })
                .collect(Collectors.toList());
    }

    @Override
    protected boolean performUpsert(String documentId, List<Float> vector, Map<String, Object> metadata) {
        documents.put(documentId, new DocumentEntry(vector, metadata));
        logger.debug("Upserted document with ID: {}", documentId);
        return true;
    }

    @Override
    protected Map<String, Object> performGetById(String documentId) {
        DocumentEntry entry = documents.get(documentId);
        if (entry == null) {
            logger.debug("Document not found with ID: {}", documentId);
            return Collections.emptyMap();
        }

        Map<String, Object> result = new HashMap<>();
        result.put("id", documentId);
        result.put("metadata", entry.metadata);
        result.put("vector", entry.vector);
        return result;
    }

    @Override
    protected boolean performDelete(String documentId) {
        DocumentEntry removed = documents.remove(documentId);
        boolean success = removed != null;
        if (success) {
            logger.debug("Deleted document with ID: {}", documentId);
        } else {
            logger.debug("Document not found for deletion, ID: {}", documentId);
        }
        return success;
    }

    @Override
    protected List<Map<String, Object>> performNativeQuery(String nativeQuery) {
        logger.warn("Native queries not supported in in-memory vector database");
        return Collections.emptyList();
    }

    /**
     * Calculates the cosine similarity between two vectors.
     *
     * @param v1 First vector
     * @param v2 Second vector
     * @return The cosine similarity score between the vectors
     */
    private double cosineSimilarity(List<Float> v1, List<Float> v2) {
        if (v1.size() != v2.size()) {
            // Vectors must be of the same dimension
            int minSize = Math.min(v1.size(), v2.size());
            v1 = v1.subList(0, minSize);
            v2 = v2.subList(0, minSize);
        }

        double dot = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < v1.size(); i++) {
            dot += v1.get(i) * v2.get(i);
            norm1 += v1.get(i) * v1.get(i);
            norm2 += v2.get(i) * v2.get(i);
        }

        // Avoid division by zero
        if (norm1 == 0 || norm2 == 0) {
            return 0.0;
        }

        return dot / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    /**
     * Returns the current number of documents in the database.
     *
     * @return The number of documents
     */
    public int getDocumentCount() {
        return documents.size();
    }

    /**
     * Clears all documents from the database.
     */
    public void clear() {
        documents.clear();
        logger.info("In-memory vector database cleared");
    }

    /**
     * Bulk upsert multiple documents at once.
     *
     * @param documents Map of document IDs to their vectors and metadata
     * @return Number of documents successfully upserted
     */
    public int bulkUpsert(Map<String, Map.Entry<List<Float>, Map<String, Object>>> documents) {
        int count = 0;
        for (Map.Entry<String, Map.Entry<List<Float>, Map<String, Object>>> entry : documents.entrySet()) {
            String docId = entry.getKey();
            List<Float> vector = entry.getValue().getKey();
            Map<String, Object> metadata = entry.getValue().getValue();

            if (upsert(docId, vector, metadata)) {
                count++;
            }
        }
        logger.info("Bulk upserted {} documents", count);
        return count;
    }
}