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
    /** change is null for a first assessment; otherwise it records what the commit changed and which skills were re-run. */
    public record Assessment(JobRequirement job, PortfolioScan portfolio, List<Gap> gaps, String summary, String assessedBy, ChangeSet change) {
        public Assessment(JobRequirement job, PortfolioScan portfolio, List<Gap> gaps, String summary, String assessedBy) {
            this(job, portfolio, gaps, summary, assessedBy, null);
        }
        public Assessment(JobRequirement job, PortfolioScan portfolio, List<Gap> gaps) {
            this(job, portfolio, gaps, null, "rules", null);
        }
    }
    /** One file touched between two scanned commits. previousPath is set for renames. */
    public record ChangedFile(String path, String status, String url, String previousPath) { }
    /**
     * The difference between the previous and the new scanned commit. Only skills whose evidence or candidate files
     * changed are re-evaluated; the rest keep their verdicts. complete=false means GitHub could not list every change,
     * so every skill was re-evaluated.
     */
    public record ChangeSet(String baseRevision, String headRevision, List<ChangedFile> files,
                            List<Skill> reassessedSkills, List<Skill> carriedOverSkills, boolean complete) { }
    /** A posting listed on a public job board (Greenhouse, Lever or Ashby). */
    public record JobListing(String source, String board, String id, String title, String company, String location, String url) { }
    public record JobBoardResults(String source, String board, String company, int total, List<JobListing> jobs) { }
    /** The signed-in GitHub user. privateAccess is true when they granted the repo scope. */
    public record AuthUser(String login, String name, String avatarUrl, boolean privateAccess) { }
    public record AuthStatus(boolean configured, AuthUser user) { }
    public record RepoSummary(String fullName, boolean privateRepository, String defaultBranch, String language, String description, Instant pushedAt) { }
    /** A posting scored against the skills the student's repository already shows. score is 0–100. */
    public record ReadyJob(JobListing listing, List<Skill> required, List<Skill> met, List<Skill> partial, List<Skill> missing, int score) { }
    public record ReadyJobResults(String repository, String revision, int scanned, List<ReadyJob> jobs, List<String> warnings) { }
    public record RepoRegistration(String repository, String branch, String webhookSecret) { }
    public record GitHubRepository(String fullName, String defaultBranch, String htmlUrl, boolean privateRepository) { }
    /** revision is the last fully assessed commit; pendingRevision is a newly detected commit still being reassessed. */
    public record WatchStatus(String repository, String branch, String revision, boolean webhookCreated, String message, String pendingRevision) { }
    public record AiStatus(boolean enabled, String provider, String model, String message) { }
}
