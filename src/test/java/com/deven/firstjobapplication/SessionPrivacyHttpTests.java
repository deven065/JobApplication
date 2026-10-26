package com.deven.firstjobapplication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Two real browsers (separate cookie jars) talking to the running server. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.url=jdbc:h2:mem:proofpath_privacy_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa", "spring.datasource.password="})
class SessionPrivacyHttpTests {
    @Value("${local.server.port}") int port;
    private final ObjectMapper json = new ObjectMapper();

    private HttpClient browser() { return HttpClient.newBuilder().cookieHandler(new CookieManager()).build(); }
    private String api(String path) { return "http://localhost:" + port + "/api" + path; }
    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(api(path))).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(HttpClient client, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(api(path))).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private String newJob(HttpClient client) throws Exception {
        HttpResponse<String> created = post(client, "/jobs/discover", "{\"title\":\"Intern\",\"company\":\"Example\",\"sourceUrl\":\"https://example.com/job\",\"postingText\":\"Java and SQL\"}");
        assertThat(created.statusCode()).isEqualTo(200);
        return json.readTree(created.body()).path("id").asText();
    }

    @Test void eachBrowserSeesOnlyItsOwnJobsAndNobodyElsesHistory() throws Exception {
        HttpClient alice = browser(), bob = browser(), stranger = browser();
        String alicesJob = newJob(alice);
        assertThat(get(alice, "/jobs").body()).contains(alicesJob);

        assertThat(get(bob, "/jobs").body()).isEqualTo("[]");                       // before doing anything
        String bobsJob = newJob(bob);
        assertThat(get(bob, "/jobs").body()).contains(bobsJob).doesNotContain(alicesJob);
        assertThat(get(alice, "/jobs").body()).doesNotContain(bobsJob);
        assertThat(get(bob, "/assessments/history?jobId=" + alicesJob + "&repository=o%2Fr").statusCode()).isEqualTo(403);
        assertThat(get(stranger, "/jobs").body()).isEqualTo("[]");                   // a browser with no session sees nothing
        assertThat(get(stranger, "/assessments/history?jobId=" + alicesJob + "&repository=o%2Fr").statusCode()).isEqualTo(403);
    }

    @Test void signingOutDeletesTheSessionsJobsAndSigningInAgainStartsClean() throws Exception {
        HttpClient browser = browser();
        HttpResponse<String> registered = post(browser, "/auth/register", "{\"name\":\"Asha\",\"email\":\"asha-privacy@example.com\",\"password\":\"long enough pw\"}");
        assertThat(registered.statusCode()).isEqualTo(200);
        String job = newJob(browser);
        assertThat(get(browser, "/jobs").body()).contains(job);

        assertThat(post(browser, "/auth/logout", "{}").statusCode()).isEqualTo(204);
        HttpResponse<String> again = post(browser, "/auth/login", "{\"email\":\"asha-privacy@example.com\",\"password\":\"long enough pw\"}");
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(get(browser, "/jobs").body()).isEqualTo("[]");                    // nothing survived the session
        JsonNode status = json.readTree(get(browser, "/auth/status").body());
        assertThat(status.path("user").path("provider").asText()).isEqualTo("email"); // the account itself did
    }

    @Test void publicEndpointsAndHealthChecksDoNotCreateGuests() throws Exception {
        HttpClient client = browser();
        get(client, "/ai/status");
        get(client, "/public/nobody");
        assertThat(((CookieManager) client.cookieHandler().orElseThrow()).getCookieStore().getCookies()).isEmpty();
    }
}
