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
import java.util.ArrayList;
import java.util.List;

public class NotionService {

    private static final String BASE_URL      = "https://api.notion.com/v1";
    private static final String NOTION_VERSION = "2022-06-28";

    private final String apiKey;
    private final String databaseId;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public record SavedNote(String pageId, String title, String category, List<String> tags) {}

    public NotionService(String apiKey, String databaseId) {
        this.apiKey     = apiKey;
        this.databaseId = databaseId;
        this.http       = HttpClient.newHttpClient();
        this.mapper     = new ObjectMapper();
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    /**
     * Creates the Quick Notes database as a child of the given parent page.
     * Returns the new database ID.
     */
    public String createDatabase(String parentPageId) {
        try {
            ObjectNode body = mapper.createObjectNode();
            ObjectNode parent = mapper.createObjectNode();
            parent.put("type", "page_id");
            parent.put("page_id", parentPageId);
            body.set("parent", parent);

            ObjectNode titleProp = mapper.createObjectNode();
            ArrayNode titleArr = mapper.createArrayNode();
            ObjectNode titleText = mapper.createObjectNode();
            ObjectNode textObj = mapper.createObjectNode();
            textObj.put("content", "Quick Notes");
            titleText.put("type", "text");
            titleText.set("text", textObj);
            titleArr.add(titleText);
            titleProp.set("title", titleArr);
            body.set("title", titleArr);

            // Properties
            ObjectNode props = mapper.createObjectNode();

            // Title (built-in)
            props.set("Title", mapper.createObjectNode().set("title", mapper.createObjectNode()));

            // Category - select
            ObjectNode catProp = mapper.createObjectNode();
            ObjectNode catSelect = mapper.createObjectNode();
            ArrayNode catOptions = mapper.createArrayNode();
            for (String cat : List.of("School","Work","Ideas","Personal","Health","Finance","Reference","Other")) {
                ObjectNode opt = mapper.createObjectNode();
                opt.put("name", cat);
                catOptions.add(opt);
            }
            catSelect.set("options", catOptions);
            catProp.set("select", catSelect);
            props.set("Category", catProp);

            // Tags - multi_select
            ObjectNode tagProp = mapper.createObjectNode();
            tagProp.set("multi_select", mapper.createObjectNode());
            props.set("Tags", tagProp);

            // Raw - rich_text
            ObjectNode rawProp = mapper.createObjectNode();
            rawProp.set("rich_text", mapper.createObjectNode());
            props.set("Raw", rawProp);

            // Summary - rich_text
            ObjectNode sumProp = mapper.createObjectNode();
            sumProp.set("rich_text", mapper.createObjectNode());
            props.set("Summary", sumProp);

            // Source - select
            ObjectNode srcProp = mapper.createObjectNode();
            ObjectNode srcSelect = mapper.createObjectNode();
            ArrayNode srcOptions = mapper.createArrayNode();
            for (String src : List.of("text","voice")) {
                ObjectNode opt = mapper.createObjectNode();
                opt.put("name", src);
                srcOptions.add(opt);
            }
            srcSelect.set("options", srcOptions);
            srcProp.set("select", srcSelect);
            props.set("Source", srcProp);

            // Created - date (created_time)
            ObjectNode createdProp = mapper.createObjectNode();
            createdProp.set("created_time", mapper.createObjectNode());
            props.set("Created", createdProp);

            body.set("properties", props);

            String response = post("/databases", body.toString());
            JsonNode json = mapper.readTree(response);
            return json.path("id").asText(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Notion database: " + e.getMessage(), e);
        }
    }


    /**
     * Patches an existing Notion database to add the required Quick Notes properties.
     * Safe to call multiple times — Notion ignores properties that already exist.
     */
    public void setupDatabase() {
        try {
            ObjectNode body = mapper.createObjectNode();
            ObjectNode props = mapper.createObjectNode();

            // Category - select
            ObjectNode catProp = mapper.createObjectNode();
            ObjectNode catSelect = mapper.createObjectNode();
            ArrayNode catOptions = mapper.createArrayNode();
            for (String cat : List.of("School","Work","Ideas","Personal","Health","Finance","Reference","Other")) {
                ObjectNode opt = mapper.createObjectNode(); opt.put("name", cat); catOptions.add(opt);
            }
            catSelect.set("options", catOptions);
            catProp.set("select", catSelect);
            props.set("Category", catProp);

            // Tags - multi_select
            ObjectNode tagsProp = mapper.createObjectNode();
            tagsProp.set("multi_select", mapper.createObjectNode());
            props.set("Tags", tagsProp);

            // Raw - rich_text
            ObjectNode rawProp = mapper.createObjectNode();
            rawProp.set("rich_text", mapper.createObjectNode());
            props.set("Raw", rawProp);

            // Summary - rich_text
            ObjectNode sumProp = mapper.createObjectNode();
            sumProp.set("rich_text", mapper.createObjectNode());
            props.set("Summary", sumProp);

            // Source - select
            ObjectNode srcProp = mapper.createObjectNode();
            ObjectNode srcSelect = mapper.createObjectNode();
            ArrayNode srcOptions = mapper.createArrayNode();
            for (String src : List.of("text","voice")) {
                ObjectNode opt = mapper.createObjectNode(); opt.put("name", src); srcOptions.add(opt);
            }
            srcSelect.set("options", srcOptions);
            srcProp.set("select", srcSelect);
            props.set("Source", srcProp);

            body.set("properties", props);

            patch("/databases/" + databaseId, body.toString());
            System.out.println("Notion database setup complete.");
        } catch (Exception e) {
            System.err.println("setupDatabase error: " + e.getMessage());
        }
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    public SavedNote saveNote(String title, String category, List<String> tags,
                               String raw, String summary, String source) {
        try {
            ObjectNode body = mapper.createObjectNode();

            ObjectNode parent = mapper.createObjectNode();
            parent.put("database_id", databaseId);
            body.set("parent", parent);

            ObjectNode props = mapper.createObjectNode();

            // Title
            props.set("Title", richTextProp("title", title));

            // Category
            ObjectNode catProp = mapper.createObjectNode();
            ObjectNode catVal  = mapper.createObjectNode();
            catVal.put("name", category);
            catProp.set("select", catVal);
            props.set("Category", catProp);

            // Tags
            ObjectNode tagsProp = mapper.createObjectNode();
            ArrayNode tagsArr   = mapper.createArrayNode();
            for (String tag : tags) {
                ObjectNode t = mapper.createObjectNode();
                t.put("name", tag.toLowerCase().trim());
                tagsArr.add(t);
            }
            tagsProp.set("multi_select", tagsArr);
            props.set("Tags", tagsProp);

            // Raw
            props.set("Raw", richTextProp("rich_text", truncate(raw, 2000)));

            // Summary
            props.set("Summary", richTextProp("rich_text", truncate(summary, 2000)));

            // Source
            ObjectNode srcProp = mapper.createObjectNode();
            ObjectNode srcVal  = mapper.createObjectNode();
            srcVal.put("name", source);
            srcProp.set("select", srcVal);
            props.set("Source", srcProp);

            body.set("properties", props);

            // Also add raw content as page body for longer notes
            ArrayNode children = mapper.createArrayNode();
            ObjectNode block = mapper.createObjectNode();
            block.put("object", "block");
            block.put("type", "paragraph");
            ObjectNode para = mapper.createObjectNode();
            ArrayNode richArr = mapper.createArrayNode();
            ObjectNode richItem = mapper.createObjectNode();
            richItem.put("type", "text");
            ObjectNode textNode = mapper.createObjectNode();
            textNode.put("content", truncate(raw, 2000));
            richItem.set("text", textNode);
            richArr.add(richItem);
            para.set("rich_text", richArr);
            block.set("paragraph", para);
            children.add(block);
            body.set("children", children);

            String response = post("/pages", body.toString());
            JsonNode json   = mapper.readTree(response);
            String pageId   = json.path("id").asText("");

            return new SavedNote(pageId, title, category, tags);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save note to Notion: " + e.getMessage(), e);
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    public record NoteResult(String title, String category, List<String> tags, String summary, String raw, String created) {}

    public List<NoteResult> searchNotes(String query, String categoryFilter, int limit) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("database_id", databaseId);
            body.put("page_size", Math.min(limit, 10));

            // Build filter
            if (query != null || categoryFilter != null) {
                ObjectNode filter;
                if (query != null && categoryFilter != null) {
                    // AND filter: category match + title/summary contains query
                    ArrayNode andArr = mapper.createArrayNode();
                    andArr.add(buildCategoryFilter(categoryFilter));
                    andArr.add(buildTextFilter("Title", query));
                    filter = mapper.createObjectNode();
                    filter.set("and", andArr);
                } else if (categoryFilter != null) {
                    filter = buildCategoryFilter(categoryFilter);
                } else {
                    // Search in Title and Summary
                    ArrayNode orArr = mapper.createArrayNode();
                    orArr.add(buildTextFilter("Title", query));
                    orArr.add(buildTextFilter("Summary", query));
                    orArr.add(buildTextFilter("Raw", query));
                    filter = mapper.createObjectNode();
                    filter.set("or", orArr);
                }
                body.set("filter", filter);
            }

            // Sort by created time desc
            ArrayNode sorts = mapper.createArrayNode();
            ObjectNode sort = mapper.createObjectNode();
            sort.put("timestamp", "created_time");
            sort.put("direction", "descending");
            sorts.add(sort);
            body.set("sorts", sorts);

            String response = post("/databases/" + databaseId + "/query", body.toString());
            JsonNode json   = mapper.readTree(response);
            JsonNode results = json.path("results");

            List<NoteResult> notes = new ArrayList<>();
            for (JsonNode page : results) {
                JsonNode p       = page.path("properties");
                String title     = extractTitle(p.path("Title"));
                String category  = p.path("Category").path("select").path("name").asText("Other");
                String summary   = extractRichText(p.path("Summary"));
                String raw       = extractRichText(p.path("Raw"));
                String created   = page.path("created_time").asText("").substring(0, 10);
                List<String> tags = new ArrayList<>();
                for (JsonNode tag : p.path("Tags").path("multi_select")) {
                    tags.add(tag.path("name").asText());
                }
                notes.add(new NoteResult(title, category, tags, summary, raw, created));
            }
            return notes;
        } catch (Exception e) {
            System.err.println("NotionService.searchNotes error: " + e.getMessage());
            return List.of();
        }
    }

    public List<NoteResult> getRecentNotes(int limit) {
        return searchNotes(null, null, limit);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ObjectNode buildTextFilter(String property, String value) {
        ObjectNode filter = mapper.createObjectNode();
        ObjectNode condition = mapper.createObjectNode();
        condition.put("contains", value);
        filter.put("property", property);
        if (property.equals("Title")) {
            filter.set("title", condition);
        } else {
            filter.set("rich_text", condition);
        }
        return filter;
    }

    private ObjectNode buildCategoryFilter(String category) {
        ObjectNode filter = mapper.createObjectNode();
        filter.put("property", "Category");
        ObjectNode selectCond = mapper.createObjectNode();
        selectCond.put("equals", category);
        filter.set("select", selectCond);
        return filter;
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

    private String extractTitle(JsonNode titleProp) {
        JsonNode arr = titleProp.path("title");
        if (arr.isArray() && arr.size() > 0) return arr.get(0).path("plain_text").asText("");
        return "";
    }

    private String extractRichText(JsonNode prop) {
        JsonNode arr = prop.path("rich_text");
        if (arr.isArray() && arr.size() > 0) return arr.get(0).path("plain_text").asText("");
        return "";
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String patch(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Notion-Version", NOTION_VERSION)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 400) throw new RuntimeException("Notion PATCH error " + res.statusCode() + ": " + res.body());
        return res.body();
    }

    private String post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("Authorization", "Bearer " + apiKey)
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
