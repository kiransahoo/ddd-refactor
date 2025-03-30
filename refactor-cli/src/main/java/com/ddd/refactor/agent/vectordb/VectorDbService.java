package com.ddd.refactor.agent.vectordb;

import java.util.List;
import java.util.Map;

/**
 * Interface defining the operations for a vector database service.
 * This abstraction allows us to use different vector DB implementations.
 */
public interface VectorDbService {

    /**
     * Upserts a document with its embedding vector into the vector database.
     *
     * @param documentId The unique identifier for the document
     * @param vector The embedding vector representation of the document
     * @param metadata Additional metadata about the document
     * @return true if successful, false otherwise
     */
    boolean upsert(String documentId, List<Float> vector, Map<String, Object> metadata);

    /**
     * Performs a similarity search in the vector database.
     *
     * @param queryVector The query vector to match against
     * @param topK The number of most similar results to return
     * @return A list of maps containing the matched documents with their metadata and scores
     */
    List<Map<String, Object>> search(List<Float> queryVector, int topK);

    /**
     * Retrieves a document by its ID.
     *
     * @param documentId The ID of the document to retrieve
     * @return The document with its metadata, or null if not found
     */
    Map<String, Object> getById(String documentId);

    /**
     * Deletes a document from the vector database.
     *
     * @param documentId The ID of the document to delete
     * @return true if successful, false otherwise
     */
    boolean delete(String documentId);

    /**
     * Initializes the vector database connection and required resources.
     *
     * @return true if initialization was successful, false otherwise
     */
    boolean initialize();

    /**
     * Checks if the vector database service is available and operational.
     *
     * @return true if the service is available, false otherwise
     */
    boolean isAvailable();

    /**
     * Executes a query in the native format of the specific vector database.
     * This allows for leveraging database-specific features.
     *
     * @param nativeQuery The native query in the format specific to the vector database
     * @return The query results as a list of maps
     */
    List<Map<String, Object>> executeNativeQuery(String nativeQuery);
}