package com.ddd.refactor.agent;

import java.util.Collections;
import java.util.List;

/**
 * If a real vector DB is unavailable, or we want a fallback,
 * we can just return a fixed snippet or empty list.
 * @author kiransahoo
 */
public class FallbackVectorDb implements VectorDb {

    private final String fallbackSnippet;

    public FallbackVectorDb(String fallbackSnippet) {
        this.fallbackSnippet = fallbackSnippet;
    }

    @Override
    public List<DomainSnippet> search(float[] queryEmbedding, int topK) {
        // Return a single snippet with no actual similarity check.
        DomainSnippet ds = new DomainSnippet("fallback", fallbackSnippet, new float[]{});
        return Collections.singletonList(ds);
    }
}
