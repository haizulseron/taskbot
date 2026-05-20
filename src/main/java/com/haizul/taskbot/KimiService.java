package com.haizul.taskbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible client for Moonshot's Kimi models. Used by the
 * {@code MEMORY_SUMMARIZATION} and {@code SUBCONSCIOUS} routing slots —
 * high-volume background workloads where Kimi is materially cheaper than
 * Anthropic's Haiku.
 *
 * Intentionally minimal: no streaming, no function-calling. Background slots
 * just need text-in / JSON-out.
 */
public class KimiService {

    private static final String API_URL = "https://api.moonshot.ai/v1/chat/completions";

    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public KimiService(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * One-shot completion. {@code system} is the system prompt; {@code user} is
     * the user message. Returns the assistant's text content, or null on error.
     * Caller is responsible for parsing JSON if the prompt asked for it.
     */
    public String complete(String model, String system, String user, int maxTokens) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_tokens", maxTokens);
            body.put("temperature", 0.3);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", system),
                    Map.of("role", "user",   "content", user)
            ));
            // K2.6 is a reasoning model — without this it burns the entire
            // max_tokens budget on internal thinking and emits empty content.
            // Background slots (memory summarization, subconscious) don't need
            // deep reasoning, just structured extraction.
            if (model != null && (model.contains("k2.6") || model.contains("reasoning"))) {
                body.put("reasoning_effort", "low");
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                System.err.println("[Kimi] HTTP " + res.statusCode() + ": " + truncate(res.body(), 400));
                return null;
            }
            JsonNode root = mapper.readTree(res.body());
            String finishReason = root.path("choices").path(0).path("finish_reason").asText("");
            if ("length".equals(finishReason)) {
                System.err.println("[Kimi] response truncated (finish_reason=length, model=" + model + ", max_tokens=" + maxTokens + ")");
                return null;
            }
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            System.err.println("[Kimi] complete(" + model + ") failed: " + e.getMessage());
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
