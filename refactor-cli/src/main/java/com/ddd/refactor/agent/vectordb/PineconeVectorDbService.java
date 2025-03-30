package com.ddd.refactor.agent.vectordb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

/**
 * Implementation of VectorDbService for Pinecone vector database.
 */
public class PineconeVectorDbService extends AbstractVectorDbService {

    private static final Logger logger = LoggerFactory.getLogger(PineconeVectorDbService.class);

    private final String apiKey;
    private final String environment;
    private final String projectId;
    private final String indexName;
    private final String namespace;
    private final HttpClient httpClient;
    private String baseUrl;

    /**
     * Constructs a PineconeVectorDbService with the given parameters.
     *
     * @param apiKey The Pinecone API key
     * @param environment The Pinecone environment (e.g., "us-west1-gcp")
     * @param projectId The Pinecone project ID
     * @param indexName The name of the Pinecone index
     * @param namespace The namespace to use within the index (can be empty)
     */
    public PineconeVectorDbService(String apiKey, String environment, String projectId, String indexName, String namespace) {
        this.apiKey = apiKey;
        this.environment = environment;
        this.projectId = projectId;
        this.indexName = indexName;
        this.namespace = namespace;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Constructs a PineconeVectorDbService with the given parameters and default namespace.
     *
     * @param apiKey The Pinecone API key
     * @param environment The Pinecone environment (e.g., "us-west1-gcp")
     * @param projectId The Pinecone project ID
     * @param indexName The name of the Pinecone index
     */
    public PineconeVectorDbService(String apiKey, String environment, String projectId, String indexName) {
        this(apiKey, environment, projectId, indexName, "");
    }

    @Override
    public boolean initialize() {
        try {
            baseUrl = String.format("https://%s-%s.svc.%s.pinecone.io",
                    indexName, projectId, environment);

            // Test connection by making a simple describe index request
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/describe_index_stats"))
                    .header("Content-Type", "application/json")
                    .header("Api-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            if (success) {
                logger.info("Successfully connected to Pinecone index: {}", indexName);
            } else {
                logger.error("Failed to connect to Pinecone index: {}, status: {}",
                        indexName, response.statusCode());
            }
            return success;
        } catch (Exception e) {
            logger.error("Error initializing Pinecone service: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    protected void closeResources() {
        // No specific resources to close for REST-based Pinecone client
    }

    @Override
    protected List<Map<String, Object>> performSearch(List<Float> queryVector, int topK) {
        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("vector", new JSONArray(queryVector));
            requestBody.put("topK", topK);
            if (namespace != null && !namespace.isEmpty()) {
                requestBody.put("namespace", namespace);
            }
            requestBody.put("includeMetadata", true);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/query"))
                    .header("Content-Type", "application/json")
                    .header("Api-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JSONObject jsonResponse = new JSONObject(response.body());
                JSONArray matches = jsonResponse.optJSONArray("matches");
                if (matches != null) {
                    List<Map<String, Object>> results = new ArrayList<>();
                    for (int i = 0; i < matches.length(); i++) {
                        JSONObject match = matches.getJSONObject(i);
                        Map<String, Object> result = new HashMap<>();
                        result.put("id", match.getString("id"));
                        result.put("score", match.getDouble("score"));

                        JSONObject metadata = match.optJSONObject("metadata");
                        if (metadata != null) {
                            result.put("metadata", toMap(metadata));
                        } else {
                            result.put("metadata", Collections.emptyMap());
                        }

                        results.add(result);
                    }
                    return results;
                }
            }

            logger.error("Failed to search Pinecone, status: {}", response.statusCode());
            return Collections.emptyList();
        } catch (Exception e) {
            logger.error("Error performing Pinecone search: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    protected boolean performUpsert(String documentId, List<Float> vector, Map<String, Object> metadata) {
        try {
            JSONObject requestBody = new JSONObject();

            JSONArray vectors = new JSONArray();
            JSONObject vectorData = new JSONObject();
            vectorData.put("id", documentId);
            vectorData.put("values", new JSONArray(vector));
            vectorData.put("metadata", new JSONObject(metadata));
            vectors.put(vectorData);

            requestBody.put("vectors", vectors);
            if (namespace != null && !namespace.isEmpty()) {
                requestBody.put("namespace", namespace);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/vectors/upsert"))
                    .header("Content-Type", "application/json")
                    .header("Api-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            if (!success) {
                logger.error("Failed to upsert to Pinecone, status: {}", response.statusCode());
            }
            return success;
        } catch (Exception e) {
            logger.error("Error upserting to Pinecone: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    protected Map<String, Object> performGetById(String documentId) {
        try {
            JSONObject requestBody = new JSONObject();
            JSONArray ids = new JSONArray();
            ids.put(documentId);
            requestBody.put("ids", ids);

            if (namespace != null && !namespace.isEmpty()) {
                requestBody.put("namespace", namespace);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/vectors/fetch"))
                    .header("Content-Type", "application/json")
                    .header("Api-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JSONObject jsonResponse = new JSONObject(response.body());
                JSONObject vectors = jsonResponse.optJSONObject("vectors");
                if (vectors != null && vectors.has(documentId)) {
                    JSONObject vector = vectors.getJSONObject(documentId);
                    Map<String, Object> result = new HashMap<>();
                    result.put("id", documentId);

                    JSONObject metadata = vector.optJSONObject("metadata");
                    if (metadata != null) {
                        result.put("metadata", toMap(metadata));
                    } else {
                        result.put("metadata", Collections.emptyMap());
                    }

                    JSONArray values = vector.optJSONArray("values");
                    if (values != null) {
                        List<Float> vectorValues = new ArrayList<>();
                        for (int i = 0; i < values.length(); i++) {
                            vectorValues.add((float) values.getDouble(i));
                        }
                        result.put("vector", vectorValues);
                    }

                    return result;
                }
            }

            logger.error("Failed to fetch document from Pinecone, status: {}", response.statusCode());
            return Collections.emptyMap();
        } catch (Exception e) {
            logger.error("Error fetching document from Pinecone: {}", e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    @Override
    protected boolean performDelete(String documentId) {
        try {
            JSONObject requestBody = new JSONObject();
            JSONArray ids = new JSONArray();
            ids.put(documentId);
            requestBody.put("ids", ids);

            if (namespace != null && !namespace.isEmpty()) {
                requestBody.put("namespace", namespace);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/vectors/delete"))
                    .header("Content-Type", "application/json")
                    .header("Api-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            if (!success) {
                logger.error("Failed to delete from Pinecone, status: {}", response.statusCode());
            }
            return success;
        } catch (Exception e) {
            logger.error("Error deleting from Pinecone: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    protected List<Map<String, Object>> performNativeQuery(String nativeQuery) {
        try {
            // For Pinecone, the native query is expected to be a JSON string
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/query"))
                    .header("Content-Type", "application/json")
                    .header("Api-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(nativeQuery))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JSONObject jsonResponse = new JSONObject(response.body());
                JSONArray matches = jsonResponse.optJSONArray("matches");
                if (matches != null) {
                    List<Map<String, Object>> results = new ArrayList<>();
                    for (int i = 0; i < matches.length(); i++) {
                        JSONObject match = matches.getJSONObject(i);
                        Map<String, Object> result = new HashMap<>();
                        result.put("id", match.getString("id"));
                        result.put("score", match.getDouble("score"));

                        JSONObject metadata = match.optJSONObject("metadata");
                        if (metadata != null) {
                            result.put("metadata", toMap(metadata));
                        } else {
                            result.put("metadata", Collections.emptyMap());
                        }

                        results.add(result);
                    }
                    return results;
                }
            }

            logger.error("Failed to execute native query in Pinecone, status: {}", response.statusCode());
            return Collections.emptyList();
        } catch (Exception e) {
            logger.error("Error executing native Pinecone query: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Converts a JSONObject to a Map.
     *
     * @param jsonObject The JSONObject to convert
     * @return A Map representation of the JSONObject
     */
    private Map<String, Object> toMap(JSONObject jsonObject) {
        Map<String, Object> map = new HashMap<>();
        for (String key : jsonObject.keySet()) {
            Object value = jsonObject.get(key);
            if (value instanceof JSONObject) {
                map.put(key, toMap((JSONObject) value));
            } else if (value instanceof JSONArray) {
                map.put(key, toList((JSONArray) value));
            } else {
                map.put(key, value);
            }
        }
        return map;
    }

    /**
     * Converts a JSONArray to a List.
     *
     * @param jsonArray The JSONArray to convert
     * @return A List representation of the JSONArray
     */
    private List<Object> toList(JSONArray jsonArray) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < jsonArray.length(); i++) {
            Object value = jsonArray.get(i);
            if (value instanceof JSONObject) {
                list.add(toMap((JSONObject) value));
            } else if (value instanceof JSONArray) {
                list.add(toList((JSONArray) value));
            } else {
                list.add(value);
            }
        }
        return list;
    }
}