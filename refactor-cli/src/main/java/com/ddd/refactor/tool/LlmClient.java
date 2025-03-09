package com.ddd.refactor.tool;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Properties;

/**
 * Minimal client that calls OpenAI's ChatCompletions API,
 * loading the OpenAI API key from "RefactorConfig.properties" under /src/main/resources.
 *
 * Expects the prompt to produce JSON with "violation", "reason", "suggestedFix".
 * TODO -Cleanup - Use Log4j or so - but since cli util this sould be ok for now
 * @author kiransahoo
 */
public class LlmClient {

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    // We'll load the API key from "/RefactorConfig.properties" on the classpath.
    private static String openAiApiKey;

    static {
        // Attempt to load the key from /RefactorConfig.properties on the classpath
        try (InputStream in = LlmClient.class.getResourceAsStream("/RefactorConfig.properties")) {
//            if (in == null) {
//                System.err.println("LlmClient: Could not find 'RefactorConfig.properties' on classpath.");
//                //return;
//            }
            Properties props = new Properties();
            props.load(in);
            openAiApiKey = props.getProperty("openAiApiKey");
            if (openAiApiKey == null || openAiApiKey.isBlank()) {
                System.err.println("LlmClient: 'openAiApiKey' not found in RefactorConfig.properties.");
            } else {
                System.out.println("LlmClient: Successfully loaded OpenAI API key from RefactorConfig.properties.");
            }
        } catch (IOException e) {
            System.err.println("LlmClient: Error loading 'RefactorConfig.properties' => " + e.getMessage());
        }
    }

    /**
     * Escapes special characters so the prompt remains valid JSON.
     */
    public static String safeForJson(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")   // Escape backslash => we want "\\"
                .replace("\"", "\\\"")   // Escape double quotes => \"
                .replace("\r", "\\r")    // Convert line breaks to \r, \n
                .replace("\n", "\\n");
    }

    /**
     * Calls the OpenAI ChatCompletion API using the loaded openAiApiKey.
     *
     * @param userPrompt The user prompt text to send to GPT.
     * @return A JSONObject with "violation", "reason", and "suggestedFix" if found; otherwise null.
     */
    public static JSONObject callLlm(String userPrompt) {
        if (openAiApiKey == null || openAiApiKey.isBlank()) {
            System.err.println("LlmClient: No valid OpenAI API key found. Skipping LLM call.");
            return null;
        }

        try {
            // 1) Sanitize user prompt for JSON
            String safePrompt = safeForJson(userPrompt);

            // 2) Build request JSON
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", "gpt-4"); // or "gpt-3.5-turbo"

            JSONArray messages = new JSONArray();
            messages.put(new JSONObject()
                    .put("role", "user")
                    .put("content", safePrompt));
            requestBody.put("messages", messages);

            // 3) Execute POST to OpenAI
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OPENAI_URL))
                    .header("Authorization", "Bearer " + openAiApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            int statusCode = response.statusCode();
            String body = response.body();

            // 4) Check HTTP status
            if (statusCode < 200 || statusCode >= 300) {
                System.err.println("LLM call failed. status=" + statusCode + ", body=" + body);
                return null;
            }

            // 5) Parse the JSON response
            JSONObject fullResp = new JSONObject(body);
            JSONArray choices = fullResp.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                System.err.println("No choices returned in LLM response.");
                return null;
            }

            JSONObject firstChoice = choices.getJSONObject(0);
            JSONObject messageObj = firstChoice.optJSONObject("message");
            if (messageObj == null) {
                System.err.println("No 'message' object in LLM response choices[0].");
                return null;
            }

            // 6) Extract the assistant text; we expect a JSON blob embedded in "content"
            String content = messageObj.optString("content", "{}");

            // 7) Attempt to parse out *only* the valid JSON portion from the first '{' to the last '}'
            int firstBrace = content.indexOf('{');
            int lastBrace = content.lastIndexOf('}');
            if (firstBrace == -1 || lastBrace == -1 || lastBrace <= firstBrace) {
                System.err.println("Could not find a valid JSON object in LLM response:\n" + content);
                return null;
            }

            String maybeJson = content.substring(firstBrace, lastBrace + 1).trim();

            // 8) Now parse into a JSONObject
            return new JSONObject(maybeJson);

        } catch (Exception e) {
            System.err.println("Error calling LLM => " + e.getMessage());
            return null;
        }
    }
}
