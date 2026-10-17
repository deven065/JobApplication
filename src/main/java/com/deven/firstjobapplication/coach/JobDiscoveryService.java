package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.JobRequirement;
import com.deven.firstjobapplication.coach.Models.RequirementQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Fetches the source page at assessment time; the page URL and extract remain traceable. */
@Service
public class JobDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(JobDiscoveryService.class);
    private final AiCoach coach;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Autowired public JobDiscoveryService(AiCoach coach) { this.coach = coach; }
    public JobDiscoveryService() { this(AiCoach.disabled()); }

    public JobRequirement discover(String title, String company, String sourceUrl, String pastedText) {
        if (title == null || title.isBlank() || company == null || company.isBlank())
            throw new IllegalArgumentException("Enter the role and company.");
        URI uri;
        try { uri = URI.create(sourceUrl); }
        catch (Exception error) { throw new IllegalArgumentException("Enter a valid public job URL."); }
        if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())))
            throw new IllegalArgumentException("Enter a public HTTP or HTTPS job URL.");
        if (pastedText != null && pastedText.length() > 100_000)
            throw new IllegalArgumentException("Job posting text is too long. Paste at most 100,000 characters.");
        String blockingSite = blocksAutomatedReading(uri.getHost());
        if ((pastedText == null || pastedText.isBlank()) && blockingSite != null)
            // These sites answer automated requests with sign-in or bot-check pages, which must never be read as a posting.
            throw new IllegalArgumentException(blockingSite + " doesn't allow automated reading of job pages. Copy the job description from the posting into Posting text; the link is still saved as the source.");
        String html = pastedText == null || pastedText.isBlank() ? fetch(uri) : pastedText;
        // Decode entities (&amp;, &nbsp; ...) after stripping tags, so quotes read naturally and match the posting text.
        String text = org.springframework.web.util.HtmlUtils.htmlUnescape(
                        html.replaceAll("(?is)<script.*?</script>|<style.*?</style>|<[^>]+>", " "))
                .replace(' ', ' ').replaceAll("\\s+", " ").trim();
        if (text.isBlank()) throw new IllegalArgumentException("No readable job text was found. Paste the posting text and try again.");
        String excerpt = text.length() > 900 ? text.substring(0, 900) + "..." : text;
        if (coach.enabled()) {
            try {
                List<RequirementQuote> quotes = coach.extractRequirements(title, text);
                if (!quotes.isEmpty())
                    return new JobRequirement(UUID.randomUUID().toString(), title, company, sourceUrl, excerpt,
                            quotes.stream().map(RequirementQuote::skill).toList(), Instant.now(), quotes, coach.label());
            } catch (Exception error) { log.warn("AI requirement extraction failed; using keyword rules", error); }
        }
        List<Skill> skills = SkillMatcher.skillsIn(text);
        return new JobRequirement(UUID.randomUUID().toString(), title, company, sourceUrl, excerpt, skills, Instant.now());
    }

    /** Job sites without a public API that block automated reading; their postings are pasted, with the link kept. */
    static String blocksAutomatedReading(String host) {
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (h.equals("linkedin.com") || h.endsWith(".linkedin.com") || h.equals("lnkd.in")) return "LinkedIn";
        if (h.matches("(.+\\.)?indeed\\.[a-z.]+")) return "Indeed";
        if (h.equals("naukri.com") || h.endsWith(".naukri.com")) return "Naukri";
        if (h.matches("(.+\\.)?glassdoor\\.[a-z.]+")) return "Glassdoor";
        return null;
    }

    private String fetch(URI uri) {
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                byte[] bytes = address.getAddress();
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()
                        || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc))
                    throw new IllegalArgumentException("Use a public job URL. Local network addresses are not allowed.");
            }
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "ProofPath-Demo").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalArgumentException("This job page could not be read (HTTP " + response.statusCode() + "). Paste the posting text instead.");
            return response.body();
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("This job page could not be read. Paste the posting text instead."); }
    }

    /** A real public careers-page source used only as a convenient starting point in the UI. */
    public JobRequirement demoJob() {
        return new JobRequirement("demo-backend", "Software Engineer Intern", "Example target role",
                "https://github.com/search?q=language%3AJava+spring+boot+internship&type=repositories",
                "Live source URL retained. Add a specific employer posting with POST /api/jobs/discover for a production assessment.",
                List.of(Skill.JAVA, Skill.SPRING_BOOT, Skill.REST_API, Skill.SQL, Skill.TESTING, Skill.GIT), Instant.now());
    }
}
