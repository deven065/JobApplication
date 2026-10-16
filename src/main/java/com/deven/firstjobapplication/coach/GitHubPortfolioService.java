package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.time.Instant;
import java.util.*;

/** Uses GitHub's public REST API. Private repositories work when GITHUB_TOKEN is configured. */
@Service
public class GitHubPortfolioService {
    private final RestClient github;
    public GitHubPortfolioService(@Value("${github.token:}") String token) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json").defaultHeader("User-Agent", "AI-Career-Coach-Demo");
        if (token != null && !token.isBlank()) builder.defaultHeader("Authorization", "Bearer " + token);
        github = builder.build();
    }

    public PortfolioScan scan(String repository, String branch) {
        String ref = branch == null || branch.isBlank() ? "main" : branch;
        JsonNode commit = github.get().uri("/repos/{repo}/commits/{ref}", repository, ref).retrieve().body(JsonNode.class);
        String sha = Objects.requireNonNull(commit).path("sha").asText();
        JsonNode tree = github.get().uri("/repos/{repo}/git/trees/{sha}?recursive=1", repository, sha).retrieve().body(JsonNode.class);
        Map<Skill, List<Evidence>> found = new EnumMap<>(Skill.class);
        int inspected = 0;
        for (JsonNode node : Objects.requireNonNull(tree).path("tree")) {
            String path = node.path("path").asText();
            if (!"blob".equals(node.path("type").asText()) || !isRelevant(path) || inspected++ >= 80) continue;
            try {
                String raw = github.get().uri("/repos/{repo}/contents/{path}?ref={sha}", repository, path, sha)
                        .retrieve().body(JsonNode.class).path("content").asText();
                String content = new String(Base64.getMimeDecoder().decode(raw));
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
        return github.get().uri("/repos/{repo}/commits/{branch}", repository, branch).retrieve().body(JsonNode.class).path("sha").asText();
    }
    private boolean isRelevant(String p) { return p.matches("(?i).*(\\.java|\\.sql|\\.js|\\.jsx|\\.ts|\\.tsx|\\.yml|\\.yaml|dockerfile|pom\\.xml|build\\.gradle)$"); }
}
