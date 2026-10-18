package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;

import java.util.*;

/**
 * Combines the assessments of several repositories for one job into a single portfolio view. For each requirement the best
 * verdict across the repositories wins, and its evidence is cited with the repository it came from. Nothing is re-scanned:
 * each repository keeps its own watch and its own change detection, and this view just reads their latest results.
 */
public final class PortfolioMerger {
    private PortfolioMerger() { }

    private static int rank(String status) { return switch (status) { case "DEMONSTRATED" -> 2; case "PARTIAL" -> 1; default -> 0; }; }
    static int readiness(List<Gap> gaps) {
        if (gaps.isEmpty()) return 0;
        double total = gaps.stream().mapToDouble(g -> rank(g.status()) / 2.0).sum();
        return (int) Math.round(100 * total / gaps.size());
    }

    /** assessments are in the order the student listed the repositories; ties go to the earlier one. */
    public static CombinedPortfolio merge(JobRequirement job, List<Assessment> assessments) {
        if (assessments.isEmpty()) throw new IllegalArgumentException("Choose at least one assessed repository.");
        boolean several = assessments.size() > 1;
        List<Gap> merged = new ArrayList<>();
        Map<Skill, List<String>> provenBy = new EnumMap<>(Skill.class);
        for (Skill skill : job.requiredSkills()) {
            Gap best = null; String bestRepo = null;
            List<String> demonstratedIn = new ArrayList<>();
            for (Assessment assessment : assessments) {
                Gap gap = assessment.gaps().stream().filter(g -> g.skill() == skill).findFirst().orElse(null);
                if (gap == null) continue;
                String repo = assessment.portfolio().repository();
                if ("DEMONSTRATED".equals(gap.status())) demonstratedIn.add(repo);
                if (best == null || rank(gap.status()) > rank(best.status())) { best = gap; bestRepo = repo; }
            }
            if (best == null) continue;
            provenBy.put(skill, demonstratedIn);
            List<Evidence> evidence = new ArrayList<>();
            for (Assessment assessment : assessments) {
                Gap gap = assessment.gaps().stream().filter(g -> g.skill() == skill).findFirst().orElse(null);
                if (gap == null || rank(gap.status()) < rank(best.status()) || gap.evidence() == null) continue;
                String repo = assessment.portfolio().repository();
                for (Evidence e : gap.evidence())
                    evidence.add(several ? new Evidence(e.skill(), repo + " · " + e.path(), e.url(), e.reason(), e.snippet(), e.excerpt()) : e);
            }
            String explanation = best.explanation();
            if (several && rank(best.status()) > 0) explanation = (demonstratedIn.size() > 1 ? "Shown in " + String.join(" and ", demonstratedIn) + ". " : "Best evidence is in " + bestRepo + ". ") + explanation;
            else if (several) explanation = "Not shown in any of the " + assessments.size() + " repositories. " + explanation;
            merged.add(new Gap(skill, best.status(), explanation, evidence, best.confidence(), best.nextStep(), best.assessedBy()));
        }
        List<RepoScore> scores = assessments.stream().map(a -> new RepoScore(a.portfolio().repository(), readiness(a.gaps()))).toList();
        List<String> repositories = assessments.stream().map(a -> a.portfolio().repository()).toList();
        return new CombinedPortfolio(job.id(), repositories, merged, readiness(merged), scores, provenBy);
    }
}
