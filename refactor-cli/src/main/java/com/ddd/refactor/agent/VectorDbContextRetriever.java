package com.ddd.refactor.agent;

import java.util.List;

/**
 * Implementation of IDomainContextRetriever using a VectorDb for semantic search
 * plus an embedder to create query embeddings.
 * @author kiransahoo
 */
public class VectorDbContextRetriever implements IDomainContextRetriever {

    private final VectorDb vectorDb;
    private final OpenAiEmbedder embedder;

    public VectorDbContextRetriever(VectorDb vectorDb, OpenAiEmbedder embedder) {
        this.vectorDb = vectorDb;
        this.embedder = embedder;
    }

    @Override
    public String retrieveContext(String chunkText, int topK) {
        float[] queryEmbedding = embedder.embed(chunkText);
        List<DomainSnippet> topSnippets = vectorDb.search(queryEmbedding, topK);
        StringBuilder sb = new StringBuilder();
        for (DomainSnippet ds : topSnippets) {
            sb.append(ds.getText()).append("\n\n");
        }
        return sb.toString();
    }
}
