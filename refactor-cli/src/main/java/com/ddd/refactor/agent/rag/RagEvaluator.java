package com.ddd.refactor.agent.rag;

import com.ddd.refactor.agent.embedding.EmbeddingService;
import com.ddd.refactor.agent.vectordb.VectorDbService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility for evaluating and logging RAG quality and performance.
 */
public class RagEvaluator {

    private static final Logger logger = LoggerFactory.getLogger(RagEvaluator.class);

    private final RagService ragService;
    private final EmbeddingService embeddingService;
    private final VectorDbService vectorDbService;
    private final Path evaluationOutputDir;

    /**
     * Constructs a RagEvaluator with the specified services.
     *
     * @param ragService The RAG service to evaluate
     * @param embeddingService The embedding service used by the RAG service
     * @param vectorDbService The vector database service used by the RAG service
     * @param evaluationOutputDir The directory to write evaluation results to
     */
    public RagEvaluator(RagService ragService, EmbeddingService embeddingService,
                        VectorDbService vectorDbService, Path evaluationOutputDir) {
        this.ragService = ragService;
        this.embeddingService = embeddingService;
        this.vectorDbService = vectorDbService;
        this.evaluationOutputDir = evaluationOutputDir;

        // Create output directory if it doesn't exist
        try {
            Files.createDirectories(evaluationOutputDir);
        } catch (IOException e) {
            logger.error("Error creating evaluation output directory: {}", e.getMessage(), e);
        }
    }

    /**
     * Evaluate the RAG system using a set of test queries.
     *
     * @param testQueries List of test queries
     * @param expectedKeywords Map of queries to expected keywords in the results
     * @return A summary of the evaluation results
     */
    public Map<String, Object> evaluateWithQueries(List<String> testQueries,
                                                   Map<String, List<String>> expectedKeywords) {
        if (ragService == null || embeddingService == null || vectorDbService == null) {
            logger.error("Cannot evaluate RAG - one or more required services are null");
            return Collections.emptyMap();
        }

        Map<String, Object> results = new HashMap<>();
        List<Map<String, Object>> queryResults = new ArrayList<>();
        double totalRelevanceScore = 0.0;
        int totalHits = 0;

        for (String query : testQueries) {
            Map<String, Object> queryResult = evaluateQuery(query, expectedKeywords.get(query));
            queryResults.add(queryResult);

            Double relevanceScore = (Double) queryResult.get("relevance_score");
            if (relevanceScore != null) {
                totalRelevanceScore += relevanceScore;
            }

            Integer hits = (Integer) queryResult.get("keyword_hits");
            if (hits != null) {
                totalHits += hits;
            }
        }

        // Calculate aggregate metrics
        int totalQueries = testQueries.size();
        double avgRelevanceScore = totalQueries > 0 ? totalRelevanceScore / totalQueries : 0.0;
        double avgKeywordHits = totalQueries > 0 ? (double) totalHits / totalQueries : 0.0;
        double keywordHitRate = totalQueries > 0 ?
                (double) queryResults.stream()
                        .filter(r -> (Integer) r.getOrDefault("keyword_hits", 0) > 0)
                        .count() / totalQueries : 0.0;

        // Build summary results
        results.put("timestamp", LocalDateTime.now().toString());
        results.put("total_queries", totalQueries);
        results.put("avg_relevance_score", avgRelevanceScore);
        results.put("avg_keyword_hits", avgKeywordHits);
        results.put("keyword_hit_rate", keywordHitRate);
        results.put("query_results", queryResults);

        // Log summary
        logger.info("RAG Evaluation Summary:");
        logger.info("  Total Queries: {}", totalQueries);
        logger.info("  Average Relevance Score: {}", avgRelevanceScore);
        logger.info("  Average Keyword Hits: {}", avgKeywordHits);
        logger.info("  Keyword Hit Rate: {}", keywordHitRate);

        // Save results to file
        saveEvaluationResults(results);

        return results;
    }

