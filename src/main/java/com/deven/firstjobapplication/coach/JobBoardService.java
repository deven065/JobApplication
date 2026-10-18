package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Discovers real postings through the public, key-free job-board APIs that companies publish their careers pages with.
 * Only these three fixed hosts are ever contacted, and the board name and job id are validated, so user input
 * cannot redirect requests elsewhere.
 */
@Service
public class JobBoardService {
    public enum Source { GREENHOUSE, LEVER, ASHBY }
    /** The full text of one posting, ready for requirement extraction. */
    public record JobPosting(String title, String company, String url, String text) { }

    private static final Pattern BOARD = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,79}");
    private static final Pattern JOB_ID = Pattern.compile("[A-Za-z0-9-]{1,80}");
    private static final int MAX_RESULTS = 50;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    /** Board, source and job id recognised from a public job link, so it can be read through the board's API. */
    public record BoardJobLink(String source, String board, String jobId) { }
    private static final Pattern GREENHOUSE_LINK = Pattern.compile("https?://(?:boards|job-boards)(?:\\.eu)?\\.greenhouse\\.io/([A-Za-z0-9._-]+)/jobs/(\\d+).*");
    private static final Pattern LEVER_LINK = Pattern.compile("https?://jobs(?:\\.eu)?\\.lever\\.co/([A-Za-z0-9._-]+)/([A-Za-z0-9-]+).*");
    private static final Pattern ASHBY_LINK = Pattern.compile("https?://jobs\\.ashbyhq\\.com/([A-Za-z0-9._%-]+)/([A-Za-z0-9-]+).*");

    public static Optional<BoardJobLink> recognise(String url) {
        if (url == null) return Optional.empty();
        String u = url.trim();
        var m = GREENHOUSE_LINK.matcher(u);
        if (m.matches()) return Optional.of(new BoardJobLink(Source.GREENHOUSE.name(), m.group(1), m.group(2)));
        m = LEVER_LINK.matcher(u);
        if (m.matches()) return Optional.of(new BoardJobLink(Source.LEVER.name(), m.group(1), m.group(2)));
        m = ASHBY_LINK.matcher(u);
        if (m.matches()) return Optional.of(new BoardJobLink(Source.ASHBY.name(), java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8), m.group(2)));
        return Optional.empty();
    }

    public JobBoardResults search(String source, String board, String query) {
        Source s = source(source);
        String slug = board(board);
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<JobListing> all = switch (s) {
            case GREENHOUSE -> greenhouseList(slug);
            case LEVER -> leverList(slug);
            case ASHBY -> ashbyList(slug);
        };
        List<JobListing> matching = all.stream()
                .filter(job -> q.isEmpty() || (job.title() + " " + job.location()).toLowerCase(Locale.ROOT).contains(q))
                .sorted(Comparator.comparing(JobListing::title, String.CASE_INSENSITIVE_ORDER))
                .toList();
        String company = all.isEmpty() ? pretty(slug) : all.get(0).company();
        return new JobBoardResults(s.name(), slug, company, matching.size(), matching.stream().limit(MAX_RESULTS).toList());
    }

    public JobPosting fetch(String source, String board, String jobId) {
        Source s = source(source);
        String slug = board(board);
        if (jobId == null || !JOB_ID.matcher(jobId).matches()) throw new IllegalArgumentException("Unknown job id.");
        return switch (s) {
            case GREENHOUSE -> {
                JsonNode job = get("https://boards-api.greenhouse.io/v1/boards/" + slug + "/jobs/" + jobId);
                String company = text(job, "company_name", pretty(slug));
                yield new JobPosting(text(job, "title", "Untitled role"), company, text(job, "absolute_url", ""),
                        HtmlUtils.htmlUnescape(text(job, "content", "")));
            }
            case LEVER -> {
                JsonNode job = get("https://api.lever.co/v0/postings/" + slug + "/" + jobId + "?mode=json");
                StringBuilder body = new StringBuilder(text(job, "descriptionPlain", ""));
                for (JsonNode list : job.path("lists"))
                    body.append("\n\n").append(text(list, "text", "")).append("\n").append(text(list, "content", ""));
                body.append("\n\n").append(text(job, "additionalPlain", ""));
                yield new JobPosting(text(job, "text", "Untitled role"), pretty(slug), text(job, "hostedUrl", ""), body.toString());
            }
            case ASHBY -> {
                JsonNode board1 = get("https://api.ashbyhq.com/posting-api/job-board/" + slug);
                for (JsonNode job : board1.path("jobs"))
                    if (jobId.equals(job.path("id").asText()))
                        yield new JobPosting(text(job, "title", "Untitled role"), pretty(slug), text(job, "jobUrl", ""), text(job, "descriptionPlain", ""));
                throw new NoSuchElementException("That posting is no longer listed on the board.");
            }
        };
    }

    /** A listing with its full description, for ranking many postings without fetching each one. */
    public record ListingWithText(JobListing listing, String text) { }

    /** Every open posting on one board with its description: one request per board on all three APIs. */
    public List<ListingWithText> listingsWithText(String source, String board, String query) {
        Source s = source(source);
        String slug = board(board);
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<ListingWithText> all = new ArrayList<>();
        switch (s) {
            case GREENHOUSE -> {
                for (JsonNode job : get("https://boards-api.greenhouse.io/v1/boards/" + slug + "/jobs?content=true").path("jobs"))
                    all.add(new ListingWithText(new JobListing(s.name(), slug, job.path("id").asText(), text(job, "title", "Untitled role"),
                            text(job, "company_name", pretty(slug)), text(job.path("location"), "name", ""), text(job, "absolute_url", "")),
                            plain(HtmlUtils.htmlUnescape(text(job, "content", "")))));
            }
            case LEVER -> {
                for (JsonNode job : get("https://api.lever.co/v0/postings/" + slug + "?mode=json&limit=200")) {
                    StringBuilder body = new StringBuilder(text(job, "descriptionPlain", ""));
                    for (JsonNode list : job.path("lists")) body.append(' ').append(text(list, "text", "")).append(' ').append(plain(text(list, "content", "")));
                    body.append(' ').append(text(job, "additionalPlain", ""));
                    all.add(new ListingWithText(new JobListing(s.name(), slug, job.path("id").asText(), text(job, "text", "Untitled role"),
                            pretty(slug), text(job.path("categories"), "location", ""), text(job, "hostedUrl", "")), body.toString()));
                }
            }
            case ASHBY -> {
                for (JsonNode job : get("https://api.ashbyhq.com/posting-api/job-board/" + slug).path("jobs"))
                    if (job.path("isListed").asBoolean(true))
                        all.add(new ListingWithText(new JobListing(s.name(), slug, job.path("id").asText(), text(job, "title", "Untitled role"),
                                pretty(slug), text(job, "location", ""), text(job, "jobUrl", "")), text(job, "descriptionPlain", "")));
            }
        }
        return all.stream().filter(item -> q.isEmpty() || (item.listing().title() + " " + item.listing().location()).toLowerCase(Locale.ROOT).contains(q)).toList();
    }
    private static String plain(String html) { return html.replaceAll("(?is)<[^>]+>", " ").replaceAll("\\s+", " ").trim(); }

    private List<JobListing> greenhouseList(String slug) {
        JsonNode root = get("https://boards-api.greenhouse.io/v1/boards/" + slug + "/jobs");
        List<JobListing> jobs = new ArrayList<>();
        for (JsonNode job : root.path("jobs"))
            jobs.add(new JobListing(Source.GREENHOUSE.name(), slug, job.path("id").asText(), text(job, "title", "Untitled role"),
                    text(job, "company_name", pretty(slug)), text(job.path("location"), "name", ""), text(job, "absolute_url", "")));
        return jobs;
    }
    private List<JobListing> leverList(String slug) {
        JsonNode root = get("https://api.lever.co/v0/postings/" + slug + "?mode=json&limit=200");
        List<JobListing> jobs = new ArrayList<>();
        for (JsonNode job : root)
            jobs.add(new JobListing(Source.LEVER.name(), slug, job.path("id").asText(), text(job, "text", "Untitled role"),
                    pretty(slug), text(job.path("categories"), "location", ""), text(job, "hostedUrl", "")));
        return jobs;
    }
    private List<JobListing> ashbyList(String slug) {
        JsonNode root = get("https://api.ashbyhq.com/posting-api/job-board/" + slug);
        List<JobListing> jobs = new ArrayList<>();
        for (JsonNode job : root.path("jobs"))
            if (job.path("isListed").asBoolean(true))
                jobs.add(new JobListing(Source.ASHBY.name(), slug, job.path("id").asText(), text(job, "title", "Untitled role"),
                        pretty(slug), text(job, "location", ""), text(job, "jobUrl", "")));
        return jobs;
    }

    private JsonNode get(String url) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json").header("User-Agent", "ProofPath").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) throw new NoSuchElementException("No public job board was found with that name. Check the company's careers page URL for its board name.");
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("The job board answered with HTTP " + response.statusCode() + ". Try again in a moment.");
            return json.readTree(response.body());
        } catch (NoSuchElementException | IllegalStateException error) { throw error; }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("The job board request was interrupted."); }
        catch (Exception error) { throw new IllegalStateException("The job board could not be reached. Try again in a moment."); }
    }

    private static Source source(String source) {
        try { return Source.valueOf(source == null ? "" : source.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Choose Greenhouse, Lever or Ashby as the job board."); }
    }
    private static String board(String board) {
        String slug = board == null ? "" : board.trim();
        if (!BOARD.matcher(slug).matches()) throw new IllegalArgumentException("Enter the company's board name, for example stripe or notion.");
        return URLEncoder.encode(slug, StandardCharsets.UTF_8);
    }
    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("");
        return value.isBlank() ? fallback : value.trim();
    }
    private static String pretty(String slug) {
        String spaced = slug.replace('-', ' ').replace('_', ' ');
        return spaced.isEmpty() ? slug : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
