package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.AuthUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * "Continue with Google" (OAuth 2.0 authorization-code flow, scopes openid, email and profile). Needs a Google OAuth client whose
 * authorized redirect URI is {APP_BASE_URL}/api/auth/google/callback, set through GOOGLE_OAUTH_CLIENT_ID and GOOGLE_OAUTH_CLIENT_SECRET.
 * A Google account is its own account type (login "google:" plus Google's stable user id), like an email account: it has no GitHub access.
 */
@Component
public class GoogleOAuth {
    static final String PROVIDER = "google";
    private final String clientId, clientSecret, baseUrl;
    private final ObjectMapper json = new ObjectMapper();
    private final RestClient http = RestClient.builder().defaultHeader("User-Agent", "ProofPath").build();

    public GoogleOAuth(@Value("${google.oauth.client-id:}") String clientId, @Value("${google.oauth.client-secret:}") String clientSecret,
                       @Value("${app.base-url:http://localhost:5173}") String baseUrl) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
    }
    public boolean configured() { return !clientId.isBlank() && !clientSecret.isBlank(); }
    public String callbackUrl() { return baseUrl + "/api/auth/google/callback"; }

    public String authorizeUrl(String state) {
        return UriComponentsBuilder.fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
                .queryParam("client_id", clientId).queryParam("redirect_uri", callbackUrl()).queryParam("response_type", "code")
                .queryParam("scope", "openid email profile").queryParam("state", state).queryParam("prompt", "select_account")
                .encode().build().toUriString();
    }

    /** Trades the one-time code for the signed-in user's Google profile. */
    public AuthUser fetchUser(String code) throws Exception {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code); form.add("client_id", clientId); form.add("client_secret", clientSecret);
        form.add("redirect_uri", callbackUrl()); form.add("grant_type", "authorization_code");
        JsonNode token = json.readTree(http.post().uri("https://oauth2.googleapis.com/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(String.class));
        String access = token.path("access_token").asText("");
        if (access.isBlank()) throw new IllegalStateException("Google did not return an access token.");
        JsonNode profile = json.readTree(http.get().uri("https://openidconnect.googleapis.com/v1/userinfo")
                .header("Authorization", "Bearer " + access).retrieve().body(String.class));
        return toUser(profile);
    }

    /** Maps Google's userinfo to a ProofPath user. An unverified Google email is refused. */
    static AuthUser toUser(JsonNode profile) {
        String subject = profile.path("sub").asText("");
        if (subject.isBlank()) throw new IllegalArgumentException("Google did not identify the account. Please try again.");
        if (!profile.path("email_verified").asBoolean(false)) throw new IllegalArgumentException("Your Google email address is not verified, so it cannot be used to sign in.");
        String email = profile.path("email").asText("");
        String name = profile.path("name").asText("").trim();
        if (name.isEmpty()) name = email.contains("@") ? email.substring(0, email.indexOf('@')) : "Google user";
        if (name.length() > 80) name = name.substring(0, 80);
        String picture = profile.path("picture").asText(null);
        return new AuthUser(PROVIDER + ":" + subject, name, picture == null || picture.isBlank() ? null : picture, false, false, PROVIDER);
    }
}
