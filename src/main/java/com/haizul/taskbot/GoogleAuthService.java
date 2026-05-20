package com.haizul.taskbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.UserCredentials;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

public class GoogleAuthService {

    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    // OOB (urn:ietf:wg:oauth:2.0:oob) was fully disabled by Google in Oct 2022.
    // We use the loopback redirect that's registered in credentials.json. The browser
    // will fail to reach localhost (no server listening), but the code is in the URL bar
    // for the user to copy back to the bot.
    private static final String REDIRECT_URI   = "http://localhost";
    private static final List<String> SCOPES = List.of(
            "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/gmail.compose",
            "https://www.googleapis.com/auth/calendar",
            "https://www.googleapis.com/auth/drive.readonly",
            "https://www.googleapis.com/auth/tasks"
    );

    private final Path tokensFile;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http     = HttpClient.newHttpClient();

    private String clientId;
    private String clientSecret;
    private volatile String refreshToken;
    private NetHttpTransport httpTransport;
    private volatile boolean initialized = false;
    // Cached credentials adapter — avoids refreshing the access token on every API call
    private volatile HttpCredentialsAdapter cachedCredentials;

    public GoogleAuthService(String credentialsPath, String tokensPath) {
        this.tokensFile = Path.of(tokensPath, "tokens.json");
        try {
            this.httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            loadCredentials(credentialsPath);
            loadStoredTokens();
            initialized = true;
        } catch (Exception e) {
            System.err.println("GoogleAuthService init failed: " + e.getMessage());
        }
    }

    private void loadCredentials(String credentialsPath) throws Exception {
        JsonNode root  = mapper.readTree(new File(credentialsPath));
        JsonNode creds = root.has("installed") ? root.get("installed") : root.get("web");
        if (creds == null) throw new IllegalArgumentException("Invalid credentials.json format");
        this.clientId     = creds.get("client_id").asText();
        this.clientSecret = creds.get("client_secret").asText();
    }

    private void loadStoredTokens() {
        if (!Files.exists(tokensFile)) return;
        try {
            JsonNode tokens = mapper.readTree(tokensFile.toFile());
            String stored   = tokens.path("refresh_token").asText(null);
            if (stored != null && !stored.isBlank()) this.refreshToken = stored;
        } catch (Exception e) {
            System.err.println("Failed to load stored Google tokens: " + e.getMessage());
        }
    }

    public boolean isInitialized() { return initialized; }
    public boolean isAuthorized()  { return initialized && refreshToken != null; }

    public String getAuthorizationUrl() {
        String scopes = String.join(" ", SCOPES);
        return "https://accounts.google.com/o/oauth2/auth"
                + "?client_id="     + encode(clientId)
                + "&redirect_uri="  + encode(REDIRECT_URI)
                + "&response_type=code"
                + "&scope="         + encode(scopes)
                + "&access_type=offline"
                + "&prompt=consent";
    }

    public boolean exchangeCode(String code) {
        try {
            String extracted = extractCode(code);
            String body = "code="          + encode(extracted)
                    + "&client_id="        + encode(clientId)
                    + "&client_secret="    + encode(clientSecret)
                    + "&redirect_uri="     + encode(REDIRECT_URI)
                    + "&grant_type=authorization_code";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_ENDPOINT))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(res.body());

            if (json.has("error")) {
                System.err.println("Token exchange error: " + json.get("error").asText()
                        + " — " + json.path("error_description").asText());
                return false;
            }

            String rt = json.path("refresh_token").asText(null);
            if (rt == null || rt.isBlank()) {
                // Google may not return refresh_token on re-auth; keep existing one
                if (this.refreshToken == null) {
                    System.err.println("No refresh_token in response and none stored.");
                    return false;
                }
            } else {
                this.refreshToken = rt;
            }
            this.cachedCredentials = null; // invalidate cache
            Files.createDirectories(tokensFile.getParent());
            ObjectNode stored = mapper.createObjectNode();
            stored.put("refresh_token", refreshToken);
            mapper.writeValue(tokensFile.toFile(), stored);
            // Restrict token file to owner-only (rw-------)
            try { Files.setPosixFilePermissions(tokensFile, PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) {} // non-POSIX OS
            return true;
        } catch (Exception e) {
            System.err.println("OAuth code exchange failed: " + e.getMessage());
            return false;
        }
    }

    public HttpCredentialsAdapter getHttpCredentials() {
        if (!isAuthorized()) return null;
        // Cache the adapter so the access token is reused across calls
        HttpCredentialsAdapter cached = this.cachedCredentials;
        if (cached != null) return cached;
        UserCredentials creds = UserCredentials.newBuilder()
                .setClientId(clientId)
                .setClientSecret(clientSecret)
                .setRefreshToken(refreshToken)
                .build();
        cached = new HttpCredentialsAdapter(creds);
        this.cachedCredentials = cached;
        return cached;
    }

    public NetHttpTransport getHttpTransport()  { return httpTransport; }
    public GsonFactory       getJsonFactory()   { return GsonFactory.getDefaultInstance(); }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Accept either a raw code or a full URL pasted from the browser address bar. */
    private static String extractCode(String input) {
        String s = input == null ? "" : input.trim();
        int q = s.indexOf("code=");
        if (q < 0) return s;
        String tail = s.substring(q + 5);
        int amp = tail.indexOf('&');
        String raw = amp < 0 ? tail : tail.substring(0, amp);
        try { return java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8); }
        catch (Exception e) { return raw; }
    }
}
