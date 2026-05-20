package com.haizul.taskbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin HTTP wrapper around Composio's v3 REST API.
 *
 * Brokers Gmail, Google Drive, and Google Calendar through Composio's tool
 * execution endpoint rather than calling Google directly. One connected
 * Composio account per {@code userId} (entity) holds the OAuth tokens.
 *
 * The {@link Result} record carries either the raw JSON {@code data} block on
 * success or an {@code ERROR_*}-prefixed message — same convention the rest of
 * ClaudeService uses, so the phantom-action detector and action_log work the
 * same as for hand-rolled tools.
 */
public class ComposioService {

    private static final String BASE_URL = "https://backend.composio.dev/api/v3";

    private final String apiKey;
    private final String userId;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public ComposioService(String apiKey, String userId) {
        this.apiKey = apiKey;
        this.userId = (userId == null || userId.isBlank()) ? "default" : userId;
    }

    /** Successful result carries {@code data}; failure carries {@code error}. */
    public record Result(boolean successful, JsonNode data, String error) {
        public boolean isError() { return !successful || error != null; }
    }

    /**
     * Execute a Composio action by its slug (e.g. {@code GMAIL_FETCH_EMAILS}).
     * Arguments are passed through verbatim; the {@code user_id} is injected.
     */
    public Result execute(String slug, Map<String, Object> arguments) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("user_id", userId);
            body.put("arguments", arguments == null ? Map.of() : arguments);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/tools/execute/" + slug))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .timeout(Duration.ofSeconds(45))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() == 401 || res.statusCode() == 403) {
                return new Result(false, null,
                        "ERROR_AUTH_FAILED: Composio rejected the request (HTTP " + res.statusCode() + "). "
                      + "The connected account may have been disconnected. "
                      + "Tell the user: \"My access to that integration expired — please reconnect it in Composio.\" "
                      + "Do NOT say the action succeeded.");
            }
            if (res.statusCode() == 429) {
                return new Result(false, null,
                        "ERROR_TRANSIENT: Composio rate-limited the request. Retry in a moment.");
            }
            if (res.statusCode() >= 500) {
                return new Result(false, null,
                        "ERROR_TRANSIENT: Composio HTTP " + res.statusCode() + ". Try again.");
            }

            JsonNode root = mapper.readTree(res.body());
            boolean ok = root.path("successful").asBoolean(res.statusCode() == 200);
            JsonNode errNode = root.path("error");

            if (!ok || (errNode != null && !errNode.isNull() && !errNode.asText("").isBlank())) {
                String errText = errNode == null ? "" : errNode.asText("");
                String low = errText.toLowerCase(java.util.Locale.ROOT);
                String prefix;
                if (low.contains("not found") || low.contains("404")) {
                    prefix = "ERROR_NOT_FOUND: ";
                } else if (low.contains("invalid") || low.contains("required") || low.contains("must be")) {
                    prefix = "ERROR_BAD_INPUT: ";
                } else if (low.contains("auth") || low.contains("token") || low.contains("permission")) {
                    prefix = "ERROR_AUTH_FAILED: ";
                } else {
                    prefix = "ERROR_TOOL: ";
                }
                return new Result(false, root.path("data"), prefix + slug + " — " + safe(errText));
            }

            return new Result(true, root.path("data"), null);
        } catch (java.net.http.HttpTimeoutException e) {
            return new Result(false, null, "ERROR_TRANSIENT: Composio call timed out.");
        } catch (Exception e) {
            System.err.println("ComposioService.execute(" + slug + ") failed: " + e.getMessage());
            return new Result(false, null, "ERROR_TOOL: " + slug + " — " + safe(e.getMessage()));
        }
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.length() > 240 ? s.substring(0, 240) + "..." : s;
    }
}
