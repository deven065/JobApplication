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
    private final Map<String, JobRequirement> savedJobs = new HashMap<>();
    public CareerCoachController(JobDiscoveryService jobs, AssessmentService assessments, GitHubPortfolioService github) { this.jobs = jobs; this.assessments = assessments; this.github = github; savedJobs.put("demo-backend", jobs.demoJob()); }

    @GetMapping("/jobs/demo") public JobRequirement demoJob() { return savedJobs.get("demo-backend"); }
    @PostMapping("/jobs/discover") public JobRequirement discover(@RequestBody DiscoverJob body) {
        JobRequirement job = jobs.discover(body.title(), body.company(), body.sourceUrl()); savedJobs.put(job.id(), job); return job;
    }
    @PostMapping("/assessments") public Assessment assess(@RequestBody AssessRequest body) {
        JobRequirement job = Optional.ofNullable(savedJobs.get(body.jobId())).orElseThrow(() -> new NoSuchElementException("Unknown job id"));
        return assessments.assess(job, body.repository(), body.branch());
    }
    @GetMapping("/repositories/resolve") public GitHubRepository resolveRepository(@RequestParam String repository) { return github.repository(repository); }
    @PostMapping("/repositories/watch") @ResponseStatus(HttpStatus.ACCEPTED)
    public WatchStatus watch(@RequestBody WatchRequest body) {
        GitHubRepository repo = github.repository(body.repository());
        String branch = blankToDefault(body.branch(), repo.defaultBranch());
        if (body.installGitHubWebhook() && !github.canCreateWebhooks())
            throw new IllegalStateException("Webhook install needs a GITHUB_TOKEN and an HTTPS github.webhook.public-base-url.");
        String secret = UUID.randomUUID().toString();
        assessments.watch(new RepoRegistration(repo.fullName(), branch, secret));
        List<Assessment> updated = assessments.reassessRepository(repo.fullName());
        if (body.installGitHubWebhook()) github.createPushWebhook(repo.fullName(), secret);
        String revision = updated.isEmpty() ? assessments.lastScan(repo.fullName()).map(PortfolioScan::revision).orElse("") : updated.get(0).portfolio().revision();
        String message = body.installGitHubWebhook() ? "GitHub push webhook installed and polling enabled." : "Polling enabled. Enable webhook setup after configuring an HTTPS callback URL.";
        return new WatchStatus(repo.fullName(), branch, revision, body.installGitHubWebhook(), message);
    }
    @GetMapping("/repositories/{owner}/{repo}/last-scan") public PortfolioScan lastScan(@PathVariable String owner, @PathVariable String repo) {
        return assessments.lastScan(owner + "/" + repo).orElseThrow(() -> new NoSuchElementException("Repository has not been scanned"));
    }
    @GetMapping("/assessments/latest") public Assessment latestAssessment(@RequestParam String jobId, @RequestParam String repository) {
        return assessments.latestAssessment(jobId, repository).orElseThrow(() -> new NoSuchElementException("No tracked assessment found. Assess and watch this repository first."));
    }
    @PostMapping("/webhooks/github") @ResponseStatus(HttpStatus.ACCEPTED)
    public void githubPush(@RequestHeader(value = "X-GitHub-Event", required = false) String event,
                           @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
                           @RequestBody byte[] body) throws Exception {
        JsonNode payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        if (!"push".equals(event)) return;
        String repo = payload.path("repository").path("full_name").asText();
        RepoRegistration watch = assessments.watch(repo).orElseThrow(() -> new IllegalArgumentException("Repository is not registered"));
        if (watch.webhookSecret() != null && !watch.webhookSecret().isBlank() && !validSignature(body, signature, watch.webhookSecret()))
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
    public record DiscoverJob(String title, String company, String sourceUrl) { }
    public record AssessRequest(String jobId, String repository, String branch) { }
    public record WatchRequest(String repository, String branch, boolean installGitHubWebhook) { }
}
