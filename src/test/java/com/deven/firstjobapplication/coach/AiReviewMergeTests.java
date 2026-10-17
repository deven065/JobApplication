package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.AiCoach.*;
import com.deven.firstjobapplication.coach.Models.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AiReviewMergeTests {
    private final Evidence java = new Evidence(Skill.JAVA, "src/App.java", "https://github.com/o/r/blob/one/src/App.java", "Java signal", "public class App {}");
    private final Evidence sql = new Evidence(Skill.SQL, "src/Repo.java", "https://github.com/o/r/blob/one/src/Repo.java", "SQL signal", "// delete later");
    private final Evidence git = new Evidence(Skill.GIT, "README.md.yml", "https://github.com/o/r/blob/one/README.md.yml", "Git signal", "url: github.com");
    private final JobRequirement job = new JobRequirement("job", "Intern", "Example", "https://example.com/job", "",
            List.of(Skill.JAVA, Skill.SQL, Skill.GIT, Skill.TESTING), Instant.now());
    private final PortfolioScan scan = new PortfolioScan("o/r", "main", "one", Instant.now(),
            Map.of(Skill.JAVA, List.of(java), Skill.SQL, List.of(sql), Skill.GIT, List.of(git)), 3, 0, false);

    private AssessmentService service(AiCoach coach) {
        StateStore store = new StateStore(new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:merge_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "")));
        GitHubPortfolioService github = mock(GitHubPortfolioService.class);
        when(github.repository("o/r")).thenReturn(new GitHubRepository("o/r", "main", "https://github.com/o/r", false));
        when(github.scan("o/r", "main")).thenReturn(scan);
        return new AssessmentService(github, store, coach);
    }

    @Test void aiVerdictsAreOnlyAcceptedWhenTraceable() {
        AiCoach coach = mock(AiCoach.class);
        when(coach.enabled()).thenReturn(true);
        when(coach.label()).thenReturn("groq:test");
        when(coach.candidates(any(), any())).thenCallRealMethod();
        when(coach.review(any(), any())).thenReturn(new PortfolioReview("Solid Java, little SQL.", List.of(
                new SkillVerdict(Skill.JAVA, Verdict.DEMONSTRATED, Confidence.HIGH, "App.java defines a class.", List.of("src/App.java"), "Add a service."),
                new SkillVerdict(Skill.SQL, Verdict.DEMONSTRATED, Confidence.HIGH, "Queries exist.", List.of("made/up.sql"), "Add a query."),
                new SkillVerdict(Skill.GIT, Verdict.NOT_DEMONSTRATED, Confidence.MEDIUM, "Only a URL mentions GitHub.", List.of(), "Add a CI workflow."),
                new SkillVerdict(Skill.TESTING, Verdict.DEMONSTRATED, Confidence.HIGH, "Tests exist.", List.of("src/AppTest.java"), "Add a test."))));

        Assessment result = service(coach).assess(job, "o/r", "");

        assertThat(result.summary()).isEqualTo("Solid Java, little SQL.");
        assertThat(result.gaps()).extracting(Gap::status).containsExactly("DEMONSTRATED", "PARTIAL", "NOT_DEMONSTRATED", "NOT_DEMONSTRATED");
        assertThat(result.gaps().get(0).evidence()).extracting(Evidence::path).containsExactly("src/App.java");
        assertThat(result.gaps().get(1).confidence()).isEqualTo("LOW");     // cited a file that was never scanned
        assertThat(result.gaps().get(2).evidence()).containsExactly(git);   // rejected signal stays visible
        assertThat(result.gaps().get(3).evidence()).isEmpty();              // no scanned candidate, so no positive claim
        assertThat(result.gaps().get(3).nextStep()).isEqualTo("Add a test.");
    }

    @Test void structuredOutputSchemasAreAcceptedBySdk() {
        for (Class<?> type : List.of(PortfolioReview.class, RequirementExtraction.class))
            com.anthropic.models.messages.MessageCreateParams.builder().model("claude-opus-5-5").maxTokens(1000L)
                    .outputConfig(type).addUserMessage("schema check").build();
    }

    @Test void providerIsChosenFromConfiguredKey() {
        assertThat(AiCoach.disabled().enabled()).isFalse();
        AiCoach groq = new AiCoach("auto", "gsk_test", "openai/gpt-oss-120b", "", "gemini-2.5-flash", "", "claude-opus-5-5");
        assertThat(groq.label()).isEqualTo("groq:openai/gpt-oss-120b");
        AiCoach gemini = new AiCoach("gemini", "gsk_test", "x", "AIza_test", "gemini-2.5-flash", "", "claude-opus-5-5");
        assertThat(gemini.label()).isEqualTo("gemini:gemini-2.5-flash");
    }

    @Test void aiFailureFallsBackToKeywordRules() {
        AiCoach coach = mock(AiCoach.class);
        when(coach.enabled()).thenReturn(true);
        when(coach.review(any(), any())).thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized", org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));

        Assessment result = service(coach).assess(job, "o/r", "");

        assertThat(result.assessedBy()).isEqualTo("rules");
        assertThat(result.summary()).startsWith("AI review unavailable:").contains("rejected the API key").doesNotContain("401 Unauthorized");
        assertThat(result.gaps()).extracting(Gap::status).containsExactly("DEMONSTRATED", "DEMONSTRATED", "DEMONSTRATED", "NOT_DEMONSTRATED");
    }
}
