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
        Assessment assessment = buildAssessment(job, scan);
        String key = key(job.id(), canonical);
        store.saveAssessment(key, resolvedBranch, assessment);
        scans.put(repoKey(canonical), scan);
        subscriptions.put(key, new AssessmentSubscription(job, canonical, resolvedBranch));
        latestAssessments.put(key, assessment);
        return assessment;
    }
    private Assessment buildAssessment(JobRequirement job, PortfolioScan scan) {
        List<Gap> rules = job.requiredSkills().stream().map(skill -> {
            List<Evidence> evidence = scan.evidence().getOrDefault(skill, List.of());
            if (evidence.isEmpty()) return new Gap(skill, "NOT_DEMONSTRATED",
                    "No matching evidence was found in the scanned files. This is not proof you lack " + skill.label() + "; it only describes this repository at this revision.", evidence);
            return new Gap(skill, "DEMONSTRATED", "Evidence found in this repository revision.", evidence);
        }).toList();
        if (!coach.enabled()) return new Assessment(job, scan, rules);
        try {
            return aiAssessment(job, scan, rules, coach.review(job, scan));
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
            for (AssessmentSubscription subscription : subscriptions.values()) {
                if (!subscription.repository().equalsIgnoreCase(repository) || !subscription.branch().equals(r.branch())) continue;
                Assessment assessment = buildAssessment(subscription.job(), scan);
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