    /**
     * Evaluate a single query.
     *
     * @param query The query to evaluate
     * @param expectedKeywords List of expected keywords in the results
     * @return A map containing evaluation metrics for the query
     */
    private Map<String, Object> evaluateQuery(String query, List<String> expectedKeywords) {
        Map<String, Object> result = new HashMap<>();
        result.put("query", query);

        long startTime = System.currentTimeMillis();

        try {
            // Generate embedding
            List<Float> queryEmbedding = embeddingService.generateEmbedding(query);
            long embeddingTime = System.currentTimeMillis() - startTime;

            // Search vector DB
            long searchStartTime = System.currentTimeMillis();
            List<Map<String, Object>> searchResults = vectorDbService.search(queryEmbedding, 5);
            long searchTime = System.currentTimeMillis() - searchStartTime;

            // Extract results
            List<Map<String, Object>> formattedResults = new ArrayList<>();
            double topScore = 0.0;

            if (!searchResults.isEmpty()) {
                topScore = (Double) searchResults.get(0).getOrDefault("score", 0.0);
            }

            for (Map<String, Object> searchResult : searchResults) {
                Map<String, Object> formattedResult = new HashMap<>();
                formattedResult.put("id", searchResult.get("id"));
                formattedResult.put("score", searchResult.get("score"));

                Map<String, Object> metadata = (Map<String, Object>) searchResult.get("metadata");
                if (metadata != null) {
                    formattedResult.put("title", metadata.getOrDefault("title", "Untitled"));
                    String content = (String) metadata.getOrDefault("content", "");

                    // Truncate content for result output
                    if (content.length() > 200) {
                        formattedResult.put("content_excerpt", content.substring(0, 200) + "...");
                    } else {
                        formattedResult.put("content_excerpt", content);
                    }

                    // Check for keyword matches
                    int keywordHits = 0;
                    if (expectedKeywords != null && !expectedKeywords.isEmpty()) {
                        for (String keyword : expectedKeywords) {
                            if (content.toLowerCase().contains(keyword.toLowerCase())) {
                                keywordHits++;
                            }
                        }
                    }

                    formattedResult.put("keyword_hits", keywordHits);
                }

                formattedResults.add(formattedResult);
            }

            // Compute total keyword hits
            int totalKeywordHits = formattedResults.stream()
                    .mapToInt(r -> (Integer) r.getOrDefault("keyword_hits", 0))
                    .sum();

            // Calculate elapsed time
            long totalTime = System.currentTimeMillis() - startTime;

            // Add results to output
            result.put("embedding_time_ms", embeddingTime);
            result.put("search_time_ms", searchTime);
            result.put("total_time_ms", totalTime);
            result.put("results_count", searchResults.size());
            result.put("top_score", topScore);
            result.put("relevance_score", topScore); // Use top score as relevance for now
            result.put("expected_keywords", expectedKeywords);
            result.put("keyword_hits", totalKeywordHits);
            result.put("results", formattedResults);

            logger.debug("Query: '{}', Results: {}, Top Score: {}, Keyword Hits: {}",
                    query, searchResults.size(), topScore, totalKeywordHits);

        } catch (Exception e) {
            logger.error("Error evaluating query '{}': {}", query, e.getMessage(), e);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Save evaluation results to a file.
     *
     * @param results The evaluation results to save
     */
    private void saveEvaluationResults(Map<String, Object> results) {
        if (evaluationOutputDir == null) {
            logger.warn("No output directory specified for evaluation results");
            return;
        }

        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path outputFile = evaluationOutputDir.resolve("rag_eval_" + timestamp + ".json");

            try (FileWriter writer = new FileWriter(outputFile.toFile())) {
                // Convert results to JSON
                writer.write(mapToJson(results));
            }

            logger.info("Saved evaluation results to {}", outputFile);
        } catch (IOException e) {
            logger.error("Error saving evaluation results: {}", e.getMessage(), e);
        }
    }

    /**
     * Convert a map to a JSON string.
     *
     * @param map The map to convert
     * @return The JSON string
     */
    private String mapToJson(Map<String, Object> map) {
        StringBuilder json = new StringBuilder("{\n");

        for (Iterator<Map.Entry<String, Object>> it = map.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Object> entry = it.next();
            json.append("  \"").append(entry.getKey()).append("\": ");
            json.append(valueToJson(entry.getValue()));

            if (it.hasNext()) {
                json.append(",");
            }
            json.append("\n");
        }

        json.append("}");
        return json.toString();
    }

    /**
     * Convert a value to a JSON string.
     *
     * @param value The value to convert
     * @return The JSON string
     */
    @SuppressWarnings("unchecked")
    private String valueToJson(Object value) {
        if (value == null) {
            return "null";
        } else if (value instanceof String) {
            return "\"" + ((String) value).replace("\"", "\\\"") + "\"";
        } else if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        } else if (value instanceof Map) {
            return mapToJson((Map<String, Object>) value);
        } else if (value instanceof List) {
            return listToJson((List<Object>) value);
        } else {
            return "\"" + value.toString() + "\"";
        }
    }

    /**
     * Convert a list to a JSON string.
     *
     * @param list The list to convert
     * @return The JSON string
     */
    private String listToJson(List<Object> list) {
        StringBuilder json = new StringBuilder("[\n");

        for (Iterator<Object> it = list.iterator(); it.hasNext();) {
            Object item = it.next();
            json.append("    ").append(valueToJson(item));

            if (it.hasNext()) {
                json.append(",");
            }
            json.append("\n");
        }

        json.append("  ]");
        return json.toString();
    }

