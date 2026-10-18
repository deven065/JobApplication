package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Interview questions about the student's own files. The AI (when configured) writes questions that point at real names in the
 * cited code; without it, templates quote the matching line from the file, so the questions are still specific to this repository.
 */
@Service
public class InterviewService {
    private static final Logger log = LoggerFactory.getLogger(InterviewService.class);
    private static final int MAX_QUESTIONS = 6;
    private final AssessmentService assessments;
    private final AiCoach coach;

    public InterviewService(AssessmentService assessments, AiCoach coach) { this.assessments = assessments; this.coach = coach; }

    public InterviewPrep prepare(String repository) {
        PortfolioScan scan = assessments.lastScan(repository.trim()).orElseThrow(() -> new NoSuchElementException("Assess this repository first."));
        List<Evidence> picks = picks(assessments.latestForRepository(scan.repository()));
        if (picks.isEmpty()) throw new IllegalStateException("No code evidence to ask about yet. Add a feature, push it, and ProofPath will find it.");
        if (coach.enabled()) {
            try {
                List<InterviewQuestion> asked = fromAi(picks, coach.interviewQuestions(scan.repository(), picks));
                if (!asked.isEmpty()) return new InterviewPrep(scan.repository(), scan.revision(), coach.label(), asked);
            } catch (RuntimeException failure) { log.warn("AI interview questions failed, using templates: {}", failure.getMessage()); }
        }
        return new InterviewPrep(scan.repository(), scan.revision(), "rules", picks.stream().map(InterviewService::template).toList());
    }

    /** One file per skill, strongest verdicts first. */
    static List<Evidence> picks(List<Assessment> current) {
        Map<Skill, Evidence> bySkill = new EnumMap<>(Skill.class);
        for (String wanted : List.of("DEMONSTRATED", "PARTIAL"))
            for (Assessment assessment : current)
                for (Gap gap : assessment.gaps())
                    if (wanted.equals(gap.status()) && gap.evidence() != null && !gap.evidence().isEmpty()) bySkill.putIfAbsent(gap.skill(), gap.evidence().get(0));
        List<Evidence> ordered = new ArrayList<>();
        Set<String> usedPaths = new HashSet<>();
        for (Skill skill : Skill.values()) {
            Evidence evidence = bySkill.get(skill);
            if (evidence != null && usedPaths.add(evidence.path())) ordered.add(evidence);
        }
        return ordered.stream().limit(MAX_QUESTIONS).toList();
    }

    /** Keeps only questions that cite a file we showed the model. */
    static List<InterviewQuestion> fromAi(List<Evidence> picks, List<AiCoach.InterviewItem> items) {
        Map<String, Evidence> byPath = new HashMap<>();
        for (Evidence pick : picks) byPath.put(pick.path(), pick);
        List<InterviewQuestion> questions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AiCoach.InterviewItem item : items) {
            if (item == null || item.path() == null || item.question() == null || item.question().isBlank()) continue;
            Evidence source = byPath.get(item.path());
            if (source == null || !seen.add(item.path())) continue;
            questions.add(new InterviewQuestion(source.skill(), item.question().trim(), source.path(), source.url(),
                    item.lookFor() == null ? "" : item.lookFor().trim()));
        }
        return questions;
    }

    private static final Map<Skill, String> ASKS = new EnumMap<>(Map.of(
            Skill.JAVA, "Walk me through what this code does, and what happens when its input is invalid or null?",
            Skill.SPRING_BOOT, "Why is this a Spring-managed component, and how would you change its configuration between local and production?",
            Skill.REST_API, "What status codes can this endpoint return, and how do you validate what the client sends?",
            Skill.SQL, "What does this query return, and how would it behave with a million rows? Would you add an index?",
            Skill.POSTGRESQL, "Why this database setup, and how do you keep its credentials and schema out of the code?",
            Skill.REACT, "Where does this component keep its state, and what makes it render again?",
            Skill.DOCKER, "Why is the image built this way, and what would you change to make it smaller or safer?",
            Skill.TESTING, "What bug would this test catch, and what would you test next?",
            Skill.GIT, "How do you use this in your workflow, and what would you do after a bad commit?"));
    private static final Map<Skill, String> LOOK_FOR = new EnumMap<>(Map.of(
            Skill.JAVA, "Names the inputs and outputs, mentions error handling, and can explain one design choice.",
            Skill.SPRING_BOOT, "Explains dependency injection and externalised configuration.",
            Skill.REST_API, "Mentions status codes, validation and idempotency of the routes.",
            Skill.SQL, "Explains joins or filters correctly and knows when an index helps.",
            Skill.POSTGRESQL, "Separates configuration from code and knows why a real database beats files or memory.",
            Skill.REACT, "Distinguishes state from props and explains re-rendering.",
            Skill.DOCKER, "Talks about layers, multi-stage builds and running as a non-root user.",
            Skill.TESTING, "Describes the behaviour being protected, not just the test mechanics.",
            Skill.GIT, "Describes branches, small commits and how to undo a mistake safely."));

    static InterviewQuestion template(Evidence evidence) {
        String line = evidence.snippet() == null ? "" : evidence.snippet().strip().replaceAll("\\s+", " ");
        if (line.length() > 90) line = line.substring(0, 90) + "…";
        String lead = line.isBlank() ? "In " + evidence.path() + ": " : "In " + evidence.path() + " you wrote \"" + line + "\". ";
        return new InterviewQuestion(evidence.skill(), lead + ASKS.get(evidence.skill()), evidence.path(), evidence.url(), LOOK_FOR.get(evidence.skill()));
    }
}
