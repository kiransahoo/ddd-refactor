package com.ddd.refactor.agent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Configuration class for RAG settings.
 */
public class RagConfig {

    private static final Logger logger = LoggerFactory.getLogger(RagConfig.class);

    private boolean enabled;
    private int maxResults;
    private double relevanceThreshold;
    private boolean includeCitations;
    private String vectorDbProvider;
    private String embeddingProvider;
    private String openAiApiKey;
    private String openAiEmbeddingModel;
    private String openAiEmbeddingUrl;
    private int embeddingBatchSize;
    private int embeddingDimension;
    private String pineconeApiKey;
    private String pineconeEnvironment;
    private String pineconeProjectId;
    private String pineconeIndexName;
    private String pineconeNamespace;
    private boolean indexCodeSnippets;
    private String documentStoragePath;
    private int chunkSize;
    private int chunkOverlap;
    private String domainContextPath;
    private boolean useForPromptEnhancement;

    /**
     * Create a RagConfig from a Properties object.
     *
     * @param props The properties containing RAG settings
     * @return A configured RagConfig
     */
    public static RagConfig fromProperties(Properties props) {
        RagConfig config = new RagConfig();

        // RAG settings
        config.setEnabled(Boolean.parseBoolean(props.getProperty("rag.enabled", "false")));
        config.setMaxResults(Integer.parseInt(props.getProperty("rag.maxResults", "5")));
        config.setRelevanceThreshold(Double.parseDouble(props.getProperty("rag.relevanceThreshold", "0.7")));
        config.setIncludeCitations(Boolean.parseBoolean(props.getProperty("rag.includeCitations", "true")));
        config.setUseForPromptEnhancement(Boolean.parseBoolean(props.getProperty("rag.useForPromptEnhancement", "true")));
        config.setIndexCodeSnippets(Boolean.parseBoolean(props.getProperty("rag.indexCodeSnippets", "true")));
        config.setDocumentStoragePath(props.getProperty("rag.documentStoragePath", "./documents"));
        config.setChunkSize(Integer.parseInt(props.getProperty("rag.chunkSize", "1000")));
        config.setChunkOverlap(Integer.parseInt(props.getProperty("rag.chunkOverlap", "200")));
        config.setDomainContextPath(props.getProperty("rag.domainContextPath", ""));

        // Vector DB settings
        config.setVectorDbProvider(props.getProperty("vectordb.provider", "inmemory"));
        config.setPineconeApiKey(props.getProperty("vectordb.pinecone.apiKey", ""));
        config.setPineconeEnvironment(props.getProperty("vectordb.pinecone.environment", ""));
        config.setPineconeProjectId(props.getProperty("vectordb.pinecone.projectId", ""));
        config.setPineconeIndexName(props.getProperty("vectordb.pinecone.indexName", ""));
        config.setPineconeNamespace(props.getProperty("vectordb.pinecone.namespace", ""));

        // Embedding settings
        config.setEmbeddingProvider(props.getProperty("embedding.provider", "openai"));
        config.setOpenAiApiKey(props.getProperty("embedding.openai.apiKey", props.getProperty("llm.api.key", "")));
        config.setOpenAiEmbeddingModel(props.getProperty("embedding.openai.model", "text-embedding-ada-002"));
        config.setOpenAiEmbeddingUrl(props.getProperty("embedding.openai.apiUrl", "https://api.openai.com/v1/embeddings"));
        config.setEmbeddingBatchSize(Integer.parseInt(props.getProperty("embedding.openai.batchSize", "20")));
        config.setEmbeddingDimension(Integer.parseInt(props.getProperty("embedding.openai.dimension", "1536")));

        return config;
    }

    /**
     * Load RAG configuration from a properties file.
     *
     * @param propertiesPath Path to the properties file
     * @return A configured RagConfig
     * @throws IOException If an I/O error occurs
     */
    public static RagConfig fromPropertiesFile(String propertiesPath) throws IOException {
        Properties props = new Properties();
        try (InputStream input = new FileInputStream(propertiesPath)) {
            props.load(input);
        }
        return fromProperties(props);
    }

    /**
     * Load RAG configuration from a resource in the classpath.
     *
     * @param resourcePath Path to the resource
     * @return A configured RagConfig
     * @throws IOException If an I/O error occurs
     */
    public static RagConfig fromResource(String resourcePath) throws IOException {
        Properties props = new Properties();
        try (InputStream input = RagConfig.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            props.load(input);
        }
        return fromProperties(props);
    }

