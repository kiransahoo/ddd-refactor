package com.ddd.refactor.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A naive in-memory implementation of VectorDb,
 * storing DomainSnippets and retrieving by cosine similarity.
 * @author kiransahoo
 */
public class InMemoryVectorDb implements VectorDb {

    private final List<DomainSnippet> snippets = new ArrayList<>();

    // Add a snippet to the store
    public void addSnippet(DomainSnippet snippet) {
        snippets.add(snippet);
    }

    @Override
    public List<DomainSnippet> search(float[] queryEmbedding, int topK) {
        // Sort by descending similarity
        return snippets.stream()
                .sorted(Comparator.comparingDouble(
                        (DomainSnippet sn) -> cosineSimilarity(queryEmbedding, sn.getEmbedding())
                ).reversed())
                .limit(topK)
                .toList();
    }

    private double cosineSimilarity(float[] v1, float[] v2) {
        double dot = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i];
            norm1 += v1[i] * v1[i];
            norm2 += v2[i] * v2[i];
        }
        return dot / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}
