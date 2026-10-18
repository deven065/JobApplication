package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Resume bullets written only from skills the repository has actually demonstrated. Every bullet names the file that proves it,
 * so a recruiter (or the student) can open it. Nothing here is invented: skills without evidence are listed as not yet proven.
 */
@Service
public class ResumeService {
    private static final Map<Skill, String> VERBS = new EnumMap<>(Map.of(
            Skill.JAVA, "Wrote application logic in Java",
            Skill.SPRING_BOOT, "Built backend services with Spring Boot",
            Skill.REST_API, "Designed and implemented REST API endpoints",
            Skill.SQL, "Wrote SQL queries for application data",
            Skill.POSTGRESQL, "Persisted application data in PostgreSQL",
            Skill.REACT, "Built an interactive user interface with React",
            Skill.DOCKER, "Containerised the application with Docker",
            Skill.TESTING, "Added automated tests to protect behaviour",
            Skill.GIT, "Managed the project with Git version control"));

    private final AssessmentService assessments;
    public ResumeService(AssessmentService assessments) { this.assessments = assessments; }

    public ResumeDraft draft(String repository) {
        PortfolioScan scan = assessments.lastScan(repository.trim()).orElseThrow(() -> new NoSuchElementException("Assess this repository first."));
        return build(scan, demonstrated(assessments.latestForRepository(scan.repository())));
    }

    /** The first DEMONSTRATED verdict with a cited file per skill, across every job this repository was assessed against. */
    static Map<Skill, Gap> demonstrated(List<Assessment> current) {
        Map<Skill, Gap> proven = new EnumMap<>(Skill.class);
        for (Assessment assessment : current)
            for (Gap gap : assessment.gaps())
                if ("DEMONSTRATED".equals(gap.status()) && gap.evidence() != null && !gap.evidence().isEmpty()) proven.putIfAbsent(gap.skill(), gap);
        return proven;
    }

    static ResumeDraft build(PortfolioScan scan, Map<Skill, Gap> proven) {
        String project = scan.repository().contains("/") ? scan.repository().substring(scan.repository().indexOf('/') + 1) : scan.repository();
        List<ResumeBullet> bullets = new ArrayList<>();
        for (Skill skill : Skill.values()) {
            Gap gap = proven.get(skill);
            if (gap == null) continue;
            Evidence proof = gap.evidence().get(0);
            bullets.add(new ResumeBullet(skill, VERBS.get(skill) + " (" + proof.path() + ").", proof.path(), proof.url()));
        }
        List<Skill> missing = Arrays.stream(Skill.values()).filter(skill -> !proven.containsKey(skill)).toList();
        String line = bullets.isEmpty() ? project : project + " | " + String.join(", ", bullets.stream().map(b -> b.skill().label()).toList());
        return new ResumeDraft(scan.repository(), scan.revision(), line, bullets, missing);
    }
}
