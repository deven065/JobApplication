package com.deven.firstjobapplication;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** The real server with Google sign-in configured (fake client id and secret: the browser redirects are what is checked). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.url=jdbc:h2:mem:proofpath_google_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=",
                "google.oauth.client-id=test-client-id", "google.oauth.client-secret=test-client-secret", "app.base-url=https://proofpath.example"})
class GoogleSignInHttpTests {
    @Value("${local.server.port}") int port;
    private final HttpClient client = HttpClient.newHttpClient();   // does not follow redirects
    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void statusAnnouncesGoogleAndTheLoginRedirectLeavesForGoogleWithAStateCookie() throws Exception {
        assertThat(new ObjectMapper().readTree(get("/auth/status").body()).path("googleConfigured").asBoolean()).isTrue();

        HttpResponse<String> login = get("/auth/google/login");
        assertThat(login.statusCode()).isEqualTo(302);
        String location = login.headers().firstValue("Location").orElseThrow();
        assertThat(location).startsWith("https://accounts.google.com/o/oauth2/v2/auth?").contains("client_id=test-client-id")
                .contains("redirect_uri=https://proofpath.example/api/auth/google/callback").contains("scope=openid%20email%20profile");
        String cookie = login.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("pp_google_state=")).findFirst().orElseThrow();
        assertThat(cookie).contains("HttpOnly").contains("Secure").contains("SameSite=Lax").contains("Path=/api/auth");
        String stateInCookie = cookie.substring("pp_google_state=".length(), cookie.indexOf(';'));
        assertThat(location).contains("state=" + stateInCookie);   // the browser must come back with the same value
    }

    @Test void aCallbackThatWasNotStartedHereIsBouncedBackWithAMessage() throws Exception {
        HttpResponse<String> forged = get("/auth/google/callback?code=abc&state=forged");
        assertThat(forged.statusCode()).isEqualTo(302);
        assertThat(forged.headers().firstValue("Location").orElseThrow()).startsWith("https://proofpath.example/?authError=");
        assertThat(forged.headers().allValues("Set-Cookie").stream().anyMatch(c -> c.startsWith("pp_session="))).isFalse();
    }
}
