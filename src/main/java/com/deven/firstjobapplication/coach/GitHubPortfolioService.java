package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Uses GitHub's public REST API. Private repositories work when GITHUB_TOKEN is configured. */
@Service
public class GitHubPortfolioService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RestClient github;
    private final String token;
    private final String publicWebhookBaseUrl;
    public GitHubPortfolioService(@Value("${github.token:}") String token,
                                  @Value("${github.webhook.public-base-url:}") String publicWebhookBaseUrl) {
        this.token = token == null ? "" : token.trim();
        this.publicWebhookBaseUrl = publicWebhookBaseUrl == null ? "" : publicWebhookBaseUrl.trim().replaceAll("/+$", "");
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json").defaultHeader("User-Agent", "AI-Career-Coach-Demo");
        if (!this.token.isBlank()) builder.defaultHeader("Authorization", "Bearer " + this.token);
        github = builder.build();
    }

    public GitHubRepository repository(String repository) {
        String[] id = repositoryId(repository);
        JsonNode node = json(github.get().uri("/repos/{owner}/{name}", id[0], id[1]).retrieve().body(String.class));
        if (node == null || node.path("default_branch").asText().isBlank()) throw new IllegalArgumentException("GitHub repository was not found or is not accessible: " + repository);
        return new GitHubRepository(node.path("full_name").asText(), node.path("default_branch").asText(), node.path("html_url").asText(), node.path("private").asBoolean());
    }

    public PortfolioScan scan(String repository, String branch) {
        String ref = branch == null || branch.isBlank() ? repository(repository).defaultBranch() : branch;
        String[] id = repositoryId(repository);
        JsonNode commit = json(github.get().uri("/repos/{owner}/{name}/commits/{ref}", id[0], id[1], ref).retrieve().body(String.class));
        String sha = Objects.requireNonNull(commit).path("sha").asText();
        JsonNode tree = json(github.get().uri("/repos/{owner}/{name}/git/trees/{sha}?recursive=1", id[0], id[1], sha).retrieve().body(String.class));
        Map<Skill, List<Evidence>> found = new EnumMap<>(Skill.class);
        int inspected = 0;
        for (JsonNode node : Objects.requireNonNull(tree).path("tree")) {
            String path = node.path("path").asText();
            if (!"blob".equals(node.path("type").asText()) || !isRelevant(path) || inspected++ >= 80) continue;
            try {
                String raw = json(github.get().uri("/repos/{owner}/{name}/git/blobs/{blob}", id[0], id[1], node.path("sha").asText())
                        .retrieve().body(String.class)).path("content").asText();
                String content = new String(Base64.getMimeDecoder().decode(raw), StandardCharsets.UTF_8);
                for (Skill skill : SkillMatcher.skillsIn(content)) {
                    String snippet = content.lines().filter(l -> SkillMatcher.skillsIn(l).contains(skill)).findFirst().orElse("").trim();
                    String url = "https://github.com/" + repository + "/blob/" + sha + "/" + path;
                    found.computeIfAbsent(skill, k -> new ArrayList<>()).add(new Evidence(skill, path, url, SkillMatcher.reason(skill), snippet));
                }
            } catch (Exception ignored) { /* Binary/too-large/inaccessible files are not evidence. */ }
        }
        return new PortfolioScan(repository, sha, Instant.now(), found);
    }
    public String latestRevision(String repository, String branch) {
        String[] id = repositoryId(repository);
        return json(github.get().uri("/repos/{owner}/{name}/commits/{branch}", id[0], id[1], branch).retrieve().body(String.class)).path("sha").asText();
    }
    public boolean canCreateWebhooks() { return !token.isBlank() && publicWebhookBaseUrl.startsWith("https://"); }
    public void createPushWebhook(String repository, String secret) {
        if (!canCreateWebhooks()) throw new IllegalStateException("Set GITHUB_TOKEN and github.webhook.public-base-url to an HTTPS tunnel or deployed URL before installing a GitHub webhook.");
        Map<String, Object> config = Map.of("url", publicWebhookBaseUrl + "/api/webhooks/github", "content_type", "json", "secret", secret, "insecure_ssl", "0");
        Map<String, Object> body = Map.of("name", "web", "active", true, "events", List.of("push"), "config", config);
        String[] id = repositoryId(repository);
        github.post().uri("/repos/{owner}/{name}/hooks", id[0], id[1]).body(body).retrieve().toBodilessEntity();
    }
    private String[] repositoryId(String repository) {
        String[] parts = repository == null ? new String[0] : repository.trim().split("/", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank())
            throw new IllegalArgumentException("Repository must use the form owner/repository.");
        return parts;
    }
    private JsonNode json(String body) {
        try { return JSON.readTree(body); }
        catch (Exception exception) { throw new IllegalStateException("GitHub returned invalid JSON", exception); }
    }
    private boolean isRelevant(String p) { return p.matches("(?i).*(\\.java|\\.sql|\\.js|\\.jsx|\\.ts|\\.tsx|\\.yml|\\.yaml|dockerfile|pom\\.xml|build\\.gradle)$"); }
}
