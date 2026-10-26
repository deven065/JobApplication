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
        // The public page keeps its own copy of what was verified, because session data is deleted when a session ends.
        jdbc.execute("ALTER TABLE coach_profiles ADD COLUMN IF NOT EXISTS revision VARCHAR(64)");
        jdbc.execute("ALTER TABLE coach_profiles ADD COLUMN IF NOT EXISTS verified_at BIGINT");
        jdbc.execute("ALTER TABLE coach_profiles ADD COLUMN IF NOT EXISTS skills_json TEXT");
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
        PortfolioScan scan = assessments.lastScan(repo).orElseThrow();
        jdbc.update("INSERT INTO coach_profiles (login, repository, avatar_url, published_at, revision, verified_at, skills_json) VALUES (?, ?, ?, ?, ?, ?, ?)",
                user.login(), details.fullName(), user.avatarUrl(), System.currentTimeMillis(), scan.revision(),
                scan.scannedAt() == null ? null : scan.scannedAt().toEpochMilli(), writeSkills(verifiedSkills(repo, user.login())));
        return find(user.login()).orElseThrow();
    }

    public void unpublish(String login) { jdbc.update("DELETE FROM coach_profiles WHERE login = ?", login); }

    public Optional<PublicProfile> find(String login) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT login, repository, avatar_url, revision, verified_at, skills_json FROM coach_profiles WHERE LOWER(login) = LOWER(?)", login);
        if (rows.isEmpty()) return Optional.empty();
        String owner = (String) rows.get(0).get("login"), repository = (String) rows.get(0).get("repository");
        String avatar = (String) rows.get(0).get("avatar_url");
        String saved = (String) rows.get(0).get("skills_json");
        List<ProfileSkill> skills = saved != null ? readSkills(saved) : verifiedSkills(repository, owner);  // older rows had no snapshot
        String revision = rows.get(0).get("revision") != null ? (String) rows.get(0).get("revision") : assessments.lastScan(repository).map(PortfolioScan::revision).orElse("");
        Object verifiedAt = rows.get(0).get("verified_at");
        Instant verified = verifiedAt != null ? Instant.ofEpochMilli(((Number) verifiedAt).longValue()) : assessments.lastScan(repository).map(PortfolioScan::scannedAt).orElse(null);
        String profileUrl = baseUrl + "/u/" + owner;
        String badge = "[![Verified skills on ProofPath](" + baseUrl + "/api/public/" + owner + "/badge.svg)](" + profileUrl + ")";
        return Optional.of(new PublicProfile(owner, avatar, repository, "https://github.com/" + repository,
                revision, verified, skills, profileUrl, badge));
    }

    /** The skills the user's own assessments of this repository have demonstrated, with their cited files. */
    private List<ProfileSkill> verifiedSkills(String repository, String login) {
        List<ProfileSkill> skills = new ArrayList<>();
        ResumeService.demonstrated(assessments.latestForOwner(repository, login)).forEach((skill, gap) -> skills.add(new ProfileSkill(skill, skill.label(), gap.explanation(),
                gap.evidence().stream().limit(3).map(e -> new ProfileEvidence(e.path(), e.url(), e.reason())).toList())));
        skills.sort(Comparator.comparing(ProfileSkill::skill));
        return skills;
    }
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private static String writeSkills(List<ProfileSkill> skills) {
        try { return JSON.writeValueAsString(skills); } catch (Exception error) { throw new IllegalStateException("Could not save the profile", error); }
    }
    private static List<ProfileSkill> readSkills(String text) {
        try { return JSON.readValue(text, new com.fasterxml.jackson.core.type.TypeReference<List<ProfileSkill>>() { }); }
        catch (Exception error) { return List.of(); }
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
