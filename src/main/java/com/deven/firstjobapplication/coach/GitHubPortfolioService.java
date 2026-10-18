package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
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
        github = builder.build();
    }
    /** The signed-in user's token when there is one, otherwise the server's GITHUB_TOKEN (if configured). */
    private void auth(org.springframework.http.HttpHeaders headers) {
        String user = GitHubCredentials.token();
        String chosen = user != null && !user.isBlank() ? user : token;
        if (!chosen.isBlank()) headers.setBearerAuth(chosen);
    }

    public GitHubRepository repository(String repository) {
        String[] id = repositoryId(repository);
        JsonNode node = json(github.get().uri("/repos/{owner}/{name}", id[0], id[1]).headers(this::auth).retrieve().body(String.class));
        if (node == null || node.path("default_branch").asText().isBlank()) throw new IllegalArgumentException("GitHub repository was not found or is not accessible: " + repository);
        return new GitHubRepository(node.path("full_name").asText(), node.path("default_branch").asText(), node.path("html_url").asText(), node.path("private").asBoolean());
    }

    /** Repositories of the signed-in user, most recently pushed first (requires a user token). */
    public List<RepoSummary> userRepositories() {
        if (GitHubCredentials.token() == null) throw new IllegalStateException("Sign in with GitHub to list your repositories.");
        JsonNode list = json(github.get().uri("/user/repos?per_page=100&sort=pushed&affiliation=owner,collaborator,organization_member")
                .headers(this::auth).retrieve().body(String.class));
        List<RepoSummary> repos = new ArrayList<>();
        for (JsonNode repo : list) {
            String pushed = repo.path("pushed_at").asText("");
            repos.add(new RepoSummary(repo.path("full_name").asText(), repo.path("private").asBoolean(), repo.path("default_branch").asText("main"),
                    repo.path("language").asText(null), repo.path("description").asText(null), pushed.isBlank() ? null : Instant.parse(pushed)));
        }
        return repos;
    }

    public PortfolioScan scan(String repository, String branch) {
        String ref = branch == null || branch.isBlank() ? repository(repository).defaultBranch() : branch.trim();
        String[] id = repositoryId(repository);
        JsonNode commit = commit(repository, id, ref);
        String sha = Objects.requireNonNull(commit).path("sha").asText();
        String treeSha = commit.path("commit").path("tree").path("sha").asText();
        if (sha.isBlank() || treeSha.isBlank()) throw new IllegalStateException("GitHub did not return a complete commit tree.");
        JsonNode tree = json(github.get().uri("/repos/{owner}/{name}/git/trees/{sha}?recursive=1", id[0], id[1], treeSha).headers(this::auth).retrieve().body(String.class));
        Map<Skill, List<Evidence>> found = new EnumMap<>(Skill.class);
        int attempted = 0;
        int scanned = 0;
        int skipped = 0;
        boolean truncated = tree.path("truncated").asBoolean();
        for (JsonNode node : Objects.requireNonNull(tree).path("tree")) {
            String path = node.path("path").asText();
            if (!"blob".equals(node.path("type").asText()) || !isRelevant(path)) continue;
            if (attempted >= 80) { skipped++; continue; }
            attempted++;
            try {
                String raw = json(github.get().uri("/repos/{owner}/{name}/git/blobs/{blob}", id[0], id[1], node.path("sha").asText())
                        .headers(this::auth).retrieve().body(String.class)).path("content").asText();
                String content = new String(Base64.getMimeDecoder().decode(raw), StandardCharsets.UTF_8);
                scanned++;
                for (Skill skill : SkillMatcher.skillsIn(content)) {
                    List<String> lines = content.lines().toList();
                    int match = 0;
                    while (match < lines.size() && !SkillMatcher.skillsIn(lines.get(match)).contains(skill)) match++;
                    String snippet = match < lines.size() ? lines.get(match).trim() : "";
                    String url = "https://github.com/" + repository + "/blob/" + sha + "/" + path + (match < lines.size() ? "#L" + (match + 1) : "");
                    found.computeIfAbsent(skill, k -> new ArrayList<>()).add(new Evidence(skill, path, url, SkillMatcher.reason(skill), snippet, excerpt(lines, match)));
                }
            } catch (Exception ignored) { skipped++; /* Binary/too-large/inaccessible files are not evidence. */ }
        }
        return new PortfolioScan(repository, ref, sha, Instant.now(), found, scanned, skipped, truncated);
    }
    public String latestRevision(String repository, String branch) {
        String[] id = repositoryId(repository);
        return commit(repository, id, branch).path("sha").asText();
    }
    /** GitHub answers 422 for a branch that does not exist; turn that into an actionable message. */
    private JsonNode commit(String repository, String[] id, String ref) {
        try {
            return json(github.get().uri("/repos/{owner}/{name}/commits/{ref}", id[0], id[1], ref).headers(this::auth).retrieve().body(String.class));
        } catch (HttpClientErrorException error) {
            if (error.getStatusCode().value() != 422) throw error;
            String defaultBranch = "";
            try { defaultBranch = repository(repository).defaultBranch(); } catch (Exception ignored) { /* message below still helps */ }
            throw new IllegalArgumentException("Branch \"" + ref + "\" was not found in " + repository + "."
                    + (defaultBranch.isBlank() ? "" : " Leave Branch empty to use the default branch \"" + defaultBranch + "\"."));
        }
    }
    /**
     * Files changed between two commits (GitHub compare API). GitHub lists at most 300 files; a larger change is marked
     * incomplete so the caller re-evaluates everything instead of trusting a partial list.
     */
    public ChangeSet compare(String repository, String baseRevision, String headRevision) {
        String[] id = repositoryId(repository);
        JsonNode node = json(github.get().uri("/repos/{owner}/{name}/compare/{range}", id[0], id[1], baseRevision + "..." + headRevision)
                .headers(this::auth).retrieve().body(String.class));
        List<ChangedFile> files = new ArrayList<>();
        for (JsonNode file : node.path("files")) {
            String path = file.path("filename").asText();
            String status = file.path("status").asText("modified");
            String atCommit = status.equals("removed") ? baseRevision : headRevision;
            String previous = file.path("previous_filename").asText(null);
            files.add(new ChangedFile(path, status, "https://github.com/" + repository + "/blob/" + atCommit + "/" + path, previous));
        }
        boolean complete = node.has("files") && files.size() < 300;
        return new ChangeSet(baseRevision, headRevision, files, List.of(), List.of(), complete);
    }
    public boolean canCreateWebhooks() { return !token.isBlank() && publicWebhookBaseUrl.startsWith("https://"); }
    public void createPushWebhook(String repository, String secret) {
        if (!canCreateWebhooks()) throw new IllegalStateException("Set GITHUB_TOKEN and github.webhook.public-base-url to an HTTPS tunnel or deployed URL before installing a GitHub webhook.");
        Map<String, Object> config = Map.of("url", publicWebhookBaseUrl + "/api/webhooks/github", "content_type", "json", "secret", secret, "insecure_ssl", "0");
        Map<String, Object> body = Map.of("name", "web", "active", true, "events", List.of("push"), "config", config);
        String[] id = repositoryId(repository);
        github.post().uri("/repos/{owner}/{name}/hooks", id[0], id[1]).body(body).headers(this::auth).retrieve().toBodilessEntity();
    }
    private String[] repositoryId(String repository) {
        String value = repository == null ? "" : repository.trim();
        if (value.startsWith("https://github.com/")) value = value.substring("https://github.com/".length());
        value = value.replaceAll("/+$", "").replaceAll("\\.git$", "");
        String[] parts = value.split("/", -1);
        if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9-]+") || !parts[1].matches("[A-Za-z0-9_.-]+"))
            throw new IllegalArgumentException("Enter a GitHub repository as owner/name or paste its URL.");
        return parts;
    }
    private JsonNode json(String body) {
        try { return JSON.readTree(body); }
        catch (Exception exception) { throw new IllegalStateException("GitHub returned invalid JSON", exception); }
    }
    /** Numbered lines around the first match, so a reviewer sees the code and not only the keyword. */
    private String excerpt(List<String> lines, int match) {
        int start = Math.max(0, Math.min(match, lines.size()) - 8);
        int end = Math.min(lines.size(), start + 40);
        StringBuilder out = new StringBuilder();
        for (int i = start; i < end && out.length() < 1800; i++) out.append(i + 1).append(": ").append(lines.get(i)).append('\n');
        return out.toString();
    }
    private boolean isRelevant(String p) { return p.matches("(?i).*(\\.java|\\.sql|\\.js|\\.jsx|\\.ts|\\.tsx|\\.yml|\\.yaml|dockerfile|pom\\.xml|build\\.gradle)$"); }
}
