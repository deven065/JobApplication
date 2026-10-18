package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;

/**
 * "Sign in with GitHub" (OAuth web flow). Needs a GitHub OAuth App whose callback URL is
 * {APP_BASE_URL}/api/auth/github/callback, configured with GITHUB_OAUTH_CLIENT_ID and GITHUB_OAUTH_CLIENT_SECRET.
 */
@RestController
@RequestMapping("/api")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String STATE_COOKIE = "pp_oauth_state";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AuthStore auth;
    private final GitHubPortfolioService github;
    private final String clientId, clientSecret, baseUrl;
    private final ObjectMapper json = new ObjectMapper();
    private final RestClient http = RestClient.builder().defaultHeader("User-Agent", "ProofPath").build();

    public AuthController(AuthStore auth, GitHubPortfolioService github,
                          @Value("${github.oauth.client-id:}") String clientId,
                          @Value("${github.oauth.client-secret:}") String clientSecret,
                          @Value("${app.base-url:http://localhost:5173}") String baseUrl) {
        this.auth = auth; this.github = github;
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
    }
    private boolean configured() { return !clientId.isBlank() && !clientSecret.isBlank(); }
    private boolean secure() { return baseUrl.startsWith("https://"); }
    private String callbackUrl() { return baseUrl + "/api/auth/github/callback"; }

    @GetMapping("/auth/status") public AuthStatus status() {
        String login = GitHubCredentials.login();
        return new AuthStatus(configured(), login == null ? null : auth.user(login).map(AuthStore.Session::user).orElse(null));
    }

    /** Starts sign-in. private=true also asks for the repo scope so private repositories can be assessed. */
    @GetMapping("/auth/github/login") public ResponseEntity<Void> login(@RequestParam(defaultValue = "false") boolean privateRepos) {
        if (!configured()) return redirect(baseUrl + "/?authError=" + enc("GitHub sign-in is not configured on this server."), null);
        String state = randomState();
        String url = UriComponentsBuilder.fromUriString("https://github.com/login/oauth/authorize")
                .queryParam("client_id", clientId).queryParam("redirect_uri", callbackUrl())
                .queryParam("scope", privateRepos ? "read:user repo" : "read:user")
                .queryParam("state", state).queryParam("allow_signup", "true").encode().build().toUriString();
        ResponseCookie cookie = ResponseCookie.from(STATE_COOKIE, state).httpOnly(true).secure(secure()).sameSite("Lax")
                .path("/api/auth").maxAge(Duration.ofMinutes(10)).build();
        return redirect(url, cookie);
    }

    @GetMapping("/auth/github/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error, HttpServletRequest request) {
        ResponseCookie clearState = ResponseCookie.from(STATE_COOKIE, "").path("/api/auth").maxAge(0).build();
        String expected = cookie(request, STATE_COOKIE);
        if (error != null) return redirect(baseUrl + "/?authError=" + enc("GitHub sign-in was cancelled."), clearState);
        if (code == null || state == null || expected == null || !expected.equals(state))
            return redirect(baseUrl + "/?authError=" + enc("Sign-in expired or was not started here. Please try again."), clearState);
        try {
            LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("client_id", clientId); form.add("client_secret", clientSecret); form.add("code", code); form.add("redirect_uri", callbackUrl());
            JsonNode tokenReply = json.readTree(http.post().uri("https://github.com/login/oauth/access_token").accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(String.class));
            String token = tokenReply.path("access_token").asText("");
            if (token.isBlank()) throw new IllegalStateException(tokenReply.path("error_description").asText("GitHub did not return an access token."));
            String scopes = tokenReply.path("scope").asText("");
            JsonNode me = json.readTree(http.get().uri("https://api.github.com/user").header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json").retrieve().body(String.class));
            AuthUser user = new AuthUser(me.path("login").asText(), me.path("name").asText(me.path("login").asText()),
                    me.path("avatar_url").asText(null), Arrays.asList(scopes.split("[ ,]")).contains("repo"));
            String session = auth.signIn(user, token);
            ResponseCookie sessionCookie = ResponseCookie.from(SessionFilter.COOKIE, session).httpOnly(true).secure(secure())
                    .sameSite("Lax").path("/").maxAge(Duration.ofDays(30)).build();
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(baseUrl + "/?signedIn=1"))
                    .header(HttpHeaders.SET_COOKIE, sessionCookie.toString()).header(HttpHeaders.SET_COOKIE, clearState.toString()).build();
        } catch (Exception failure) {
            log.warn("GitHub sign-in failed", failure);
            return redirect(baseUrl + "/?authError=" + enc("GitHub sign-in failed. Please try again."), clearState);
        }
    }

    @PostMapping("/auth/logout") public ResponseEntity<Void> logout(HttpServletRequest request) {
        String id = SessionFilter.sessionId(request);
        if (id != null) auth.signOut(id);
        ResponseCookie gone = ResponseCookie.from(SessionFilter.COOKIE, "").httpOnly(true).secure(secure()).sameSite("Lax").path("/").maxAge(0).build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, gone.toString()).build();
    }

    @GetMapping("/me/repos") public List<RepoSummary> repositories() {
        if (GitHubCredentials.login() == null) throw new SecurityException("Sign in with GitHub to list your repositories.");
        return github.userRepositories();
    }

    private static ResponseEntity<Void> redirect(String location, ResponseCookie cookie) {
        ResponseEntity.BodyBuilder reply = ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location));
        if (cookie != null) reply.header(HttpHeaders.SET_COOKIE, cookie.toString());
        return reply.build();
    }
    private static String cookie(HttpServletRequest request, String name) {
        return request.getCookies() == null ? null : Arrays.stream(request.getCookies()).filter(c -> name.equals(c.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
    }
    private static String randomState() { byte[] b = new byte[24]; RANDOM.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static String enc(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); }
}
