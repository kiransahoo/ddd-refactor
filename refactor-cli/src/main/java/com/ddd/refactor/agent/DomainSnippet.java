package com.ddd.refactor.agent;

/**
 * Represents a snippet of domain or framework code, plus some metadata.
 * @author kiransahoo
 */
public class DomainSnippet {
    private final String id;
    private final String text;
    private final float[] embedding;

    public DomainSnippet(String id, String text, float[] embedding) {
        this.id = id;
        this.text = text;
        this.embedding = embedding;
    }

    public String getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public float[] getEmbedding() {
        return embedding;
    }
}
