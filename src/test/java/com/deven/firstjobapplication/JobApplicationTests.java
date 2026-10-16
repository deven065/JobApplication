package com.deven.firstjobapplication;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:proofpath_context_test;DB_CLOSE_DELAY=-1")
class JobApplicationTests {
    @Value("${local.server.port}") int port;

    @Test
    void contextLoads() {
    }

    @Test void jobRequirementsAndHistoryWorkOverHttp() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String base = "http://localhost:" + port + "/api";
        HttpRequest discover = HttpRequest.newBuilder(URI.create(base + "/jobs/discover"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"Intern\",\"company\":\"Example\",\"sourceUrl\":\"https://example.com/job\",\"postingText\":\"Java and SQL\"}"))
                .build();
        HttpResponse<String> created = client.send(discover, HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(200);
        JsonNode job = new ObjectMapper().readTree(created.body());
        String id = job.path("id").asText();
        assertThat(job.path("requiredSkills").size()).isGreaterThan(0);

        HttpRequest edit = HttpRequest.newBuilder(URI.create(base + "/jobs/" + id + "/requirements"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"skills\":[\"JAVA\",\"SQL\"]}"))
                .build();
        HttpResponse<String> changed = client.send(edit, HttpResponse.BodyHandlers.ofString());
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(new ObjectMapper().readTree(changed.body()).path("requiredSkills").size()).isEqualTo(2);

        HttpResponse<String> saved = client.send(HttpRequest.newBuilder(URI.create(base + "/jobs")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(saved.body()).contains(id);
        HttpResponse<String> history = client.send(HttpRequest.newBuilder(URI.create(base + "/assessments/history?jobId=" + id + "&repository=owner%2Frepo")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(history.statusCode()).isEqualTo(200);
        assertThat(history.body()).isEqualTo("[]");
    }

}