    /**
     * Convert this configuration to a Properties object.
     *
     * @return A Properties object containing this configuration
     */
    public Properties toProperties() {
        Properties props = new Properties();

        // RAG settings
        props.setProperty("rag.enabled", String.valueOf(enabled));
        props.setProperty("rag.maxResults", String.valueOf(maxResults));
        props.setProperty("rag.relevanceThreshold", String.valueOf(relevanceThreshold));
        props.setProperty("rag.includeCitations", String.valueOf(includeCitations));
        props.setProperty("rag.useForPromptEnhancement", String.valueOf(useForPromptEnhancement));
        props.setProperty("rag.indexCodeSnippets", String.valueOf(indexCodeSnippets));
        props.setProperty("rag.documentStoragePath", documentStoragePath);
        props.setProperty("rag.chunkSize", String.valueOf(chunkSize));
        props.setProperty("rag.chunkOverlap", String.valueOf(chunkOverlap));
        props.setProperty("rag.domainContextPath", domainContextPath);

        // Vector DB settings
        props.setProperty("vectordb.provider", vectorDbProvider);
        props.setProperty("vectordb.pinecone.apiKey", pineconeApiKey);
        props.setProperty("vectordb.pinecone.environment", pineconeEnvironment);
        props.setProperty("vectordb.pinecone.projectId", pineconeProjectId);
        props.setProperty("vectordb.pinecone.indexName", pineconeIndexName);
        props.setProperty("vectordb.pinecone.namespace", pineconeNamespace);

        // Embedding settings
        props.setProperty("embedding.provider", embeddingProvider);
        props.setProperty("embedding.openai.apiKey", openAiApiKey);
        props.setProperty("embedding.openai.model", openAiEmbeddingModel);
        props.setProperty("embedding.openai.apiUrl", openAiEmbeddingUrl);
        props.setProperty("embedding.openai.batchSize", String.valueOf(embeddingBatchSize));
        props.setProperty("embedding.openai.dimension", String.valueOf(embeddingDimension));

        return props;
    }

    // Getters and setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(int maxResults) {
        this.maxResults = maxResults;
    }

    public double getRelevanceThreshold() {
        return relevanceThreshold;
    }

    public void setRelevanceThreshold(double relevanceThreshold) {
        this.relevanceThreshold = relevanceThreshold;
    }

    public boolean isIncludeCitations() {
        return includeCitations;
    }

    public void setIncludeCitations(boolean includeCitations) {
        this.includeCitations = includeCitations;
    }

    public String getVectorDbProvider() {
        return vectorDbProvider;
    }

    public void setVectorDbProvider(String vectorDbProvider) {
        this.vectorDbProvider = vectorDbProvider;
    }

    public String getEmbeddingProvider() {
        return embeddingProvider;
    }

    public void setEmbeddingProvider(String embeddingProvider) {
        this.embeddingProvider = embeddingProvider;
    }

    public String getOpenAiApiKey() {
        return openAiApiKey;
    }

    public void setOpenAiApiKey(String openAiApiKey) {
        this.openAiApiKey = openAiApiKey;
    }

    public String getOpenAiEmbeddingModel() {
        return openAiEmbeddingModel;
    }

    public void setOpenAiEmbeddingModel(String openAiEmbeddingModel) {
        this.openAiEmbeddingModel = openAiEmbeddingModel;
    }

    public String getOpenAiEmbeddingUrl() {
        return openAiEmbeddingUrl;
    }

    public void setOpenAiEmbeddingUrl(String openAiEmbeddingUrl) {
        this.openAiEmbeddingUrl = openAiEmbeddingUrl;
    }

    public int getEmbeddingBatchSize() {
        return embeddingBatchSize;
    }

    public void setEmbeddingBatchSize(int embeddingBatchSize) {
        this.embeddingBatchSize = embeddingBatchSize;
    }

    public int getEmbeddingDimension() {
        return embeddingDimension;
    }

    public void setEmbeddingDimension(int embeddingDimension) {
        this.embeddingDimension = embeddingDimension;
    }

    public String getPineconeApiKey() {
        return pineconeApiKey;
    }

    public void setPineconeApiKey(String pineconeApiKey) {
        this.pineconeApiKey = pineconeApiKey;
    }

    public String getPineconeEnvironment() {
        return pineconeEnvironment;
    }

    public void setPineconeEnvironment(String pineconeEnvironment) {
        this.pineconeEnvironment = pineconeEnvironment;
    }

    public String getPineconeProjectId() {
        return pineconeProjectId;
    }

    public void setPineconeProjectId(String pineconeProjectId) {
        this.pineconeProjectId = pineconeProjectId;
    }

    public String getPineconeIndexName() {
        return pineconeIndexName;
    }

    public void setPineconeIndexName(String pineconeIndexName) {
        this.pineconeIndexName = pineconeIndexName;
    }

    public String getPineconeNamespace() {
        return pineconeNamespace;
    }

    public void setPineconeNamespace(String pineconeNamespace) {
        this.pineconeNamespace = pineconeNamespace;
    }

    public boolean isIndexCodeSnippets() {
        return indexCodeSnippets;
    }

    public void setIndexCodeSnippets(boolean indexCodeSnippets) {
        this.indexCodeSnippets = indexCodeSnippets;
    }

    public String getDocumentStoragePath() {
        return documentStoragePath;
    }

    public void setDocumentStoragePath(String documentStoragePath) {
        this.documentStoragePath = documentStoragePath;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }

    public String getDomainContextPath() {
        return domainContextPath;
    }

    public void setDomainContextPath(String domainContextPath) {
        this.domainContextPath = domainContextPath;
    }

    public boolean isUseForPromptEnhancement() {
        return useForPromptEnhancement;
    }

    public void setUseForPromptEnhancement(boolean useForPromptEnhancement) {
        this.useForPromptEnhancement = useForPromptEnhancement;
    }
}