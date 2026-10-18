package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/**
 * "Fix this gap": opens a GitHub Issue in the student's own repository with a checklist for the missing skill, and makes sure the
 * repository is watched. Closing the issue with a commit changes the repository, which ProofPath's change detection then re-checks.
 */
@Service
public class GapActionService {
    private final JdbcTemplate jdbc;
    private final GitHubPortfolioService github;
    private final AssessmentService assessments;

    public GapActionService(JdbcTemplate jdbc, GitHubPortfolioService github, AssessmentService assessments) {
        this.jdbc = jdbc; this.github = github; this.assessments = assessments;
        jdbc.execute("CREATE TABLE IF NOT EXISTS coach_issues (repository VARCHAR(255) NOT NULL, skill VARCHAR(64) NOT NULL, issue_number INT NOT NULL, url TEXT NOT NULL, PRIMARY KEY (repository, skill))");
    }

    public IssueResult fixGap(JobRequirement job, String repository, Skill skill, String login) {
        if (login == null || GitHubCredentials.token() == null) throw new SecurityException("Sign in with GitHub to let ProofPath open an issue in your repository.");
        String repo = repository.trim();
        Assessment assessment = assessments.latestAssessment(job.id(), repo)
                .orElseThrow(() -> new NoSuchElementException("Assess this repository against the job first."));
        Gap gap = assessment.gaps().stream().filter(g -> g.skill() == skill).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(skill.label() + " is not one of this job's requirements."));
        if ("DEMONSTRATED".equals(gap.status())) throw new IllegalArgumentException(skill.label() + " is already demonstrated. There is no gap to fix.");

        String key = repo.toLowerCase(Locale.ROOT);
        boolean watching = ensureWatched(assessment.portfolio(), repo);
        List<java.util.Map<String, Object>> known = jdbc.queryForList("SELECT issue_number, url FROM coach_issues WHERE repository = ? AND skill = ?", key, skill.name());
        if (!known.isEmpty()) {
            int number = ((Number) known.get(0).get("issue_number")).intValue();
            String url = (String) known.get(0).get("url");
            if (isOpen(repo, number)) return new IssueResult(number, url, title(job, skill), true, watching);
        }
        String title = title(job, skill);
        GitHubPortfolioService.CreatedIssue created;
        try { created = github.createIssue(repo, title, body(job, gap, login)); }
        catch (RestClientResponseException error) {
            int status = error.getStatusCode().value();
            if (status == 404 || status == 403)
                throw new SecurityException("GitHub would not let ProofPath open an issue in " + repo + ". Allow issue access when signing in, and check that you can write to this repository.");
            if (status == 410) throw new IllegalStateException("Issues are turned off for " + repo + ". Enable them in the repository settings.");
            throw error;
        }
        jdbc.update("DELETE FROM coach_issues WHERE repository = ? AND skill = ?", key, skill.name());
        jdbc.update("INSERT INTO coach_issues (repository, skill, issue_number, url) VALUES (?, ?, ?, ?)", key, skill.name(), created.number(), created.url());
        return new IssueResult(created.number(), created.url(), title, false, watching);
    }

    /** Closing the issue is a commit to the repository, so it has to be watched for the automatic re-check to happen. */
    private boolean ensureWatched(PortfolioScan scan, String repository) {
        if (assessments.watch(repository).isPresent()) return true;
        assessments.watch(new RepoRegistration(scan.repository(), scan.branch(), ""));
        return true;
    }
    private boolean isOpen(String repository, int number) {
        try { return github.issueIsOpen(repository, number); }
        catch (RuntimeException unknown) { return false; }
    }

    static String title(JobRequirement job, Skill skill) { return "Show " + skill.label() + " in this repo (" + job.title() + ")"; }

    static String body(JobRequirement job, Gap gap, String login) {
        LearningPath path = LearningPaths.of(gap.skill());
        StringBuilder text = new StringBuilder();
        text.append("## Goal\n**").append(job.title()).append(" at ").append(job.company()).append("** asks for **").append(gap.skill().label())
                .append("**. ProofPath could not find enough of it in this repository (").append(gap.status().toLowerCase(Locale.ROOT).replace('_', ' ')).append(").\n\n");
        if (gap.explanation() != null && !gap.explanation().isBlank()) text.append("## What ProofPath saw\n").append(gap.explanation().trim()).append("\n\n");
        text.append("## Checklist\n");
        if (gap.nextStep() != null && !gap.nextStep().isBlank()) text.append("- [ ] ").append(gap.nextStep().trim()).append('\n');
        for (String step : path.steps()) text.append("- [ ] ").append(step).append('\n');
        text.append("\nEstimated time: ").append(path.estimate()).append(". Small project idea: ").append(path.project()).append("\n\n");
        text.append("## How it gets re-checked\nProofPath watches this repository. Push the commit that closes this issue and ProofPath re-reads the changed files, then updates the ")
                .append(gap.skill().label()).append(" verdict automatically.\n\n");
        if (job.sourceUrl() != null && !job.sourceUrl().isBlank()) text.append("Job posting: ").append(job.sourceUrl()).append("\n");
        text.append("_Opened by ProofPath for @").append(login).append("._\n");
        return text.toString();
    }
}
