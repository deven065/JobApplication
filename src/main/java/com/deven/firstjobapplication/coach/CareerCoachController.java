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
    private final AiCoach coach;
    private final JobBoardService boards;
    private final Map<String, JobRequirement> savedJobs;
    private final Map<String, String> jobOwners = new java.util.concurrent.ConcurrentHashMap<>();
    private AuthStore authStore;  // absent in unit tests; then every job is public
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void useAuthStore(AuthStore authStore) { this.authStore = authStore; jobOwners.putAll(authStore.jobOwners()); }
    public CareerCoachController(JobDiscoveryService jobs, AssessmentService assessments, GitHubPortfolioService github, StateStore store) {
        this(jobs, assessments, github, store, AiCoach.disabled());
    }
    public CareerCoachController(JobDiscoveryService jobs, AssessmentService assessments, GitHubPortfolioService github, StateStore store, AiCoach coach) {
        this(jobs, assessments, github, store, coach, new JobBoardService());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public CareerCoachController(JobDiscoveryService jobs, AssessmentService assessments, GitHubPortfolioService github, StateStore store, AiCoach coach, JobBoardService boards) {
        this.jobs = jobs; this.assessments = assessments; this.github = github; this.store = store; this.coach = coach; this.boards = boards;
        savedJobs = new java.util.concurrent.ConcurrentHashMap<>(store.jobs());
        savedJobs.put("demo-backend", jobs.demoJob());
    }

    @GetMapping("/ai/status") public AiStatus aiStatus() {
        return coach.enabled()
                ? new AiStatus(true, coach.provider(), coach.model(), "The AI reads job postings and reviews repository evidence.")
                : new AiStatus(false, "none", "", "AI review is off. Set GROQ_API_KEY or GEMINI_API_KEY (both free) and restart the backend. Keyword rules are used meanwhile.");
    }
    @GetMapping("/jobs/demo") public JobRequirement demoJob() { return savedJobs.get("demo-backend"); }
    /** Signed-in users see their own jobs on any device; signed-out visitors see jobs nobody owns. */
    @GetMapping("/jobs") public List<JobRequirement> savedJobs() {
        String login = GitHubCredentials.login();
        return savedJobs.values().stream().filter(job -> !job.id().equals("demo-backend"))
                .filter(job -> Objects.equals(jobOwners.get(job.id()), login))
                .sorted(Comparator.comparing(JobRequirement::fetchedAt).reversed()).toList();
    }
    private ReadyJobsService readyJobs;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void useReadyJobs(ReadyJobsService readyJobs) { this.readyJobs = readyJobs; }
    /** Open postings on the given boards, ranked by how much of each one this repository already proves. */
    @GetMapping("/jobs/ready") public ReadyJobResults readyJobs(@RequestParam String repository, @RequestParam String source,
                                                                @RequestParam String boards, @RequestParam(required = false) String q) {
        if (readyJobs == null) throw new IllegalStateException("Job matching is not available.");
        return readyJobs.rank(repository, source, Arrays.asList(boards.split("[,\\s]+")), q);
    }
    private GapActionService gapActions;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void useGapActions(GapActionService gapActions) { this.gapActions = gapActions; }
    /** "Fix this gap": opens a GitHub issue with a checklist in the student's own repository and keeps the repository watched. */
    @PostMapping("/gaps/issue") public IssueResult fixGap(@RequestBody FixGapRequest body) {
        if (gapActions == null) throw new IllegalStateException("Issue creation is not available.");
        JobRequirement job = accessible(body.jobId());
        boolean alreadyWatched = assessments.watch(body.repository()).isPresent();
        IssueResult result = gapActions.fixGap(job, body.repository(), body.skill(), GitHubCredentials.login());
        if (!alreadyWatched && authStore != null) authStore.ownWatch(body.repository().trim(), GitHubCredentials.login());
        return result;
    }
    /** Lists real postings from a company's public Greenhouse, Lever or Ashby board. */
    @GetMapping("/jobs/boards") public JobBoardResults searchBoard(@RequestParam String source, @RequestParam String board, @RequestParam(required = false) String q) {
        return boards.search(source, board, q);
    }
    /** Imports one board posting: its full text is read for requirements and its public URL is kept as the source. */
    @PostMapping("/jobs/import") public JobRequirement importPosting(@RequestBody ImportJob body) {
        JobBoardService.JobPosting posting = boards.fetch(body.source(), body.board(), body.jobId());
        if (posting.url().isBlank()) throw new IllegalStateException("The job board did not return a public link for this posting.");
        JobRequirement job = jobs.discover(posting.title(), posting.company(), posting.url(), posting.text());
        return remember(job);
    }
    @PostMapping("/jobs/discover") public JobRequirement discover(@RequestBody DiscoverJob body) {
        String text = body.postingText();
        // A Greenhouse, Lever or Ashby job link is read through the board's official API instead of scraping the page.
        if (text == null || text.isBlank()) {
            var link = JobBoardService.recognise(body.sourceUrl());
            if (link.isPresent()) text = boards.fetch(link.get().source(), link.get().board(), link.get().jobId()).text();
        }
        return remember(jobs.discover(body.title(), body.company(), body.sourceUrl(), text));
    }
    private JobRequirement remember(JobRequirement job) {
        store.saveJob(job); savedJobs.put(job.id(), job);
        String login = GitHubCredentials.login();
        if (login != null && authStore != null) { authStore.ownJob(job.id(), login); jobOwners.put(job.id(), login); }
        return job;
    }
    /** A job someone signed in to create is only visible to them. */
    private JobRequirement accessible(String jobId) {
        JobRequirement job = Optional.ofNullable(savedJobs.get(jobId)).orElseThrow(() -> new NoSuchElementException("Unknown job id"));
        String owner = jobOwners.get(jobId);
        if (owner != null && !owner.equals(GitHubCredentials.login())) throw new SecurityException("This job belongs to another account. Sign in as its owner.");
        return job;
    }
    @PutMapping("/jobs/{id}/requirements") public JobRequirement updateRequirements(@PathVariable String id, @RequestBody RequirementsUpdate body) {
        JobRequirement existing = accessible(id);
        if (body.skills() == null || body.skills().isEmpty()) throw new IllegalArgumentException("Select at least one requirement.");
        List<Skill> skills = body.skills().stream().distinct().toList();
        List<RequirementQuote> quotes = Optional.ofNullable(existing.requirementQuotes()).orElse(List.of()).stream()
                .filter(quote -> skills.contains(quote.skill())).toList();
        JobRequirement updated = new JobRequirement(existing.id(), existing.title(), existing.company(), existing.sourceUrl(),
                existing.sourceExcerpt(), skills, existing.fetchedAt(), quotes, existing.extractedBy());
        if (!id.equals("demo-backend")) store.saveJob(updated);
        assessments.invalidateJob(id);
        savedJobs.put(id, updated);
        return updated;
    }
    @PostMapping("/assessments") public Assessment assess(@RequestBody AssessRequest body) {
        return assessments.assess(accessible(body.jobId()), body.repository(), body.branch());
    }
    /** The job judged against several already-assessed repositories at once (the best evidence per requirement wins). */
    @GetMapping("/assessments/combined") public CombinedPortfolio combined(@RequestParam String jobId, @RequestParam String repositories) {
        JobRequirement job = accessible(jobId);
        List<String> names = Arrays.stream(repositories.split(",")).map(String::trim).filter(n -> !n.isEmpty()).distinct().limit(5).toList();
        List<Assessment> found = new ArrayList<>();
        for (String name : names)
            found.add(assessments.latestAssessment(jobId, name).orElseThrow(() -> new NoSuchElementException(name + " has not been assessed against this job yet.")));
        return PortfolioMerger.merge(job, found);
    }
    @GetMapping("/repositories/resolve") public GitHubRepository resolveRepository(@RequestParam String repository) { return github.repository(repository); }
    @GetMapping("/repositories/watch") public WatchStatus watchStatus(@RequestParam String repository) {
        RepoRegistration watch = assessments.watch(repository)
                .orElseThrow(() -> new NoSuchElementException("Repository is not being watched"));
        String revision = assessments.lastScan(repository).map(PortfolioScan::revision).orElse("");
        String pending = assessments.pendingRevision(repository).orElse(null);
        return new WatchStatus(watch.repository(), watch.branch(), revision,
                watch.webhookSecret() != null && !watch.webhookSecret().isBlank(),
                pending == null ? "Watching for repository changes." : "New commit detected. Reassessing.", pending);
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
        // Background checks of this repository use the watcher's GitHub access (private repos, higher rate limit).
        if (GitHubCredentials.login() != null && authStore != null) authStore.ownWatch(repo.fullName(), GitHubCredentials.login());
        String message = body.installGitHubWebhook() || alreadyInstalled
                ? "Watching changes with a GitHub webhook and one-minute polling."
                : "Watching changes. GitHub will be checked every minute.";
        return new WatchStatus(repo.fullName(), branch, latest.revision(), body.installGitHubWebhook() || alreadyInstalled, message, null);
    }
    @GetMapping("/repositories/{owner}/{repo}/last-scan") public PortfolioScan lastScan(@PathVariable String owner, @PathVariable String repo) {
        return assessments.lastScan(owner + "/" + repo).orElseThrow(() -> new NoSuchElementException("Repository has not been scanned"));
    }
    @GetMapping("/assessments/latest") public Assessment latestAssessment(@RequestParam String jobId, @RequestParam String repository) {
        accessible(jobId);
        return assessments.latestAssessment(jobId, repository).orElseThrow(() -> new NoSuchElementException("No tracked assessment found. Assess and watch this repository first."));
    }
    @GetMapping("/assessments/history") public List<Assessment> history(@RequestParam String jobId, @RequestParam String repository) {
        if (savedJobs.containsKey(jobId)) accessible(jobId);
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
        if (!pushedBranch.equals("refs/heads/" + watch.branch())) return;
        String after = payload.path("after").asText(null);
        var owner = authStore == null ? Optional.<AuthStore.Session>empty() : authStore.watchOwner(repo);
        if (owner.isPresent()) GitHubCredentials.as(owner.get().user().login(), owner.get().token(), () -> assessments.reassessRepository(repo, after));
        else assessments.reassessRepository(repo, after);
    }
    private String blankToDefault(String branch, String defaultBranch) { return branch == null || branch.isBlank() ? defaultBranch : branch.trim(); }
    private boolean validSignature(byte[] body, String supplied, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body));
        return supplied != null && java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
    public record DiscoverJob(String title, String company, String sourceUrl, String postingText) { }
    public record ImportJob(String source, String board, String jobId) { }
    public record FixGapRequest(String jobId, String repository, Skill skill) { }
    public record AssessRequest(String jobId, String repository, String branch) { }
    public record WatchRequest(String repository, String branch, boolean installGitHubWebhook) { }
    public record RequirementsUpdate(List<Skill> skills) { }
}
