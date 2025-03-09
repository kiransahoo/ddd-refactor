package com.ddd.refactor.agent;

import java.util.List;

/**
 * Simple interface for a vector database that stores DomainSnippets.
 * @author kiransahoo
 * TODO As need arises will decide the best db for this purpose
 */
public interface VectorDb {

    /**
     * Search the DB for the top k snippets most similar to the given embedding.
     */
    List<DomainSnippet> search(float[] queryEmbedding, int topK);
}
