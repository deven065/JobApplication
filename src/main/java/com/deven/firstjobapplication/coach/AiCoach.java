package com.deven.firstjobapplication.coach;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.deven.firstjobapplication.coach.Models.*;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * An LLM reads job postings and reviews the code signals the scanner found.
 * The regex scanner still decides which files are candidates; the model only judges them,
 * and every claim it makes is checked against the posting text or the scanned file paths.
 *
 * Providers: Groq and Google Gemini (free tiers, OpenAI-compatible chat API) or Claude (Anthropic SDK).
 * With no key configured the coach is disabled and the rule-based results are used.
 */
@Service
public class AiCoach {
    private static final Logger log = LoggerFactory.getLogger(AiCoach.class);
    private static final int MAX_POSTING_CHARS = 30_000;
    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS).build();

    /** Free tiers have small per-minute token limits, so they get a smaller evidence budget. */
    private record Provider(String name, String baseUrl, int candidatesPerSkill, int excerptChars) { }
    private static final Map<String, Provider> PROVIDERS = Map.of(
            "groq", new Provider("groq", "https://api.groq.com/openai/v1", 3, 700),
            "gemini", new Provider("gemini", "https://generativelanguage.googleapis.com/v1beta/openai", 5, 1800),
            "claude", new Provider("claude", null, 5, 1800));

    private final Provider provider;
    private final String model;
    private final AnthropicClient anthropic;
    private final RestClient openAiCompatible;

    public AiCoach(@Value("${coach.ai.provider:auto}") String requested,
                   @Value("${groq.api-key:}") String groqKey, @Value("${groq.model:openai/gpt-oss-120b}") String groqModel,
                   @Value("${gemini.api-key:}") String geminiKey, @Value("${gemini.model:gemini-2.5-flash}") String geminiModel,
                   @Value("${anthropic.api-key:}") String anthropicKey, @Value("${anthropic.model:claude-opus-5-5}") String anthropicModel) {
        Map<String, String> keys = Map.of("groq", blank(groqKey), "gemini", blank(geminiKey), "claude", blank(anthropicKey));
        String name = requested == null || requested.isBlank() || requested.equalsIgnoreCase("auto")
                ? List.of("groq", "gemini", "claude").stream().filter(p -> !keys.get(p).isEmpty()).findFirst().orElse("")
                : requested.trim().toLowerCase(Locale.ROOT);
        if (!name.isEmpty() && !PROVIDERS.containsKey(name)) throw new IllegalStateException("Unknown coach.ai.provider: " + requested + " (use groq, gemini or claude)");
        String key = name.isEmpty() ? "" : keys.get(name);
        this.provider = key.isEmpty() ? null : PROVIDERS.get(name);
        this.model = switch (name) { case "groq" -> groqModel; case "gemini" -> geminiModel; case "claude" -> anthropicModel; default -> ""; };
        this.anthropic = provider != null && name.equals("claude") ? AnthropicOkHttpClient.builder().apiKey(key).build() : null;
        if (provider != null && provider.baseUrl() != null) {
            SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
            timeouts.setConnectTimeout(Duration.ofSeconds(10));
            timeouts.setReadTimeout(Duration.ofSeconds(90));
            this.openAiCompatible = RestClient.builder().baseUrl(provider.baseUrl()).requestFactory(timeouts)
                    .defaultHeader("Authorization", "Bearer " + key).build();
        } else this.openAiCompatible = null;
        if (provider != null) log.info("AI coach enabled: {}", label());
    }
    public static AiCoach disabled() { return new AiCoach("auto", "", "", "", "", "", ""); }
    public boolean enabled() { return provider != null; }
    public String provider() { return provider == null ? "none" : provider.name(); }
    public String model() { return model; }
    public String label() { return provider() + ":" + model; }

    // ---- Job posting -> requirements ----------------------------------------------------------

    public enum Importance { REQUIRED, PREFERRED }
    public record ExtractedRequirement(
            @JsonPropertyDescription("Skill from the fixed taxonomy") Skill skill,
            @JsonPropertyDescription("Exact sentence or phrase copied verbatim from the posting that asks for this skill") String quote,
            Importance importance) { }
    public record RequirementExtraction(List<ExtractedRequirement> requirements) { }

    private static final String EXTRACT_SYSTEM = """
            You extract technical requirements from job and internship postings for a career coach.
            Map each requirement onto this fixed skill taxonomy: %s.
            Only include a skill when the posting actually asks for it (as a requirement, a preference, or a core part of
            the day-to-day work). Words that merely contain a skill name (for example "digital" for Git) do not count.
            Ignore company boilerplate and benefits.
            For every skill, copy the shortest exact phrase from the posting that justifies it into "quote". It must be
            verbatim, because it is shown to the student as the source of the requirement. Use PREFERRED for
            "nice to have", "bonus" or "plus" items and REQUIRED otherwise. Include each skill at most once.
            Reply with JSON only, in this shape:
            {"requirements":[{"skill":"SQL","quote":"experience writing SQL queries","importance":"REQUIRED"}]}"""
            .formatted(Arrays.stream(Skill.values()).map(s -> s.name() + " (" + s.label() + ")").collect(Collectors.joining(", ")));

    public List<RequirementQuote> extractRequirements(String title, String postingText) {
        String text = postingText.length() > MAX_POSTING_CHARS ? postingText.substring(0, MAX_POSTING_CHARS) : postingText;
        String prompt = "Role: " + title + "\n\n<posting>\n" + text + "\n</posting>";
        RequirementExtraction result = call(EXTRACT_SYSTEM, prompt, RequirementExtraction.class);
        String haystack = normalize(text);
        Map<Skill, RequirementQuote> bySkill = new LinkedHashMap<>();
        for (ExtractedRequirement r : Optional.ofNullable(result.requirements()).orElse(List.of())) {
            if (r == null || r.skill() == null || r.quote() == null || r.quote().isBlank()) continue;
            // Traceability guard: drop any requirement whose quote is not really in the posting.
            if (!haystack.contains(normalize(r.quote()))) { log.info("Dropped {}: quote not found in posting", r.skill()); continue; }
            bySkill.putIfAbsent(r.skill(), new RequirementQuote(r.skill(), r.quote().trim(),
                    r.importance() == null ? Importance.REQUIRED.name() : r.importance().name()));
        }
        return List.copyOf(bySkill.values());
    }

    // ---- Repository evidence -> verdicts --------------------------------------------------------

    public enum Verdict { DEMONSTRATED, PARTIAL, NOT_DEMONSTRATED }
    public enum Confidence { HIGH, MEDIUM, LOW }
    public record SkillVerdict(
            Skill skill,
            Verdict status,
            Confidence confidence,
            @JsonPropertyDescription("Two or three sentences explaining the verdict, referring to the cited files by path") String explanation,
            @JsonPropertyDescription("Paths of the candidate files that support the verdict, copied exactly from the input. Empty when nothing supports it.") List<String> citedPaths,
            @JsonPropertyDescription("One concrete, small change to this repository that would demonstrate the skill more strongly") String nextStep) { }
    public record PortfolioReview(
            @JsonPropertyDescription("Three or four sentences: how well this repository matches the role, the most important gap, and what to build next") String summary,
            List<SkillVerdict> verdicts) { }

    private static final String REVIEW_SYSTEM = """
            You are a careful career coach reviewing a student's GitHub repository against a job's requirements.
            A keyword scanner has already selected candidate files for each required skill. Keyword matches are often
            false positives (a word in a comment, a dependency name, "delete" in prose, "git" in a URL), so judge the code itself:
            - DEMONSTRATED: the excerpt shows the student actually implementing or meaningfully using the skill.
            - PARTIAL: there is a real but thin signal (only configuration, boilerplate, a trivial or generated example).
            - NOT_DEMONSTRATED: no candidate shows real use of the skill.
            Rules:
            - Judge only from the excerpts provided. Do not assume anything about files you were not shown.
            - citedPaths must be copied exactly from the candidate paths for that skill. Cite only files you relied on.
            - A missing signal means "not demonstrated in this scan", never that the student lacks the skill. Say so plainly
              when relevant and do not speculate about the student's ability.
            - Return exactly one verdict for every required skill, including skills with no candidates.
            - nextStep should be specific to this repository (name a file or feature to add), and achievable in a few hours.
            Reply with JSON only, in this shape:
            {"summary":"...","verdicts":[{"skill":"SQL","status":"DEMONSTRATED","confidence":"HIGH","explanation":"...","citedPaths":["queries/app.sql"],"nextStep":"..."}]}
            status is DEMONSTRATED, PARTIAL or NOT_DEMONSTRATED; confidence is HIGH, MEDIUM or LOW.""";

    public PortfolioReview review(JobRequirement job, PortfolioScan scan) { return review(job, scan, Map.of()); }

    /**
     * Reviews only job.requiredSkills(). unchanged lists the other required skills (with their current status) whose
     * files the latest commit did not touch, so the summary can still describe the whole role.
     */
    public PortfolioReview review(JobRequirement job, PortfolioScan scan, Map<Skill, String> unchanged) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Role: ").append(job.title()).append(" at ").append(job.company()).append('\n');
        prompt.append("Repository: ").append(scan.repository()).append(" @ ").append(scan.revision())
                .append(" (").append(scan.scannedFiles()).append(" files read, ").append(scan.skippedFiles()).append(" skipped)\n\n");
        if (!unchanged.isEmpty()) {
            prompt.append("The latest commit did not touch the files behind these other required skills, so they keep their earlier verdicts: ")
                    .append(unchanged.entrySet().stream().map(e -> e.getKey().label() + " = " + e.getValue()).collect(Collectors.joining(", ")))
                    .append(".\nReturn verdicts only for the requirements below, but write the summary for the whole role.\n\n");
        }
        Map<Skill, String> quotes = Optional.ofNullable(job.requirementQuotes()).orElse(List.of()).stream()
                .collect(Collectors.toMap(RequirementQuote::skill, RequirementQuote::quote, (a, b) -> a));
        int excerptChars = provider == null ? 1800 : provider.excerptChars();
        for (Skill skill : job.requiredSkills()) {
            prompt.append("<requirement skill=\"").append(skill.name()).append("\" label=\"").append(skill.label()).append("\">\n");
            if (quotes.containsKey(skill)) prompt.append("Posting says: ").append(quotes.get(skill)).append('\n');
            List<Evidence> candidates = candidates(scan, skill);
            if (candidates.isEmpty()) prompt.append("No candidate files were found by the scanner.\n");
            for (Evidence e : candidates) {
                String code = e.excerpt() == null ? e.snippet() : e.excerpt();
                if (code.length() > excerptChars) code = code.substring(0, excerptChars);
                prompt.append("<candidate path=\"").append(e.path()).append("\">\n").append(code).append("\n</candidate>\n");
            }
            prompt.append("</requirement>\n\n");
        }
        return call(REVIEW_SYSTEM, prompt.toString(), PortfolioReview.class);
    }

    /** The files the reviewer is shown for a skill; verdicts may only cite these. */
    public List<Evidence> candidates(PortfolioScan scan, Skill skill) {
        int limit = provider == null ? 5 : provider.candidatesPerSkill();
        return scan.evidence().getOrDefault(skill, List.of()).stream().limit(limit).toList();
    }

    // ---- Provider calls -------------------------------------------------------------------------

    private <T> T call(String system, String prompt, Class<T> type) {
        if (provider == null) throw new IllegalStateException("No AI provider is configured. Set GROQ_API_KEY, GEMINI_API_KEY or ANTHROPIC_API_KEY.");
        return anthropic != null ? callClaude(system, prompt, type) : callOpenAiCompatible(system, prompt, type);
    }

    /** Groq and Gemini both expose the OpenAI chat-completions format. JSON mode keeps the reply parseable. */
    private <T> T callOpenAiCompatible(String system, String prompt, Class<T> type) {
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0.1,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", prompt)));
        String raw = openAiCompatible.post().uri("/chat/completions").body(body).retrieve().body(String.class);
        try {
            JsonNode choice = JSON.readTree(raw).path("choices").path(0);
            if ("length".equals(choice.path("finish_reason").asText())) throw new IllegalStateException(provider.name() + "'s answer was cut off.");
            String content = choice.path("message").path("content").asText("").trim()
                    .replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
            if (content.isEmpty()) throw new IllegalStateException(provider.name() + " returned an empty answer.");
            return JSON.readValue(content, type);
        } catch (IllegalStateException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException(provider.name() + " returned JSON that did not match the expected shape.", error); }
    }

    private <T> T callClaude(String system, String prompt, Class<T> type) {
        StructuredMessageCreateParams<T> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(system)
                // Server-side refusal fallback: retries a declined request on a suitable model automatically.
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .outputConfig(type)
                .addUserMessage(prompt)
                .build();
        StructuredMessage<T> response = anthropic.messages().create(params);
        if (response.stopReason().map(StopReason.REFUSAL::equals).orElse(false))
            throw new IllegalStateException("Claude declined to review this request.");
        if (response.stopReason().map(StopReason.MAX_TOKENS::equals).orElse(false))
            throw new IllegalStateException("Claude's answer was cut off.");
        return response.content().stream().flatMap(block -> block.text().stream()).map(text -> text.text())
                .findFirst().orElseThrow(() -> new IllegalStateException("Claude returned no structured answer."));
    }

    private static String blank(String value) { return value == null ? "" : value.trim(); }
    private static String normalize(String value) {
        return value.replaceAll("[\\u2018\\u2019]", "'").replaceAll("[\\u201C\\u201D]", "\"")
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
