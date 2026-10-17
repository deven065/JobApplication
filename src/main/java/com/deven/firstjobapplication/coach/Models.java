package com.deven.firstjobapplication.coach;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class Models {
    private Models() { }

    /** A requirement the AI found in the posting, with the exact sentence that justifies it. */
    public record RequirementQuote(Skill skill, String quote, String importance) { }
    public record JobRequirement(String id, String title, String company, String sourceUrl,
                                 String sourceExcerpt, List<Skill> requiredSkills, Instant fetchedAt,
                                 List<RequirementQuote> requirementQuotes, String extractedBy) {
        public JobRequirement(String id, String title, String company, String sourceUrl,
                              String sourceExcerpt, List<Skill> requiredSkills, Instant fetchedAt) {
            this(id, title, company, sourceUrl, sourceExcerpt, requiredSkills, fetchedAt, List.of(), "rules");
        }
    }
    /** snippet is the first matching line; excerpt is the surrounding code the AI reviewer reads. */
    public record Evidence(Skill skill, String path, String url, String reason, String snippet, String excerpt) {
        public Evidence(Skill skill, String path, String url, String reason, String snippet) {
            this(skill, path, url, reason, snippet, snippet);
        }
    }
    public record PortfolioScan(String repository, String branch, String revision, Instant scannedAt,
                                Map<Skill, List<Evidence>> evidence, int scannedFiles,
                                int skippedFiles, boolean treeTruncated) { }
    /** status: DEMONSTRATED, PARTIAL or NOT_DEMONSTRATED. confidence: HIGH, MEDIUM, LOW. */
    public record Gap(Skill skill, String status, String explanation, List<Evidence> evidence,
                      String confidence, String nextStep, String assessedBy) {
        public Gap(Skill skill, String status, String explanation, List<Evidence> evidence) {
            this(skill, status, explanation, evidence, null, null, "rules");
        }
    }
    public record Assessment(JobRequirement job, PortfolioScan portfolio, List<Gap> gaps, String summary, String assessedBy) {
        public Assessment(JobRequirement job, PortfolioScan portfolio, List<Gap> gaps) {
            this(job, portfolio, gaps, null, "rules");
        }
    }
    public record RepoRegistration(String repository, String branch, String webhookSecret) { }
    public record GitHubRepository(String fullName, String defaultBranch, String htmlUrl, boolean privateRepository) { }
    /** revision is the last fully assessed commit; pendingRevision is a newly detected commit still being reassessed. */
    public record WatchStatus(String repository, String branch, String revision, boolean webhookCreated, String message, String pendingRevision) { }
    public record AiStatus(boolean enabled, String provider, String model, String message) { }
}