    /**
     * Evaluate the embedding quality by measuring how well similar documents cluster together.
     *
     * @param documentGroups Map of group names to lists of document IDs that should be similar
     * @return A summary of the embedding quality evaluation
     */
    public Map<String, Object> evaluateEmbeddingQuality(Map<String, List<String>> documentGroups) {
        if (embeddingService == null || vectorDbService == null) {
            logger.error("Cannot evaluate embedding quality - required services are null");
            return Collections.emptyMap();
        }

        Map<String, Object> results = new HashMap<>();
        List<Map<String, Object>> groupResults = new ArrayList<>();

        for (Map.Entry<String, List<String>> entry : documentGroups.entrySet()) {
            String groupName = entry.getKey();
            List<String> documentIds = entry.getValue();

            Map<String, Object> groupResult = evaluateDocumentGroup(groupName, documentIds);
            groupResults.add(groupResult);
        }

        // Calculate aggregate metrics
        double avgIntraGroupSimilarity = groupResults.stream()
                .mapToDouble(r -> (Double) r.getOrDefault("avg_intra_group_similarity", 0.0))
                .average()
                .orElse(0.0);

        double avgInterGroupSimilarity = groupResults.stream()
                .mapToDouble(r -> (Double) r.getOrDefault("avg_inter_group_similarity", 0.0))
                .average()
                .orElse(0.0);

        // Build summary results
        results.put("timestamp", LocalDateTime.now().toString());
        results.put("embedding_model", embeddingService.getModelName());
        results.put("embedding_dimension", embeddingService.getEmbeddingDimension());
        results.put("avg_intra_group_similarity", avgIntraGroupSimilarity);
        results.put("avg_inter_group_similarity", avgInterGroupSimilarity);
        results.put("group_results", groupResults);

        // Log summary
        logger.info("Embedding Quality Evaluation Summary:");
        logger.info("  Embedding Model: {}", embeddingService.getModelName());
        logger.info("  Average Intra-Group Similarity: {}", avgIntraGroupSimilarity);
        logger.info("  Average Inter-Group Similarity: {}", avgInterGroupSimilarity);

        return results;
    }

    /**
     * Evaluate the similarity of documents within a group.
     *
     * @param groupName The name of the group
     * @param documentIds List of document IDs in the group
     * @return A map containing evaluation metrics for the group
     */
    private Map<String, Object> evaluateDocumentGroup(String groupName, List<String> documentIds) {
        Map<String, Object> result = new HashMap<>();
        result.put("group_name", groupName);
        result.put("document_count", documentIds.size());

        List<List<Float>> embeddings = new ArrayList<>();
        List<String> retrievedDocumentIds = new ArrayList<>();

        // Retrieve documents and their embeddings
        for (String documentId : documentIds) {
            Map<String, Object> document = vectorDbService.getById(documentId);
            if (document.isEmpty()) {
                logger.warn("Document not found: {}", documentId);
                continue;
            }

            List<Float> embedding = (List<Float>) document.get("vector");
            if (embedding == null || embedding.isEmpty()) {
                logger.warn("No embedding found for document: {}", documentId);
                continue;
            }

            embeddings.add(embedding);
            retrievedDocumentIds.add(documentId);
        }

        // Calculate pairwise similarities within the group
        List<Double> similarities = new ArrayList<>();
        double totalSimilarity = 0.0;

        for (int i = 0; i < embeddings.size(); i++) {
            for (int j = i + 1; j < embeddings.size(); j++) {
                double similarity = cosineSimilarity(embeddings.get(i), embeddings.get(j));
                similarities.add(similarity);
                totalSimilarity += similarity;
            }
        }

        double avgSimilarity = similarities.isEmpty() ? 0.0 : totalSimilarity / similarities.size();

        result.put("retrieved_documents", retrievedDocumentIds.size());
        result.put("similarity_pairs", similarities.size());
        result.put("avg_intra_group_similarity", avgSimilarity);

        logger.debug("Group: '{}', Documents: {}, Avg Similarity: {}",
                groupName, retrievedDocumentIds.size(), avgSimilarity);

        return result;
    }

    /**
     * Calculate cosine similarity between two vectors.
     *
     * @param v1 First vector
     * @param v2 Second vector
     * @return Cosine similarity
     */
    private double cosineSimilarity(List<Float> v1, List<Float> v2) {
        if (v1.size() != v2.size()) {
            // Vectors must be of same dimension
            int minSize = Math.min(v1.size(), v2.size());
            v1 = v1.subList(0, minSize);
            v2 = v2.subList(0, minSize);
        }

        double dot = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < v1.size(); i++) {
            dot += v1.get(i) * v2.get(i);
            norm1 += v1.get(i) * v1.get(i);
            norm2 += v2.get(i) * v2.get(i);
        }

        // Avoid division by zero
        if (norm1 == 0 || norm2 == 0) {
            return 0.0;
        }

        return dot / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}