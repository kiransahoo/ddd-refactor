package com.ddd.refactor.agent;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * A "production-grade" style advanced agent that:
 * 1) Splits a legacy file into chunks.
 * 2) For each chunk (in parallel), retrieves domain context from a provided IDomainContextRetriever (vector DB or fallback).
 * 3) Does multi-turn GPT calls to ensure parseable Java code in "suggestedFix".
 * 4) If parse fails after max attempts, we embed the snippet as a comment fallback.
 * 5) Aggregates all chunk results into a final JSON.
 */
public class AdvancedRefactorAgent {

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    private final String openAiApiKey;
    private final String basePrompt;
    private final Path legacyFile;
    private final int maxRetries;
    private final int chunkSize;
    private final int parallelism;

    // Domain context retrieval strategy
    private final IDomainContextRetriever contextRetriever;

    public AdvancedRefactorAgent(String openAiApiKey,
                                        String basePrompt,
                                        Path legacyFile,
                                        int maxRetries,
                                        int chunkSize,
                                        int parallelism,
                                        IDomainContextRetriever contextRetriever) {
        this.openAiApiKey = openAiApiKey;
        this.basePrompt = basePrompt;
        this.legacyFile = legacyFile;
        this.maxRetries = maxRetries;
        this.chunkSize = chunkSize;
        this.parallelism = parallelism;
        this.contextRetriever = contextRetriever;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length < 2) {
            System.err.println("Usage: ProductionGradeRefactorAgent <OPENAI_API_KEY> <path/to/legacyFile> [maxRetries] [chunkSize] [parallelism]");
            System.exit(1);
        }
        String apiKey = args[0];
        Path file = Path.of(args[1]);
        int retries = (args.length >= 3) ? Integer.parseInt(args[2]) : 3;
        int cSize = (args.length >= 4) ? Integer.parseInt(args[3]) : 300;
        int par = (args.length >= 5) ? Integer.parseInt(args[4]) : 4;

        // Example domain context strategy:
        //  - We'll build a small in-memory vector DB with 1 or 2 domain code snippets
        //  - If e no real DB, fallback to a single snippet
        OpenAiEmbedder embedder = new OpenAiEmbedder();
        InMemoryVectorDb vdb = new InMemoryVectorDb();

        // Populate the in-memory DB with some aggregator snippet, etc.
        String aggSnippet = """
package com.myddd.framework;

public abstract class AbstractAggregate<ID> {
   protected ID id;
   public ID getId() { return id; }
}
@interface CommandHandler {}
""";
        float[] aggEmb = embedder.embed(aggSnippet);
        vdb.addSnippet(new DomainSnippet("agg", aggSnippet, aggEmb));

        // Alternatively, if we can't connect to a real DB, fallback:
        // VectorDb fallbackDb = new FallbackVectorDb(aggSnippet);

        // We'll just use the in-memory DB
        VectorDbContextRetriever retriever = new VectorDbContextRetriever(vdb, embedder);

        // Base prompt
        String prompt = """
You are an expert in Java, Spring Boot, Hexagonal Architecture, and advanced DDD frameworks.
Return EXACTLY one JSON:
{
  "violation": boolean,
  "reason": "...",
  "suggestedFix": "..."
}
If violation=true, suggestedFix MUST parse in JavaParser. ASCII quotes only, no enumerations.
""";

