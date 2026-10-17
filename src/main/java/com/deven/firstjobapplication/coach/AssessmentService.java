package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AssessmentService {
    private static final Logger log = LoggerFactory.getLogger(AssessmentService.class);
    private final GitHubPortfolioService github;
    private final AiCoach coach;
    private final StateStore store;
    private final Map<String, RepoRegistration> watched = new ConcurrentHashMap<>();
    private final Map<String, PortfolioScan> scans = new ConcurrentHashMap<>();
    private final Map<String, Assessment> latestAssessments = new ConcurrentHashMap<>();
    private final Map<String, AssessmentSubscription> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, String> pendingRevisions = new ConcurrentHashMap<>();
    public AssessmentService(GitHubPortfolioService github, StateStore store) { this(github, store, AiCoach.disabled()); }
    @Autowired public AssessmentService(GitHubPortfolioService github, StateStore store, AiCoach coach) {
        this.github = github;
        this.coach = coach;
        this.store = store;
        for (RepoRegistration watch : store.watches()) watched.put(repoKey(watch.repository()), watch);
        for (StateStore.SavedAssessment saved : store.assessments()) {
            Assessment assessment = saved.assessment();
            String repository = assessment.portfolio().repository();
            String key = key(assessment.job().id(), repository);
            latestAssessments.put(key, assessment);
            scans.put(repoKey(repository), assessment.portfolio());
            subscriptions.put(key, new AssessmentSubscription(assessment.job(), repository, saved.branch()));
        }
    }

    public Assessment assess(JobRequirement job, String repository, String branch) {
        GitHubRepository resolved = github.repository(repository);
        String canonical = resolved.fullName();
        String resolvedBranch = branch == null || branch.isBlank() ? resolved.defaultBranch() : branch.trim();
        PortfolioScan scan = github.scan(canonical, resolvedBranch);
        String key = key(job.id(), canonical);
        // A manual rescan of a newer commit is treated like a detected change: only affected skills are re-run.
        Assessment assessment = evaluate(job, scan, latestAssessments.get(key), new HashMap<>());
        store.saveAssessment(key, resolvedBranch, assessment);
        scans.put(repoKey(canonical), scan);
        subscriptions.put(key, new AssessmentSubscription(job, canonical, resolvedBranch));
        latestAssessments.put(key, assessment);
        return assessment;
    }
    /**
     * Builds the assessment for a new scan. With a previous assessment of an older commit, GitHub's compare API says
     * which files changed; a skill is re-evaluated only if a changed file was among its previous evidence or its new
     * candidates. Every other skill keeps its verdict, with evidence links moved to the new commit (the files are
     * identical there). If the change list is unavailable or incomplete, everything is re-evaluated.
     */
    private Assessment evaluate(JobRequirement job, PortfolioScan scan, Assessment previous, Map<String, ChangeSet> compareCache) {
        boolean comparable = previous != null && previous.portfolio() != null
                && !previous.portfolio().revision().equals(scan.revision())
                && previous.portfolio().repository().equalsIgnoreCase(scan.repository())
                && new HashSet<>(previous.job().requiredSkills()).equals(new HashSet<>(job.requiredSkills()));
        if (!comparable) return buildAssessment(job, scan, Map.of());
        String base = previous.portfolio().revision();
        ChangeSet diff = compareCache.computeIfAbsent(base, b -> {
            try { return github.compare(scan.repository(), b, scan.revision()); }
            catch (Exception error) { log.warn("Could not compare {}..{}; re-evaluating every skill", b, scan.revision(), error); return null; }
        });
        if (diff == null || !diff.complete()) {
            Assessment full = buildAssessment(job, scan, Map.of());
            ChangeSet change = new ChangeSet(base, scan.revision(), diff == null ? List.of() : diff.files(), job.requiredSkills(), List.of(), false);
            return withChange(full, full.gaps(), full.summary(), full.assessedBy(), change);
        }
        Set<String> changedPaths = new HashSet<>();
        for (ChangedFile file : diff.files()) { changedPaths.add(file.path()); if (file.previousPath() != null) changedPaths.add(file.previousPath()); }
        Map<Skill, Gap> before = new EnumMap<>(Skill.class);
        for (Gap gap : previous.gaps()) before.put(gap.skill(), gap);
        List<Skill> affected = new ArrayList<>(), unchanged = new ArrayList<>();
        for (Skill skill : job.requiredSkills()) {
            Gap old = before.get(skill);
            boolean touched = old == null
                    || old.evidence().stream().anyMatch(e -> changedPaths.contains(e.path()))
                    || scan.evidence().getOrDefault(skill, List.of()).stream().anyMatch(e -> changedPaths.contains(e.path()));
            (touched ? affected : unchanged).add(skill);
        }
        ChangeSet change = new ChangeSet(base, scan.revision(), diff.files(), affected, unchanged, true);
        Map<Skill, Gap> merged = new EnumMap<>(Skill.class);
        for (Skill skill : unchanged) merged.put(skill, movedToCommit(before.get(skill), base, scan.revision()));
        String summary = previous.summary(), assessedBy = previous.assessedBy();
        if (!affected.isEmpty()) {
            JobRequirement subset = new JobRequirement(job.id(), job.title(), job.company(), job.sourceUrl(), job.sourceExcerpt(), affected, job.fetchedAt(),
                    Optional.ofNullable(job.requirementQuotes()).orElse(List.of()).stream().filter(q -> affected.contains(q.skill())).toList(), job.extractedBy());
            Map<Skill, String> context = new EnumMap<>(Skill.class);
            for (Skill skill : unchanged) context.put(skill, merged.get(skill).status());
            Assessment partial = buildAssessment(subset, scan, context);
            for (Gap gap : partial.gaps()) merged.put(gap.skill(), gap);
            summary = partial.summary(); assessedBy = partial.assessedBy();
        }
        List<Gap> gaps = job.requiredSkills().stream().map(merged::get).toList();
        return new Assessment(job, scan, gaps, summary, assessedBy, change);
    }
    private static Assessment withChange(Assessment base, List<Gap> gaps, String summary, String assessedBy, ChangeSet change) {
        return new Assessment(base.job(), base.portfolio(), gaps, summary, assessedBy, change);
    }
    /** The file behind this evidence did not change, so the same lines exist at the new commit. */
    private static Gap movedToCommit(Gap gap, String fromRevision, String toRevision) {
        List<Evidence> moved = gap.evidence().stream().map(e -> new Evidence(e.skill(), e.path(),
                e.url() == null ? null : e.url().replace("/blob/" + fromRevision + "/", "/blob/" + toRevision + "/"),
                e.reason(), e.snippet(), e.excerpt())).toList();
        return new Gap(gap.skill(), gap.status(), gap.explanation(), moved, gap.confidence(), gap.nextStep(), gap.assessedBy());
    }
    private Assessment buildAssessment(JobRequirement job, PortfolioScan scan, Map<Skill, String> unchangedContext) {
        List<Gap> rules = job.requiredSkills().stream().map(skill -> {
            List<Evidence> evidence = scan.evidence().getOrDefault(skill, List.of());
            if (evidence.isEmpty()) return new Gap(skill, "NOT_DEMONSTRATED",
                    "No matching evidence was found in the scanned files. This is not proof you lack " + skill.label() + "; it only describes this repository at this revision.", evidence);
            return new Gap(skill, "DEMONSTRATED", "Evidence found in this repository revision.", evidence);
        }).toList();
        if (!coach.enabled()) return new Assessment(job, scan, rules);
        try {
            AiCoach.PortfolioReview review = unchangedContext.isEmpty() ? coach.review(job, scan) : coach.review(job, scan, unchangedContext);
            return aiAssessment(job, scan, rules, review);
        } catch (Exception error) {
            log.warn("AI review failed; using keyword rules", error);
            return new Assessment(job, scan, rules, aiUnavailable(error), "rules");
        }
    }
    /** Explains an AI failure in terms the student can act on; shown instead of the coach summary. */
    static String aiUnavailable(Exception error) {
        int status = error instanceof org.springframework.web.client.RestClientResponseException http ? http.getStatusCode().value()
                : error instanceof com.anthropic.errors.AnthropicServiceException api ? api.statusCode() : 0;
        String reason = switch (status) {
            case 401, 403 -> "The AI provider rejected the API key. Create a new key, put it in .env, restart the backend and click Rescan now.";
            case 429 -> "The AI provider's free rate limit was reached. Wait a minute, then click Rescan now.";
            case 404 -> "The configured AI model was not found. Set a current model name in .env (for example GROQ_MODEL) and restart the backend.";
            default -> status >= 500 ? "The AI provider is temporarily unavailable. Try Rescan now in a moment."
                    : "The AI review could not be completed for this scan. Try Rescan now.";
        };
        return "AI review unavailable: " + reason + " These results come from keyword rules only.";
    }
    /** Merges the AI verdicts with the scan, enforcing that every positive claim cites a scanned file. */
    private Assessment aiAssessment(JobRequirement job, PortfolioScan scan, List<Gap> rules, AiCoach.PortfolioReview review) {
        Map<Skill, AiCoach.SkillVerdict> verdicts = new EnumMap<>(Skill.class);
        for (AiCoach.SkillVerdict v : Optional.ofNullable(review.verdicts()).orElse(List.of()))
            if (v != null && v.skill() != null && v.status() != null) verdicts.putIfAbsent(v.skill(), v);
        List<Gap> gaps = new ArrayList<>();
        for (Gap rule : rules) {
            AiCoach.SkillVerdict v = verdicts.get(rule.skill());
            if (v == null) { gaps.add(rule); continue; }
            List<Evidence> candidates = coach.candidates(scan, rule.skill());
            Set<String> cited = new HashSet<>(Optional.ofNullable(v.citedPaths()).orElse(List.of()));
            List<Evidence> supporting = candidates.stream().filter(e -> cited.contains(e.path())).toList();
            String status = v.status().name();
            String confidence = v.confidence() == null ? "MEDIUM" : v.confidence().name();
            String explanation = v.explanation();
            if (candidates.isEmpty()) {
                status = "NOT_DEMONSTRATED";
                explanation = "The scanner found no candidate files for " + rule.skill().label() + ". This is not proof you lack the skill; it only describes this repository at this revision.";
            } else if (!status.equals("NOT_DEMONSTRATED") && supporting.isEmpty()) {
                // A positive verdict without a traceable file is not accepted as-is.
                status = "PARTIAL"; confidence = "LOW"; supporting = candidates;
                explanation = explanation + " (The reviewer did not cite a specific file, so this is marked partial.)";
            }
            // For rejected skills, show which signals were reviewed so the student can see why.
            List<Evidence> shown = status.equals("NOT_DEMONSTRATED") ? candidates : supporting;
            gaps.add(new Gap(rule.skill(), status, explanation, shown, confidence, v.nextStep(), coach.label()));
        }
        return new Assessment(job, scan, gaps, review.summary(), coach.label());
    }
    public void watch(RepoRegistration registration) {
        store.saveWatch(registration);
        watched.put(repoKey(registration.repository()), registration);
    }
    public Collection<RepoRegistration> watches() { return watched.values(); }
    public Optional<RepoRegistration> watch(String repository) { return Optional.ofNullable(watched.get(repoKey(repository))); }
    public Optional<PortfolioScan> lastScan(String repository) { return Optional.ofNullable(scans.get(repoKey(repository))); }
    public List<Assessment> reassessRepository(String repository) { return reassessRepository(repository, null); }
    /** detectedRevision (from polling or a push payload) is reported as pending until every assessment is rebuilt. */
    public List<Assessment> reassessRepository(String repository, String detectedRevision) {
        RepoRegistration r = watched.get(repoKey(repository));
        if (r == null) return List.of();
        if (detectedRevision != null && !detectedRevision.isBlank()) pendingRevisions.put(repoKey(repository), detectedRevision);
        try {
            PortfolioScan scan = github.scan(r.repository(), r.branch());
            List<Assessment> updated = new ArrayList<>();
            Map<String, ChangeSet> compareCache = new HashMap<>();  // one compare call per base commit, shared by all jobs
            for (AssessmentSubscription subscription : subscriptions.values()) {
                if (!subscription.repository().equalsIgnoreCase(repository) || !subscription.branch().equals(r.branch())) continue;
                Assessment assessment = evaluate(subscription.job(), scan, latestAssessments.get(key(subscription.job().id(), repository)), compareCache);
                store.saveAssessment(key(subscription.job().id(), repository), subscription.branch(), assessment);
                latestAssessments.put(key(subscription.job().id(), repository), assessment);
                updated.add(assessment);
            }
            // Publish the new scan only after its assessments exist, so clients never see a half-finished update.
            scans.put(repoKey(repository), scan);
            return updated;
        } finally {
            pendingRevisions.remove(repoKey(repository));
        }
    }
    public Optional<String> pendingRevision(String repository) { return Optional.ofNullable(pendingRevisions.get(repoKey(repository))); }
    public Optional<Assessment> latestAssessment(String jobId, String repository) { return Optional.ofNullable(latestAssessments.get(key(jobId, repository))); }
    public List<Assessment> history(String jobId, String repository) { return store.history(key(jobId, repository)); }
    public void invalidateJob(String jobId) {
        store.deleteCurrentAssessments(jobId);
        String prefix = jobId + "|";
        subscriptions.keySet().removeIf(key -> key.startsWith(prefix));
        latestAssessments.keySet().removeIf(key -> key.startsWith(prefix));
    }
    private String key(String jobId, String repository) { return jobId + "|" + repoKey(repository); }
    private String repoKey(String repository) { return repository.trim().toLowerCase(Locale.ROOT); }
    private record AssessmentSubscription(JobRequirement job, String repository, String branch) { }
}
