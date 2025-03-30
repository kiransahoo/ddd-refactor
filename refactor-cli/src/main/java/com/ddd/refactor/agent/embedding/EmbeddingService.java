package com.ddd.refactor.agent.embedding;

import java.util.List;
import java.util.Map;

/**
 * Interface for services that generate vector embeddings from text.
 * These embeddings are used for semantic search in vector databases.
 */
public interface EmbeddingService {

    /**
     * Generate an embedding vector for a single text input.
     *
     * @param text The text to generate an embedding for
     * @return A list of floats representing the embedding vector
     */
    List<Float> generateEmbedding(String text);

    /**
     * Generate embedding vectors for multiple text inputs.
     *
     * @param texts A list of text strings to generate embeddings for
     * @return A list of embedding vectors, each represented as a list of floats
     */
    List<List<Float>> generateEmbeddings(List<String> texts);

    /**
     * Generate embeddings for a structured document with multiple sections.
     *
     * @param document A map of section names to text content
     * @return A map of section names to their corresponding embedding vectors
     */
    Map<String, List<Float>> generateEmbeddingsForDocument(Map<String, String> document);

    /**
     * Get the dimensionality of the embeddings produced by this service.
     *
     * @return The number of dimensions in the embedding vectors
     */
    int getEmbeddingDimension();

    /**
     * Checks if the embedding service is available and operational.
     *
     * @return true if the service is available, false otherwise
     */
    boolean isAvailable();

    /**
     * Get the name of the model used for generating embeddings.
     *
     * @return The name of the embedding model
     */
    String getModelName();
}