        AdvancedRefactorAgent agent = new AdvancedRefactorAgent(
                apiKey,
                prompt,
                file,
                retries,
                cSize,
                par,
                retriever
        );
        JSONObject finalJson = agent.runRefactoring();
        System.out.println("=== Final Aggregated JSON ===");
        System.out.println(finalJson.toString(2));
    }

    /**
     * Master method:
     * - chunkify file
     * - process each chunk in parallel
     * - gather final results in a JSON
     */
    public JSONObject runRefactoring() throws IOException, InterruptedException {
        List<String> lines = Files.readAllLines(legacyFile);
        List<List<String>> chunks = chunkify(lines, chunkSize);

        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        List<Future<JSONObject>> futures = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            final int chunkIndex = i;
            final List<String> chunk = chunks.get(i);
            futures.add(executor.submit(() -> processOneChunk(chunkIndex + 1, chunk)));
        }

        JSONArray results = new JSONArray();
        StringBuilder aggregatedFix = new StringBuilder();
        for (Future<JSONObject> f : futures) {
            try {
                JSONObject chunkResp = f.get();
                results.put(chunkResp);
                boolean violation = chunkResp.optBoolean("violation", false);
                if (violation) {
                    aggregatedFix.append("//--- fix for chunk ")
                            .append(chunkResp.optInt("chunkIndex", -1))
                            .append(" ---\n")
                            .append(chunkResp.optString("suggestedFix", ""))
                            .append("\n");
                } else {
                    aggregatedFix.append("//--- chunk ")
                            .append(chunkResp.optInt("chunkIndex", -1))
                            .append(" => no violation\n");
                }
            } catch (ExecutionException e) {
                System.err.println("Chunk future error => " + e.getCause());
            }
        }
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);

        JSONObject finalJson = new JSONObject();
        finalJson.put("file", legacyFile.toString());
        finalJson.put("chunksProcessed", chunks.size());
        finalJson.put("results", results);
        finalJson.put("aggregatedFix", aggregatedFix.toString());
        return finalJson;
    }

    /**
     * Process a single chunk:
     *  1) retrieve domain context
     *  2) multi-turn parse check
     *  3) fallback if parse fails
     */
    private JSONObject processOneChunk(int index, List<String> chunkLines) throws IOException, InterruptedException {
        String chunkText = String.join("\n", chunkLines);
        // retrieve up to 2 relevant domain snippets
        String domainContext = contextRetriever.retrieveContext(chunkText, 2);

        // build conversation
        List<JSONObject> miniConv = new ArrayList<>();
        miniConv.add(new JSONObject()
                .put("role", "system")
                .put("content", "You are an advanced DDD refactoring agent. Follow instructions strictly."));
        String userContent = basePrompt
                + "\n\n//=== Domain Code Snippets ===\n" + domainContext
                + "\n\n//=== Legacy Code Chunk ===\n" + chunkText;

        miniConv.add(new JSONObject()
                .put("role", "user")
                .put("content", userContent));

        JSONObject lastValid = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            JSONObject assistantMsg = callOpenAiChat(miniConv);
            if (assistantMsg == null) {
                // error from LLM
                miniConv.add(new JSONObject()
                        .put("role", "user")
                        .put("content", "LLM returned null, please produce valid JSON {\"violation\":..., \"reason\":..., \"suggestedFix\":...}"));
                continue;
            }
            String content = assistantMsg.optString("content", "{}");
            JSONObject parsed = extractJson(content);
            if (parsed == null) {
                miniConv.add(new JSONObject()
                        .put("role", "user")
                        .put("content", "Your response wasn't valid JSON. Return exactly one JSON object."));
                continue;
            }

            parsed.put("chunkIndex", index);
            boolean violation = parsed.optBoolean("violation", false);
            String fix = parsed.optString("suggestedFix", "");
            if (!violation || fix.isEmpty()) {
                lastValid = parsed;
                break;
            }
            // parse-check
            if (tryParseJava(fix)) {
                lastValid = parsed;
                break;
            } else {
                miniConv.add(new JSONObject()
                        .put("role", "user")
                        .put("content", "Your suggestedFix is not valid Java. Please correct syntax and ensure it can parse in JavaParser."));
            }
        }

        if (lastValid == null) {
            // fallback: embed snippet as comment
            JSONObject fallback = new JSONObject();
            fallback.put("violation", true);
            fallback.put("reason", "Max parse attempts reached, fallback comment only.");
            fallback.put("chunkIndex", index);
            // embed snippet
            fallback.put("suggestedFix",
                    "// fallback refactor, snippet unparseable\n/*\n" + chunkText + "\n*/");
            return fallback;
        }
        return lastValid;
    }

    /**
     * Calls GPT chat completions
     */
    private JSONObject callOpenAiChat(List<JSONObject> convo) throws IOException, InterruptedException {
        JSONObject reqBody = new JSONObject();
        reqBody.put("model", "gpt-4");
        JSONArray msgs = new JSONArray();
        for (JSONObject m : convo) {
            msgs.put(m);
        }
        reqBody.put("messages", msgs);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_URL))
                .header("Authorization", "Bearer " + openAiApiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(reqBody.toString()))
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            System.err.println("OpenAI call error => " + resp.statusCode() + ": " + resp.body());
            return null;
        }
        JSONObject full = new JSONObject(resp.body());
        JSONArray choices = full.optJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        return choices.getJSONObject(0).optJSONObject("message");
    }

    /**
     * Extract JSON from GPT content
     */
    private JSONObject extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start == -1 || end == -1 || end < start) {
            return null;
        }
        String maybeJson = content.substring(start, end + 1).trim();
        try {
            return new JSONObject(maybeJson);
        } catch (JSONException e) {
            System.err.println("extractJson error => " + e.getMessage());
            return null;
        }
    }

    private boolean tryParseJava(String code) {
        try {
            StaticJavaParser.parse(code);
            return true;
        } catch (Exception e) {
            System.err.println("tryParseJava => " + e.getMessage());
            return false;
        }
    }

    private List<List<String>> chunkify(List<String> lines, int size) {
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i += size) {
            out.add(lines.subList(i, Math.min(i + size, lines.size())));
        }
        return out;
    }
}
