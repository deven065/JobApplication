package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AssessmentService {
    private final GitHubPortfolioService github;
    private final Map<String, RepoRegistration> watched = new ConcurrentHashMap<>();
    private final Map<String, PortfolioScan> scans = new ConcurrentHashMap<>();
    private final Map<String, Assessment> latestAssessments = new ConcurrentHashMap<>();
    private final Map<String, AssessmentSubscription> subscriptions = new ConcurrentHashMap<>();
    public AssessmentService(GitHubPortfolioService github) { this.github = github; }

    public Assessment assess(JobRequirement job, String repository, String branch) {
        String resolvedBranch = branch == null || branch.isBlank() ? github.repository(repository).defaultBranch() : branch;
        PortfolioScan scan = github.scan(repository, resolvedBranch);
        scans.put(repository, scan);
        Assessment assessment = buildAssessment(job, scan);
        String key = key(job.id(), repository);
        subscriptions.put(key, new AssessmentSubscription(job, repository, resolvedBranch));
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
    public void watch(RepoRegistration registration) { watched.put(registration.repository(), registration); }
    public Collection<RepoRegistration> watches() { return watched.values(); }
    public Optional<RepoRegistration> watch(String repository) { return Optional.ofNullable(watched.get(repository)); }
    public Optional<PortfolioScan> lastScan(String repository) { return Optional.ofNullable(scans.get(repository)); }
    public List<Assessment> reassessRepository(String repository) {
        RepoRegistration r = watched.get(repository);
        if (r == null) return List.of();
        PortfolioScan scan = github.scan(r.repository(), r.branch());
        scans.put(repository, scan);
        List<Assessment> updated = new ArrayList<>();
        for (AssessmentSubscription subscription : subscriptions.values()) {
            if (!subscription.repository().equals(repository)) continue;
            Assessment assessment = buildAssessment(subscription.job(), scan);
            latestAssessments.put(key(subscription.job().id(), repository), assessment);
            updated.add(assessment);
        }
        return updated;
    }
    public Optional<Assessment> latestAssessment(String jobId, String repository) { return Optional.ofNullable(latestAssessments.get(key(jobId, repository))); }
    private String key(String jobId, String repository) { return jobId + "|" + repository; }
    private record AssessmentSubscription(JobRequirement job, String repository, String branch) { }
}
