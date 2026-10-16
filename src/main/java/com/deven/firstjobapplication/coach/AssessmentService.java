package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AssessmentService {
    private final GitHubPortfolioService github;
    private final StateStore store;
    private final Map<String, RepoRegistration> watched = new ConcurrentHashMap<>();
    private final Map<String, PortfolioScan> scans = new ConcurrentHashMap<>();
    private final Map<String, Assessment> latestAssessments = new ConcurrentHashMap<>();
    private final Map<String, AssessmentSubscription> subscriptions = new ConcurrentHashMap<>();
    public AssessmentService(GitHubPortfolioService github, StateStore store) {
        this.github = github;
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
        String resolvedBranch = branch == null || branch.isBlank() ? resolved.defaultBranch() : branch;
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
        List<Gap> gaps = job.requiredSkills().stream().map(skill -> {
            List<Evidence> evidence = scan.evidence().getOrDefault(skill, List.of());
            if (evidence.isEmpty()) return new Gap(skill, "NOT_DEMONSTRATED",
                    "No matching evidence was found in the scanned files. This is not proof you lack " + skill.label() + "; it only describes this repository at this revision.", evidence);
            return new Gap(skill, "DEMONSTRATED", "Evidence found in this repository revision.", evidence);
        }).toList();
        return new Assessment(job, scan, gaps);
    }
    public void watch(RepoRegistration registration) {
        store.saveWatch(registration);
        watched.put(repoKey(registration.repository()), registration);
    }
    public Collection<RepoRegistration> watches() { return watched.values(); }
    public Optional<RepoRegistration> watch(String repository) { return Optional.ofNullable(watched.get(repoKey(repository))); }
    public Optional<PortfolioScan> lastScan(String repository) { return Optional.ofNullable(scans.get(repoKey(repository))); }
    public List<Assessment> reassessRepository(String repository) {
        RepoRegistration r = watched.get(repoKey(repository));
        if (r == null) return List.of();
        PortfolioScan scan = github.scan(r.repository(), r.branch());
        scans.put(repoKey(repository), scan);
        List<Assessment> updated = new ArrayList<>();
        for (AssessmentSubscription subscription : subscriptions.values()) {
            if (!subscription.repository().equalsIgnoreCase(repository) || !subscription.branch().equals(r.branch())) continue;
            Assessment assessment = buildAssessment(subscription.job(), scan);
            store.saveAssessment(key(subscription.job().id(), repository), subscription.branch(), assessment);
            latestAssessments.put(key(subscription.job().id(), repository), assessment);
            updated.add(assessment);
        }
        return updated;
    }
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
