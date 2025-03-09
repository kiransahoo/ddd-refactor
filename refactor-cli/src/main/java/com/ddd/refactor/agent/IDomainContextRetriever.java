package com.ddd.refactor.agent;

/**
 * Strategy interface for retrieving domain context/snippets relevant to a chunk of code.
 */
public interface IDomainContextRetriever {

    /**
     * Given some code chunk, returns an aggregated domain context string
     * that might help GPT produce a better fix.
     */
    String retrieveContext(String chunkText, int topK);
}
