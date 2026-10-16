package com.deven.firstjobapplication.coach;

import java.util.*;
import java.util.regex.Pattern;

public final class SkillMatcher {
    private static final Map<Skill, Pattern> RULES = Map.of(
            Skill.JAVA, Pattern.compile("\\b(java|public class|springapplication)\\b", Pattern.CASE_INSENSITIVE),
            Skill.SPRING_BOOT, Pattern.compile("spring-boot|@springbootapplication|@restcontroller", Pattern.CASE_INSENSITIVE),
            Skill.REST_API, Pattern.compile("@(get|post|put|delete)mapping|rest api|/api/", Pattern.CASE_INSENSITIVE),
            Skill.SQL, Pattern.compile("\\b(select|insert|update|delete|create table|join|sql)\\b", Pattern.CASE_INSENSITIVE),
            Skill.POSTGRESQL, Pattern.compile("postgres|postgresql", Pattern.CASE_INSENSITIVE),
            Skill.REACT, Pattern.compile("react|useState|useEffect|jsx", Pattern.CASE_INSENSITIVE),
            Skill.DOCKER, Pattern.compile("dockerfile|docker-compose|from [a-z].*", Pattern.CASE_INSENSITIVE),
            Skill.TESTING, Pattern.compile("@test|junit|describe\\(|it\\(", Pattern.CASE_INSENSITIVE),
            Skill.GIT, Pattern.compile("github|git", Pattern.CASE_INSENSITIVE));
    private SkillMatcher() { }
    public static List<Skill> skillsIn(String text) {
        return RULES.entrySet().stream().filter(e -> e.getValue().matcher(text).find()).map(Map.Entry::getKey).toList();
    }
    public static String reason(Skill skill) { return "Matched an explicit " + skill.label() + " implementation signal"; }
}
