package com.deven.firstjobapplication.coach;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class Models {
    private Models() { }

    public record JobRequirement(String id, String title, String company, String sourceUrl,
                                 String sourceExcerpt, List<Skill> requiredSkills, Instant fetchedAt) { }
    public record Evidence(Skill skill, String path, String url, String reason, String snippet) { }
    public record PortfolioScan(String repository, String revision, Instant scannedAt,
                                Map<Skill, List<Evidence>> evidence) { }
    public record Gap(Skill skill, String status, String explanation, List<Evidence> evidence) { }
    public record Assessment(JobRequirement job, PortfolioScan portfolio, List<Gap> gaps) { }
    public record RepoRegistration(String repository, String branch, String webhookSecret) { }
    public record GitHubRepository(String fullName, String defaultBranch, String htmlUrl, boolean privateRepository) { }
    public record WatchStatus(String repository, String branch, String revision, boolean webhookCreated, String message) { }
}
