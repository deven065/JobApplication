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
    private final Map<String, JobRequirement> savedJobs = new HashMap<>();
    public CareerCoachController(JobDiscoveryService jobs, AssessmentService assessments) { this.jobs = jobs; this.assessments = assessments; savedJobs.put("demo-backend", jobs.demoJob()); }

    @GetMapping("/jobs/demo") public JobRequirement demoJob() { return savedJobs.get("demo-backend"); }
    @PostMapping("/jobs/discover") public JobRequirement discover(@RequestBody DiscoverJob body) {
        JobRequirement job = jobs.discover(body.title(), body.company(), body.sourceUrl()); savedJobs.put(job.id(), job); return job;
    }
    @PostMapping("/assessments") public Assessment assess(@RequestBody AssessRequest body) {
        JobRequirement job = Optional.ofNullable(savedJobs.get(body.jobId())).orElseThrow(() -> new NoSuchElementException("Unknown job id"));
        return assessments.assess(job, body.repository(), body.branch());
    }
    @PostMapping("/repositories/watch") @ResponseStatus(HttpStatus.ACCEPTED)
    public void watch(@RequestBody WatchRequest body) { assessments.watch(new RepoRegistration(body.repository(), blankToMain(body.branch()), body.webhookSecret())); assessments.rescan(body.repository()); }
    @GetMapping("/repositories/{owner}/{repo}/last-scan") public PortfolioScan lastScan(@PathVariable String owner, @PathVariable String repo) {
        return assessments.lastScan(owner + "/" + repo).orElseThrow(() -> new NoSuchElementException("Repository has not been scanned"));
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
        assessments.rescan(repo);
    }
    private String blankToMain(String branch) { return branch == null || branch.isBlank() ? "main" : branch; }
    private boolean validSignature(byte[] body, String supplied, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body));
        return supplied != null && java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
    public record DiscoverJob(String title, String company, String sourceUrl) { }
    public record AssessRequest(String jobId, String repository, String branch) { }
    public record WatchRequest(String repository, String branch, String webhookSecret) { }
}
