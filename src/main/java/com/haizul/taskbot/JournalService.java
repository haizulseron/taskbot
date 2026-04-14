package com.haizul.taskbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;

public class JournalService {

    private static final String BASE_URL       = "https://api.notion.com/v1";
    private static final String NOTION_VERSION = "2022-06-28";
    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String CLAUDE_MODEL   = "claude-haiku-4-5-20251001";

    private final String notionApiKey;
    private final String journalDatabaseId;
    private final String claudeApiKey;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public record JournalResult(String title, String type, List<String> tags, String date) {}

    public JournalService(String notionApiKey, String journalDatabaseId, String claudeApiKey) {
        this.notionApiKey      = notionApiKey;
        this.journalDatabaseId = normalizeNotionId(journalDatabaseId);
        this.claudeApiKey      = claudeApiKey;
        this.http              = HttpClient.newHttpClient();
        this.mapper            = new ObjectMapper();
    }

    /**
     * Converts Notion IDs to the standard UUID format the API expects.
     * Handles both dash-less hex IDs and the newer ntn_ prefix format.
     */
    private static String normalizeNotionId(String id) {
        if (id == null) return null;
        String clean = id.trim();

        // Already a valid UUID with dashes
        if (clean.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            return clean;
        }

        // Strip ntn_ prefix if present — the remaining chars are a base62-ish encoding.
        // Notion also accepts the raw 32-char hex (no dashes) so strip prefix and try that.
        if (clean.startsWith("ntn_")) {
            clean = clean.substring(4);
        }

        // 32-char hex string without dashes → insert dashes (8-4-4-4-12)
        if (clean.matches("[0-9a-fA-F]{32}")) {
            return clean.substring(0, 8) + "-" + clean.substring(8, 12) + "-"
                    + clean.substring(12, 16) + "-" + clean.substring(16, 20) + "-"
                    + clean.substring(20);
        }

        // Can't normalize — return as-is and let the API error surface
        return id.trim();
    }

    // ── Public ────────────────────────────────────────────────────────────────

