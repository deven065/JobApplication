package com.deven.firstjobapplication.coach;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Detects the taxonomy skills a job description asks for, for quickly ranking many postings without an AI call.
 * Uses word boundaries so "Java" does not match "JavaScript" and "Git" does not match "digital".
 */
public final class JobSkillMatcher {
    private static final int FLAGS = Pattern.CASE_INSENSITIVE;
    private static final Map<Skill, Pattern> RULES = new EnumMap<>(Map.of(
            Skill.JAVA, Pattern.compile("\\bjava\\b(?!\\s*script)", FLAGS),
            Skill.SPRING_BOOT, Pattern.compile("\\bspring\\s*boot\\b|\\bspring\\s+(framework|mvc|cloud)\\b", FLAGS),
            Skill.REST_API, Pattern.compile("\\brest(ful)?\\b|\\bapis?\\b|\\bweb\\s+services?\\b|\\bgraphql\\b", FLAGS),
            Skill.SQL, Pattern.compile("\\bsql\\b|\\bmysql\\b|\\bpostgres(ql)?\\b|\\brelational\\s+databases?\\b|\\bsql\\s*server\\b", FLAGS),
            Skill.POSTGRESQL, Pattern.compile("\\bpostgres(ql)?\\b", FLAGS),
            Skill.REACT, Pattern.compile("\\breact(\\.js|js)?\\b(?!\\s+native)", FLAGS),
            Skill.DOCKER, Pattern.compile("\\bdocker\\b|\\bcontaineri[sz](ed|ation)\\b|\\bcontainers?\\b", FLAGS),
            Skill.TESTING, Pattern.compile("\\bunit\\s+tests?\\b|\\b(automated|integration)\\s+test(s|ing)?\\b|\\btest[- ]driven\\b|\\btdd\\b|\\bjunit\\b|\\bjest\\b|\\btesting\\b", FLAGS),
            Skill.GIT, Pattern.compile("\\bgit\\b|\\bgithub\\b|\\bgitlab\\b|\\bversion\\s+control\\b", FLAGS)));
    private JobSkillMatcher() { }

    public static List<Skill> skillsIn(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<Skill> found = new ArrayList<>();
        for (Skill skill : Skill.values()) if (RULES.get(skill).matcher(text).find()) found.add(skill);
        return found;
    }
}
