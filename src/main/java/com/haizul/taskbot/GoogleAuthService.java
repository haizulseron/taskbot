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
import java.util.List;

public class GoogleAuthService {

    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String REDIRECT_URI   = "urn:ietf:wg:oauth:2.0:oob";
    private static final List<String> SCOPES = List.of(
            "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/gmail.compose",
            "https://www.googleapis.com/auth/calendar",
            "https://www.googleapis.com/auth/drive.readonly"
    );

    private final Path tokensFile;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http     = HttpClient.newHttpClient();

    private String clientId;
    private String clientSecret;
    private String refreshToken;
    private NetHttpTransport httpTransport;
    private boolean initialized = false;

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
            String body = "code="          + encode(code.trim())
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

            this.refreshToken = json.get("refresh_token").asText();
            Files.createDirectories(tokensFile.getParent());
            ObjectNode stored = mapper.createObjectNode();
            stored.put("refresh_token", refreshToken);
            mapper.writeValue(tokensFile.toFile(), stored);
            return true;
        } catch (Exception e) {
            System.err.println("OAuth code exchange failed: " + e.getMessage());
            return false;
        }
    }

    public HttpCredentialsAdapter getHttpCredentials() {
        if (!isAuthorized()) return null;
        UserCredentials creds = UserCredentials.newBuilder()
                .setClientId(clientId)
                .setClientSecret(clientSecret)
                .setRefreshToken(refreshToken)
                .build();
        return new HttpCredentialsAdapter(creds);
    }

    public NetHttpTransport getHttpTransport()  { return httpTransport; }
    public GsonFactory       getJsonFactory()   { return GsonFactory.getDefaultInstance(); }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
