package com.haizul.taskbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

public class NoteService {

    public record StructuredNote(
            String title,
            String category,
            List<String> tags,
            String summary
    ) {}

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL   = ModelRouting.REFLECTIONS;

    private static final String SYSTEM_PROMPT = """
            You are a note structuring assistant. The user sends a raw note (text or voice transcript).
            Analyse it and return ONLY a JSON object — no markdown, no backticks.

            JSON fields:
            - title       : short, clear title for this note (max 10 words)
            - category    : ONE of: School, Work, Ideas, Personal, Health, Finance, Reference, Other
            - tags        : array of 2-5 relevant lowercase tags (e.g. ["jacob","birthday","may"])
            - summary     : 2-4 sentence structured summary. If the note has key facts (dates, names, numbers), preserve them exactly.

            Rules:
            - Pick the most specific category that fits
            - Tags should be nouns, names, topics — not generic words like "note" or "remember"
            - Summary should read as a clean, useful reference — not "the user said..."
            - If it's a person's detail (birthday, contact, preference), make sure it's in the summary clearly
            - If it's an idea, summarise the idea and any context given
            - Keep title concise and searchable
            """;

    private static final String SEARCH_PROMPT = """
            You are a note search assistant. The user asks a natural language question about their notes.
            Extract search parameters and return ONLY a JSON object — no markdown, no backticks.

            JSON fields:
            - query          : 1-3 key nouns/names to search for (string). Extract the core subject — e.g. "when is my MRI" → "MRI", "Jacob's birthday" → "Jacob", "CEE assignment" → "CEE". Keep it short and specific.
            - category_filter: ONLY set this if the user explicitly mentions a category like "my school notes" or "health stuff". Otherwise leave null.
            - limit          : number of results to fetch (1-5, default 3)
            - is_recent      : true only if user says "recent", "last notes", "what did I save"

            Examples:
            "when is jacob's birthday" → query:"jacob", category_filter:null
            "what is my MRI appointment date" → query:"MRI", category_filter:null
            "what ideas do I have for Cassia" → query:"cassia", category_filter:null
            "show me my health notes" → query:null, category_filter:"Health"
            "show me my recent notes" → is_recent:true, query:null
            "anything about CEE" → query:"CEE", category_filter:null

            Important: do NOT set category_filter unless the user explicitly asks for a category.
            """;

    private final String apiKey;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public NoteService(String apiKey) {
        this.apiKey  = apiKey;
        this.http    = HttpClient.newHttpClient();
        this.mapper  = new ObjectMapper();
    }

    public StructuredNote structure(String rawText) {
        try {
            String response = callApi(rawText, SYSTEM_PROMPT, 300);
            String clean    = stripFences(response);
            JsonNode p      = mapper.readTree(clean);

            String title    = p.path("title").asText("Quick Note");
            String category = p.path("category").asText("Other");
            String summary  = p.path("summary").asText(rawText);

            List<String> tags = new ArrayList<>();
            for (JsonNode tag : p.path("tags")) tags.add(tag.asText());

            return new StructuredNote(title, category, tags, summary);
        } catch (Exception e) {
            System.err.println("NoteService.structure error: " + e.getMessage());
            // Fallback — save as-is with minimal structure
            return new StructuredNote(
                    truncate(rawText, 60),
                    "Other",
                    List.of(),
                    rawText
            );
        }
    }

    public record NoteSearchParams(String query, String categoryFilter, int limit, boolean isRecent) {}

    public NoteSearchParams parseSearchQuery(String userMessage) {
        try {
            String response = callApi(userMessage, SEARCH_PROMPT, 150);
            String clean    = stripFences(response);
            JsonNode p      = mapper.readTree(clean);

            String query    = nb(p.path("query"));
            String catFilter = nb(p.path("category_filter"));
            int limit       = p.path("limit").asInt(3);
            boolean recent  = p.path("is_recent").asBoolean(false);

            return new NoteSearchParams(query, catFilter, Math.min(limit, 5), recent);
        } catch (Exception e) {
            System.err.println("NoteService.parseSearchQuery error: " + e.getMessage());
            return new NoteSearchParams(userMessage, null, 3, false);
        }
    }

    private String callApi(String userMessage, String systemPrompt, int maxTokens) throws Exception {
        String body = mapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
            put("model", MODEL);
            put("max_tokens", maxTokens);
            put("system", systemPrompt);
            put("messages", List.of(java.util.Map.of("role", "user", "content", userMessage)));
        }});

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new RuntimeException("API error " + res.statusCode());

        JsonNode root = mapper.readTree(res.body());
        return root.path("content").get(0).path("text").asText("").trim();
    }

    private static String stripFences(String s) {
        return s.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("(?s)```\\s*$", "").trim();
    }

    private static String nb(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        String s = n.asText(null);
        return (s == null || s.isBlank() || s.equals("null")) ? null : s.trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
