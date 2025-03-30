package com.ddd.refactor.agent.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implementation of EmbeddingService using OpenAI's embedding API.
 */
public class OpenAiEmbeddingService implements EmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiEmbeddingService.class);

    private final String apiKey;
    private final String modelName;
    private final String apiUrl;
    private final int batchSize;
    private final int embeddingDimension;
    private final HttpClient httpClient;
    private final AtomicBoolean available = new AtomicBoolean(false);

    /**
     * Constructs an OpenAiEmbeddingService with default settings.
     *
     * @param apiKey The OpenAI API key
     */
    public OpenAiEmbeddingService(String apiKey) {
        this(apiKey, "text-embedding-ada-002", "https://api.openai.com/v1/embeddings", 20, 1536);
    }

    /**
     * Constructs an OpenAiEmbeddingService with custom settings.
     *
     * @param apiKey The OpenAI API key
     * @param modelName The name of the embedding model to use
     * @param apiUrl The URL of the embeddings API
     * @param batchSize The maximum number of texts to embed in a single API call
     * @param embeddingDimension The dimension of the embedding vectors
     */
    public OpenAiEmbeddingService(String apiKey, String modelName, String apiUrl, int batchSize, int embeddingDimension) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.apiUrl = apiUrl;
        this.batchSize = batchSize;
        this.embeddingDimension = embeddingDimension;
        this.httpClient = HttpClient.newHttpClient();

        // Check availability
        initialize();
    }

    /**
     * Initializes the service and checks if the API is available.
     */
    private void initialize() {
        logger.info("Initializing OpenAI embedding service with API key: {}",
                apiKey != null ? (apiKey.substring(0, Math.min(3, apiKey.length())) + "...") : "null");

        // Validate API URL
        if (!isValidUrl(apiUrl)) {
            logger.error("Invalid API URL: {}. Must be an absolute URL with http:// or https:// scheme.", apiUrl);
            available.set(false);
            return;
        }

        // Validate API key
        if (apiKey == null || apiKey.isBlank()) {
            logger.error("OpenAI API key is not configured");
            available.set(false);
            return;
        }

        // Check availability
        checkAvailability();
    }

    @Override
    public List<Float> generateEmbedding(String text) {
        if (!available.get()) {
            logger.warn("OpenAI embedding service is not available");
            return Collections.emptyList();
        }

        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelName);
            requestBody.put("input", text);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(new org.json.JSONObject(requestBody).toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return extractEmbedding(new org.json.JSONObject(response.body()));
            }

            logger.error("Failed to generate embedding, status: {}", response.statusCode());
            return Collections.emptyList();
        } catch (Exception e) {
            logger.error("Error generating embedding: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<List<Float>> generateEmbeddings(List<String> texts) {
        if (!available.get()) {
            logger.warn("OpenAI embedding service is not available");
            return Collections.emptyList();
        }

        List<List<Float>> results = new ArrayList<>();

        // Process in batches to avoid exceeding API limits
        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, end);

            try {
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", modelName);
                requestBody.put("input", batch);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(new org.json.JSONObject(requestBody).toString()))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    List<List<Float>> batchResults = extractBatchEmbeddings(new org.json.JSONObject(response.body()));
                    results.addAll(batchResults);
                } else {
                    logger.error("Failed to generate batch embeddings, status: {}", response.statusCode());
                    // Add empty embeddings as placeholders for this batch
                    for (int j = 0; j < batch.size(); j++) {
                        results.add(Collections.emptyList());
                    }
                }
            } catch (Exception e) {
                logger.error("Error generating batch embeddings: {}", e.getMessage(), e);
                // Add empty embeddings as placeholders for this batch
                for (int j = 0; j < batch.size(); j++) {
                    results.add(Collections.emptyList());
                }
            }

            // Add a small delay between batches to respect rate limits
            if (end < texts.size()) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        return results;
    }

    @Override
    public Map<String, List<Float>> generateEmbeddingsForDocument(Map<String, String> document) {
        if (!available.get()) {
            logger.warn("OpenAI embedding service is not available");
            return Collections.emptyMap();
        }

        Map<String, List<Float>> results = new HashMap<>();

        // Extract all section texts
        List<String> sectionTexts = new ArrayList<>(document.values());
        List<String> sectionKeys = new ArrayList<>(document.keySet());

        // Generate embeddings for all sections
        List<List<Float>> embeddings = generateEmbeddings(sectionTexts);

        // Map the results back to the original section names
        for (int i = 0; i < sectionKeys.size(); i++) {
            if (i < embeddings.size()) {
                results.put(sectionKeys.get(i), embeddings.get(i));
            }
        }

        return results;
    }

    @Override
    public int getEmbeddingDimension() {
        return embeddingDimension;
    }

    @Override
    public boolean isAvailable() {
        return available.get();
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * Check if the embedding service is available by making a test API call.
     */
    private void checkAvailability() {
        if (!isValidUrl(apiUrl)) {
            logger.error("Cannot check availability: Invalid API URL: {}", apiUrl);
            available.set(false);
            return;
        }

        if (apiKey == null || apiKey.isBlank()) {
            logger.error("Cannot check availability: OpenAI API key is not configured");
            available.set(false);
            return;
        }

        try {
            // Make a simple test request with a short text
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelName);
            requestBody.put("input", "test");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(new org.json.JSONObject(requestBody).toString()))
                    .build();

            logger.info("Testing embedding service availability with URL: {}", apiUrl);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            boolean success = response.statusCode() == 200;
            available.set(success);

            if (success) {
                logger.info("OpenAI embedding service is available using model: {}", modelName);
            } else {
                logger.error("OpenAI embedding service test failed, status: {}", response.statusCode());
            }
        } catch (Exception e) {
            logger.error("Error checking OpenAI embedding service availability: {}", e.getMessage(), e);
            available.set(false);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Float> extractEmbedding(org.json.JSONObject response) {
        try {
            org.json.JSONArray data = response.getJSONArray("data");
            if (data.length() > 0) {
                org.json.JSONObject firstResult = data.getJSONObject(0);
                org.json.JSONArray embedding = firstResult.getJSONArray("embedding");

                // Convert to List<Float>
                List<Float> floatEmbedding = new ArrayList<>(embedding.length());
                for (int i = 0; i < embedding.length(); i++) {
                    floatEmbedding.add((float) embedding.getDouble(i));
                }
                return floatEmbedding;
            }
            return Collections.emptyList();
        } catch (Exception e) {
            logger.error("Error extracting embedding from response: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private List<List<Float>> extractBatchEmbeddings(org.json.JSONObject response) {
        try {
            org.json.JSONArray data = response.getJSONArray("data");
            List<List<Float>> results = new ArrayList<>(data.length());

            // Create a map to store embeddings by index
            Map<Integer, List<Float>> embeddingsByIndex = new HashMap<>();

            for (int i = 0; i < data.length(); i++) {
                org.json.JSONObject item = data.getJSONObject(i);
                int index = item.getInt("index");
                org.json.JSONArray embedding = item.getJSONArray("embedding");

                // Convert to List<Float>
                List<Float> floatEmbedding = new ArrayList<>(embedding.length());
                for (int j = 0; j < embedding.length(); j++) {
                    floatEmbedding.add((float) embedding.getDouble(j));
                }
                embeddingsByIndex.put(index, floatEmbedding);
            }

            // Add embeddings in the correct order
            for (int i = 0; i < embeddingsByIndex.size(); i++) {
                results.add(embeddingsByIndex.getOrDefault(i, Collections.emptyList()));
            }

            return results;
        } catch (Exception e) {
            logger.error("Error extracting batch embeddings from response: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Validate that a URL string is a valid absolute URL.
     */
    private boolean isValidUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        // Check if the URL has a scheme (http:// or https://)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return false;
        }

        // Validate URL format
        try {
            new URL(url);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}