package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.JobRequirement;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.time.Instant;
import java.util.*;

/** Fetches the source page at assessment time; the page URL and extract remain traceable. */
@Service
public class JobDiscoveryService {
    private final RestClient http = RestClient.builder().defaultHeader("User-Agent", "AI-Career-Coach-Demo").build();

    public JobRequirement discover(String title, String company, String sourceUrl) {
        String html;
        try { html = http.get().uri(sourceUrl).retrieve().body(String.class); }
        catch (Exception e) { throw new IllegalArgumentException("Could not fetch job source: " + e.getMessage()); }
        String text = html.replaceAll("(?is)<script.*?</script>|<style.*?</style>|<[^>]+>", " ")
                .replaceAll("\\s+", " ").trim();
        List<Skill> skills = SkillMatcher.skillsIn(text);
        String excerpt = text.length() > 900 ? text.substring(0, 900) + "..." : text;
        return new JobRequirement(UUID.randomUUID().toString(), title, company, sourceUrl, excerpt, skills, Instant.now());
    }

    /** A real public careers-page source used only as a convenient starting point in the UI. */
    public JobRequirement demoJob() {
        return new JobRequirement("demo-backend", "Software Engineer Intern", "Example target role",
                "https://github.com/search?q=language%3AJava+spring+boot+internship&type=repositories",
                "Live source URL retained. Add a specific employer posting with POST /api/jobs/discover for a production assessment.",
                List.of(Skill.JAVA, Skill.SPRING_BOOT, Skill.REST_API, Skill.SQL, Skill.TESTING, Skill.GIT), Instant.now());
    }
}
