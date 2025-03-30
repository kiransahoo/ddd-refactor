package com.ddd.refactor.agent.vectordb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Abstract implementation of VectorDbService providing common functionality
 * and error handling for vector database operations.
 */
public abstract class AbstractVectorDbService implements VectorDbService {

    private static final Logger logger = LoggerFactory.getLogger(AbstractVectorDbService.class);

    protected final AtomicBoolean initialized = new AtomicBoolean(false);
    protected final AtomicBoolean available = new AtomicBoolean(false);

    /**
     * Default constructor which calls initialize() method.
     */
    public AbstractVectorDbService() {
        boolean initResult = initialize();
        initialized.set(initResult);
        available.set(initResult);
        if (initResult) {
            logger.info("Vector database service initialized successfully: {}", getClass().getSimpleName());
        } else {
            logger.warn("Vector database service initialization failed: {}", getClass().getSimpleName());
        }
    }

    /**
     * Shuts down the vector database service.
     */
    public void shutdown() {
        closeResources();
        initialized.set(false);
        available.set(false);
        logger.info("Vector database service shutdown completed: {}", getClass().getSimpleName());
    }

    /**
     * Closes any resources used by the vector database service.
     * Implementations should override this method to properly clean up resources.
     */
    protected abstract void closeResources();

    @Override
    public boolean isAvailable() {
        return available.get();
    }

    @Override
    public List<Map<String, Object>> search(List<Float> queryVector, int topK) {
        if (!isAvailable()) {
            logger.warn("Vector database service not available for search: {}", getClass().getSimpleName());
            return Collections.emptyList();
        }

        try {
            return performSearch(queryVector, topK);
        } catch (Exception e) {
            logger.error("Error performing vector search: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Performs the actual similarity search implementation.
     *
     * @param queryVector The query vector to match against
     * @param topK The number of most similar results to return
     * @return A list of maps containing the matched documents with their metadata and scores
     */
    protected abstract List<Map<String, Object>> performSearch(List<Float> queryVector, int topK);

    @Override
    public boolean upsert(String documentId, List<Float> vector, Map<String, Object> metadata) {
        if (!isAvailable()) {
            logger.warn("Vector database service not available for upsert: {}", getClass().getSimpleName());
            return false;
        }

        try {
            return performUpsert(documentId, vector, metadata);
        } catch (Exception e) {
            logger.error("Error upserting document {}: {}", documentId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Performs the actual upsert implementation.
     *
     * @param documentId The unique identifier for the document
     * @param vector The embedding vector representation of the document
     * @param metadata Additional metadata about the document
     * @return true if successful, false otherwise
     */
    protected abstract boolean performUpsert(String documentId, List<Float> vector, Map<String, Object> metadata);

    @Override
    public Map<String, Object> getById(String documentId) {
        if (!isAvailable()) {
            logger.warn("Vector database service not available for getById: {}", getClass().getSimpleName());
            return Collections.emptyMap();
        }

        try {
            return performGetById(documentId);
        } catch (Exception e) {
            logger.error("Error retrieving document {}: {}", documentId, e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    /**
     * Performs the actual getById implementation.
     *
     * @param documentId The ID of the document to retrieve
     * @return The document with its metadata, or an empty map if not found
     */
    protected abstract Map<String, Object> performGetById(String documentId);

    @Override
    public boolean delete(String documentId) {
        if (!isAvailable()) {
            logger.warn("Vector database service not available for delete: {}", getClass().getSimpleName());
            return false;
        }

        try {
            return performDelete(documentId);
        } catch (Exception e) {
            logger.error("Error deleting document {}: {}", documentId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Performs the actual delete implementation.
     *
     * @param documentId The ID of the document to delete
     * @return true if successful, false otherwise
     */
    protected abstract boolean performDelete(String documentId);

    @Override
    public List<Map<String, Object>> executeNativeQuery(String nativeQuery) {
        if (!isAvailable()) {
            logger.warn("Vector database service not available for native query: {}", getClass().getSimpleName());
            return Collections.emptyList();
        }

        try {
            return performNativeQuery(nativeQuery);
        } catch (Exception e) {
            logger.error("Error executing native query: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Performs the execution of a native query.
     *
     * @param nativeQuery The native query in the format specific to the vector database
     * @return The query results as a list of maps
     */
    protected abstract List<Map<String, Object>> performNativeQuery(String nativeQuery);
}