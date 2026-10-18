package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * The public "verified skills" page. A student publishes one repository they own; only skills the latest assessment marked
 * DEMONSTRATED appear, each with links to the exact files (pinned to the scanned commit) that prove it. Private repositories
 * can never be published, because the page and its links are visible to everyone.
 */
@Service
public class ProfileService {
    private final JdbcTemplate jdbc;
    private final AssessmentService assessments;
    private final GitHubPortfolioService github;
    private final String baseUrl;

    public ProfileService(JdbcTemplate jdbc, AssessmentService assessments, GitHubPortfolioService github,
                          @Value("${app.base-url:http://localhost:5173}") String baseUrl) {
        this.jdbc = jdbc; this.assessments = assessments; this.github = github;
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        jdbc.execute("CREATE TABLE IF NOT EXISTS coach_profiles (login VARCHAR(128) PRIMARY KEY, repository VARCHAR(255) NOT NULL, avatar_url TEXT, published_at BIGINT NOT NULL)");
    }

    public PublicProfile publish(AuthUser user, String repository) {
        if ("email".equals(user.provider()))
            throw new IllegalArgumentException("Publishing a verified-skills page needs a GitHub sign-in, so ProofPath can confirm the repository is yours.");
        String repo = repository == null ? "" : repository.trim();
        if (!repo.toLowerCase(Locale.ROOT).startsWith(user.login().toLowerCase(Locale.ROOT) + "/"))
            throw new IllegalArgumentException("You can publish a repository you own (" + user.login() + "/...).");
        GitHubRepository details = github.repository(repo);
        if (details.privateRepository())
            throw new IllegalArgumentException("Private repositories cannot be published, because the profile page is public. Make the repository public first.");
        assessments.lastScan(repo).orElseThrow(() -> new IllegalStateException("Assess this repository before publishing it."));
        if (ResumeService.demonstrated(assessments.latestForRepository(repo)).isEmpty())
            throw new IllegalStateException("No skill is demonstrated yet, so there is nothing to publish. Fix a gap first.");
        jdbc.update("DELETE FROM coach_profiles WHERE login = ?", user.login());
        jdbc.update("INSERT INTO coach_profiles (login, repository, avatar_url, published_at) VALUES (?, ?, ?, ?)",
                user.login(), details.fullName(), user.avatarUrl(), System.currentTimeMillis());
        return find(user.login()).orElseThrow();
    }

    public void unpublish(String login) { jdbc.update("DELETE FROM coach_profiles WHERE login = ?", login); }

    public Optional<PublicProfile> find(String login) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT login, repository, avatar_url FROM coach_profiles WHERE LOWER(login) = LOWER(?)", login);
        if (rows.isEmpty()) return Optional.empty();
        String owner = (String) rows.get(0).get("login"), repository = (String) rows.get(0).get("repository");
        String avatar = (String) rows.get(0).get("avatar_url");
        Optional<PortfolioScan> scan = assessments.lastScan(repository);
        List<ProfileSkill> skills = new ArrayList<>();
        ResumeService.demonstrated(assessments.latestForRepository(repository)).forEach((skill, gap) -> skills.add(new ProfileSkill(skill, skill.label(), gap.explanation(),
                gap.evidence().stream().limit(3).map(e -> new ProfileEvidence(e.path(), e.url(), e.reason())).toList())));
        skills.sort(Comparator.comparing(ProfileSkill::skill));
        String profileUrl = baseUrl + "/u/" + owner;
        String badge = "[![Verified skills on ProofPath](" + baseUrl + "/api/public/" + owner + "/badge.svg)](" + profileUrl + ")";
        return Optional.of(new PublicProfile(owner, avatar, repository, "https://github.com/" + repository,
                scan.map(PortfolioScan::revision).orElse(""), scan.map(PortfolioScan::scannedAt).orElse(null), skills, profileUrl, badge));
    }

    /** A small shields-style SVG for README files: "ProofPath | SQL ✓ verified". */
    public static String badge(String value, boolean verified) {
        String label = "ProofPath";
        int labelWidth = 12 + label.length() * 7, valueWidth = 14 + value.length() * 7, total = labelWidth + valueWidth;
        String color = verified ? "#0F6E56" : "#6B7280";
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + total + "\" height=\"20\" role=\"img\" aria-label=\"" + xml(label + ": " + value) + "\">"
                + "<linearGradient id=\"s\" x2=\"0\" y2=\"100%\"><stop offset=\"0\" stop-color=\"#fff\" stop-opacity=\".15\"/><stop offset=\"1\" stop-opacity=\".1\"/></linearGradient>"
                + "<clipPath id=\"r\"><rect width=\"" + total + "\" height=\"20\" rx=\"3\" fill=\"#fff\"/></clipPath>"
                + "<g clip-path=\"url(#r)\"><rect width=\"" + labelWidth + "\" height=\"20\" fill=\"#102A43\"/><rect x=\"" + labelWidth + "\" width=\"" + valueWidth + "\" height=\"20\" fill=\"" + color + "\"/>"
                + "<rect width=\"" + total + "\" height=\"20\" fill=\"url(#s)\"/></g>"
                + "<g fill=\"#fff\" text-anchor=\"middle\" font-family=\"Verdana,DejaVu Sans,sans-serif\" font-size=\"11\">"
                + "<text x=\"" + labelWidth / 2 + "\" y=\"14\">" + xml(label) + "</text>"
                + "<text x=\"" + (labelWidth + valueWidth / 2) + "\" y=\"14\">" + xml(value) + "</text></g></svg>";
    }
    private static String xml(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
}