    public JournalResult saveJournal(String content, Integer mood, Integer energy, ZoneId zoneId) {
        try {
            LocalDateTime now = LocalDateTime.now(zoneId);
            String date       = now.toLocalDate().toString(); // ISO date yyyy-MM-dd
            String title      = "Journal \u2013 " + now.format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm"));
            String type       = determineType(now);
            int weekNumber    = now.get(WeekFields.ISO.weekOfWeekBasedYear());
            List<String> tags = suggestTags(content);

            ObjectNode body = mapper.createObjectNode();

            // Parent
            ObjectNode parent = mapper.createObjectNode();
            parent.put("database_id", journalDatabaseId);
            body.set("parent", parent);

            // Properties
            ObjectNode props = mapper.createObjectNode();

            // Title (title)
            props.set("Title", richTextProp("title", title));

            // Date (date)
            ObjectNode dateProp = mapper.createObjectNode();
            ObjectNode dateVal  = mapper.createObjectNode();
            dateVal.put("start", date);
            dateProp.set("date", dateVal);
            props.set("Date", dateProp);

            // Content (rich_text) — truncated to 2000 chars
            props.set("Content", richTextProp("rich_text", truncate(content, 2000)));

            // Mood (number)
            ObjectNode moodProp = mapper.createObjectNode();
            if (mood != null) {
                moodProp.put("number", mood);
            } else {
                moodProp.putNull("number");
            }
            props.set("Mood", moodProp);

            // Energy (number)
            ObjectNode energyProp = mapper.createObjectNode();
            if (energy != null) {
                energyProp.put("number", energy);
            } else {
                energyProp.putNull("number");
            }
            props.set("Energy", energyProp);

            // Tags (multi_select)
            ObjectNode tagsProp = mapper.createObjectNode();
            ArrayNode tagsArr   = mapper.createArrayNode();
            for (String tag : tags) {
                ObjectNode t = mapper.createObjectNode();
                t.put("name", tag.toLowerCase().trim());
                tagsArr.add(t);
            }
            tagsProp.set("multi_select", tagsArr);
            props.set("Tags", tagsProp);

            // Type (select)
            ObjectNode typeProp = mapper.createObjectNode();
            ObjectNode typeVal  = mapper.createObjectNode();
            typeVal.put("name", type);
            typeProp.set("select", typeVal);
            props.set("Type", typeProp);

            // Week Number (number)
            ObjectNode weekProp = mapper.createObjectNode();
            weekProp.put("number", weekNumber);
            props.set("Week Number", weekProp);

            body.set("properties", props);

            // Children — full content as paragraph block
            ArrayNode children = mapper.createArrayNode();
            ObjectNode block = mapper.createObjectNode();
            block.put("object", "block");
            block.put("type", "paragraph");
            ObjectNode para = mapper.createObjectNode();
            ArrayNode richArr = mapper.createArrayNode();
            ObjectNode richItem = mapper.createObjectNode();
            richItem.put("type", "text");
            ObjectNode textNode = mapper.createObjectNode();
            textNode.put("content", truncate(content, 2000));
            richItem.set("text", textNode);
            richArr.add(richItem);
            para.set("rich_text", richArr);
            block.set("paragraph", para);
            children.add(block);
            body.set("children", children);

            String response = post("/pages", body.toString());
            // Verify response is valid
            mapper.readTree(response);

            return new JournalResult(title, type, tags, date);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save journal to Notion: " + e.getMessage(), e);
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private List<String> suggestTags(String content) {
        try {
            String systemPrompt = "Suggest 1-3 short tags (lowercase, single words or hyphenated) for this journal entry. Return only a JSON array of strings.";

            String body = mapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
                put("model", CLAUDE_MODEL);
                put("max_tokens", 150);
                put("system", systemPrompt);
                put("messages", List.of(java.util.Map.of("role", "user", "content", content)));
            }});

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(CLAUDE_API_URL))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", claudeApiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new RuntimeException("Claude API error " + res.statusCode());

            JsonNode root = mapper.readTree(res.body());
            String text   = root.path("content").get(0).path("text").asText("").trim();
            String clean  = stripFences(text);
            JsonNode arr  = mapper.readTree(clean);

            List<String> tags = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode tag : arr) tags.add(tag.asText());
            }
            return tags;
        } catch (Exception e) {
            System.err.println("JournalService.suggestTags error: " + e.getMessage());
            return List.of();
        }
    }

    private String determineType(LocalDateTime now) {
        int hour = now.getHour();
        if (hour < 12) return "Morning";
        if (hour >= 18) return "Evening";
        return "Random";
    }

    private ObjectNode richTextProp(String type, String content) {
        ObjectNode prop = mapper.createObjectNode();
        if (type.equals("title")) {
            ArrayNode arr = mapper.createArrayNode();
            ObjectNode item = mapper.createObjectNode();
            item.put("type", "text");
            ObjectNode text = mapper.createObjectNode();
            text.put("content", content != null ? content : "");
            item.set("text", text);
            arr.add(item);
            prop.set("title", arr);
        } else {
            ArrayNode arr = mapper.createArrayNode();
            ObjectNode item = mapper.createObjectNode();
            item.put("type", "text");
            ObjectNode text = mapper.createObjectNode();
            text.put("content", content != null ? content : "");
            item.set("text", text);
            arr.add(item);
            prop.set("rich_text", arr);
        }
        return prop;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String stripFences(String s) {
        return s.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("(?s)```\\s*$", "").trim();
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private String post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("Authorization", "Bearer " + notionApiKey)
                .header("Content-Type", "application/json")
                .header("Notion-Version", NOTION_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 400) {
            throw new RuntimeException("Notion API error " + res.statusCode() + ": " + res.body());
        }
        return res.body();
    }
}
