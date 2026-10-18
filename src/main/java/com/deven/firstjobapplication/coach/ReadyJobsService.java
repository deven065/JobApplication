package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Ranks open postings on public job boards by how much of each posting the student's repository already proves.
 * A skill counts as met when an assessment of the repository demonstrated it, and half when it was partial or when
 * the scan holds unreviewed signals for it. Requirements are detected with keyword rules so a whole board can be
 * ranked in one request; importing a posting runs the full AI assessment.
 */
@Service
public class ReadyJobsService {
    private static final int MAX_BOARDS = 5, MAX_RESULTS = 40;
    /** Postings with fewer checkable requirements say too little to rank; small lists are scored as if they had three. */
    static final int MIN_REQUIREMENTS = 2, CONFIDENT_REQUIREMENTS = 3;
    private final JobBoardService boards;
    private final AssessmentService assessments;
    public ReadyJobsService(JobBoardService boards, AssessmentService assessments) { this.boards = boards; this.assessments = assessments; }

    enum Level { MET, PARTIAL, MISSING }

    public ReadyJobResults rank(String repository, String source, List<String> boardNames, String query) {
        if (repository == null || repository.isBlank()) throw new IllegalArgumentException("Assess a repository first, then find jobs you're ready for.");
        PortfolioScan scan = assessments.lastScan(repository.trim())
                .orElseThrow(() -> new IllegalArgumentException("Assess this repository first, then find jobs you're ready for."));
        Map<Skill, Level> levels = levels(scan, assessments.latestForRepository(scan.repository()));
        List<String> names = boardNames.stream().map(String::trim).filter(n -> !n.isEmpty()).distinct().limit(MAX_BOARDS).toList();
        if (names.isEmpty()) throw new IllegalArgumentException("Enter at least one company board name, for example stripe.");
        List<ReadyJob> ranked = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int scanned = 0;
        for (String name : names) {
            List<JobBoardService.ListingWithText> postings;
            try { postings = boards.listingsWithText(source, name, query); }
            catch (RuntimeException error) { warnings.add(name + ": " + error.getMessage()); continue; }
            scanned += postings.size();
            for (JobBoardService.ListingWithText posting : postings) {
                List<Skill> required = JobSkillMatcher.skillsIn(posting.listing().title() + " " + posting.text());
                if (required.size() < MIN_REQUIREMENTS) continue;  // too little in this posting that code can show
                ranked.add(score(posting.listing(), required, levels));
            }
        }
        ranked.sort(Comparator.comparingInt(ReadyJob::score).reversed()
                .thenComparing(job -> -job.met().size())
                .thenComparing(job -> job.listing().title(), String.CASE_INSENSITIVE_ORDER));
        return new ReadyJobResults(scan.repository(), scan.revision(), scanned, ranked.stream().limit(MAX_RESULTS).toList(), warnings);
    }

    static Map<Skill, Level> levels(PortfolioScan scan, List<Assessment> current) {
        Map<Skill, Level> levels = new EnumMap<>(Skill.class);
        for (Assessment assessment : current)
            for (Gap gap : assessment.gaps()) {
                Level level = switch (gap.status()) { case "DEMONSTRATED" -> Level.MET; case "PARTIAL" -> Level.PARTIAL; default -> Level.MISSING; };
                levels.merge(gap.skill(), level, (a, b) -> a.ordinal() <= b.ordinal() ? a : b);  // keep the best verdict
            }
        for (Skill skill : Skill.values())
            if (!levels.containsKey(skill) && !scan.evidence().getOrDefault(skill, List.of()).isEmpty()) levels.put(skill, Level.PARTIAL);
        return levels;
    }

    static ReadyJob score(JobListing listing, List<Skill> required, Map<Skill, Level> levels) {
        List<Skill> met = new ArrayList<>(), partial = new ArrayList<>(), missing = new ArrayList<>();
        for (Skill skill : required)
            switch (levels.getOrDefault(skill, Level.MISSING)) { case MET -> met.add(skill); case PARTIAL -> partial.add(skill); default -> missing.add(skill); }
        int score = (int) Math.round(100.0 * (met.size() + 0.5 * partial.size()) / Math.max(required.size(), CONFIDENT_REQUIREMENTS));
        return new ReadyJob(listing, required, met, partial, missing, score);
    }
}
