package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api")
public class CareerCoachController {
    private final JobDiscoveryService jobs;
    private final AssessmentService assessments;
    private final GitHubPortfolioService github;
    private final StateStore store;
    private final Map<String, JobRequirement> savedJobs;
    public CareerCoachController(JobDiscoveryService jobs, AssessmentService assessments, GitHubPortfolioService github, StateStore store) {
        this.jobs = jobs; this.assessments = assessments; this.github = github; this.store = store;
        savedJobs = new java.util.concurrent.ConcurrentHashMap<>(store.jobs());
        savedJobs.put("demo-backend", jobs.demoJob());
    }

    @GetMapping("/jobs/demo") public JobRequirement demoJob() { return savedJobs.get("demo-backend"); }
    @GetMapping("/jobs") public List<JobRequirement> savedJobs() {
        return savedJobs.values().stream().filter(job -> !job.id().equals("demo-backend"))
                .sorted(Comparator.comparing(JobRequirement::fetchedAt).reversed()).toList();
    }
    @PostMapping("/jobs/discover") public JobRequirement discover(@RequestBody DiscoverJob body) {
        JobRequirement job = jobs.discover(body.title(), body.company(), body.sourceUrl(), body.postingText()); store.saveJob(job); savedJobs.put(job.id(), job); return job;
    }
    @PutMapping("/jobs/{id}/requirements") public JobRequirement updateRequirements(@PathVariable String id, @RequestBody RequirementsUpdate body) {
        JobRequirement existing = Optional.ofNullable(savedJobs.get(id)).orElseThrow(() -> new NoSuchElementException("Unknown job id"));
        if (body.skills() == null || body.skills().isEmpty()) throw new IllegalArgumentException("Select at least one requirement.");
        JobRequirement updated = new JobRequirement(existing.id(), existing.title(), existing.company(), existing.sourceUrl(),
                existing.sourceExcerpt(), body.skills().stream().distinct().toList(), existing.fetchedAt());
        if (!id.equals("demo-backend")) store.saveJob(updated);
        assessments.invalidateJob(id);
        savedJobs.put(id, updated);
        return updated;
    }
    @PostMapping("/assessments") public Assessment assess(@RequestBody AssessRequest body) {
        JobRequirement job = Optional.ofNullable(savedJobs.get(body.jobId())).orElseThrow(() -> new NoSuchElementException("Unknown job id"));
        return assessments.assess(job, body.repository(), body.branch());
    }
    @GetMapping("/repositories/resolve") public GitHubRepository resolveRepository(@RequestParam String repository) { return github.repository(repository); }
    @GetMapping("/repositories/watch") public WatchStatus watchStatus(@RequestParam String repository) {
        RepoRegistration watch = assessments.watch(repository)
                .orElseThrow(() -> new NoSuchElementException("Repository is not being watched"));
        String revision = assessments.lastScan(repository).map(PortfolioScan::revision).orElse("");
        return new WatchStatus(watch.repository(), watch.branch(), revision,
                watch.webhookSecret() != null && !watch.webhookSecret().isBlank(), "Watching for repository changes.");
    }
    @PostMapping("/repositories/watch") @ResponseStatus(HttpStatus.ACCEPTED)
    public WatchStatus watch(@RequestBody WatchRequest body) {
        GitHubRepository repo = github.repository(body.repository());
        String branch = blankToDefault(body.branch(), repo.defaultBranch());
        if (body.installGitHubWebhook() && !github.canCreateWebhooks())
            throw new IllegalStateException("Webhook install needs a GITHUB_TOKEN and an HTTPS github.webhook.public-base-url.");
        PortfolioScan latest = assessments.lastScan(repo.fullName())
                .orElseThrow(() -> new IllegalArgumentException("Assess this repository before watching it."));
        if (!latest.branch().equals(branch)) throw new IllegalArgumentException("Assess the selected branch before watching it.");
        RepoRegistration existing = assessments.watch(repo.fullName()).orElse(null);
        boolean alreadyInstalled = existing != null && existing.webhookSecret() != null && !existing.webhookSecret().isBlank();
        String secret = alreadyInstalled ? existing.webhookSecret() : "";
        if (body.installGitHubWebhook() && !alreadyInstalled) {
            secret = UUID.randomUUID().toString();
            github.createPushWebhook(repo.fullName(), secret);
        }
        assessments.watch(new RepoRegistration(repo.fullName(), branch, secret));
        String message = body.installGitHubWebhook() || alreadyInstalled
                ? "Watching changes with a GitHub webhook and one-minute polling."
                : "Watching changes. GitHub will be checked every minute.";
        return new WatchStatus(repo.fullName(), branch, latest.revision(), body.installGitHubWebhook() || alreadyInstalled, message);
    }
    @GetMapping("/repositories/{owner}/{repo}/last-scan") public PortfolioScan lastScan(@PathVariable String owner, @PathVariable String repo) {
        return assessments.lastScan(owner + "/" + repo).orElseThrow(() -> new NoSuchElementException("Repository has not been scanned"));
    }
    @GetMapping("/assessments/latest") public Assessment latestAssessment(@RequestParam String jobId, @RequestParam String repository) {
        return assessments.latestAssessment(jobId, repository).orElseThrow(() -> new NoSuchElementException("No tracked assessment found. Assess and watch this repository first."));
    }
    @GetMapping("/assessments/history") public List<Assessment> history(@RequestParam String jobId, @RequestParam String repository) {
        return assessments.history(jobId, repository);
    }
    @PostMapping("/webhooks/github") @ResponseStatus(HttpStatus.ACCEPTED)
    public void githubPush(@RequestHeader(value = "X-GitHub-Event", required = false) String event,
                           @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
                           @RequestBody byte[] body) throws Exception {
        JsonNode payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        if (!"push".equals(event)) return;
        String repo = payload.path("repository").path("full_name").asText();
        RepoRegistration watch = assessments.watch(repo).orElseThrow(() -> new IllegalArgumentException("Repository is not registered"));
        if (watch.webhookSecret() == null || watch.webhookSecret().isBlank() || !validSignature(body, signature, watch.webhookSecret()))
            throw new SecurityException("Invalid GitHub webhook signature");
        String pushedBranch = payload.path("ref").asText();
        if (pushedBranch.equals("refs/heads/" + watch.branch())) assessments.reassessRepository(repo);
    }
    private String blankToDefault(String branch, String defaultBranch) { return branch == null || branch.isBlank() ? defaultBranch : branch; }
    private boolean validSignature(byte[] body, String supplied, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body));
        return supplied != null && java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
    public record DiscoverJob(String title, String company, String sourceUrl, String postingText) { }
    public record AssessRequest(String jobId, String repository, String branch) { }
    public record WatchRequest(String repository, String branch, boolean installGitHubWebhook) { }
    public record RequirementsUpdate(List<Skill> skills) { }
}
