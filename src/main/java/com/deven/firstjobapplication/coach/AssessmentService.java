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
    public AssessmentService(GitHubPortfolioService github) { this.github = github; }

    public Assessment assess(JobRequirement job, String repository, String branch) {
        PortfolioScan scan = github.scan(repository, branch);
        scans.put(repository, scan);
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
    public void rescan(String repository) {
        RepoRegistration r = watched.get(repository);
        if (r != null) scans.put(repository, github.scan(r.repository(), r.branch()));
    }
}
