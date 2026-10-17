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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The brief's loop: a commit adds SQL, and only the affected skill is reassessed, citing the changed file. */
class AffectedSkillsTests {
    private final Evidence java1 = new Evidence(Skill.JAVA, "src/App.java", "https://github.com/o/r/blob/one/src/App.java#L3", "Java", "public class App {}");
    private final Evidence sql2 = new Evidence(Skill.SQL, "queries/app.sql", "https://github.com/o/r/blob/two/queries/app.sql#L1", "SQL", "SELECT id FROM applications");
    private final JobRequirement job = new JobRequirement("job", "Intern", "Example", "https://example.com/job", "", List.of(Skill.JAVA, Skill.SQL), Instant.now());
    private final PortfolioScan first = new PortfolioScan("o/r", "main", "one", Instant.now(), Map.of(Skill.JAVA, List.of(java1)), 1, 0, false);
    private final PortfolioScan second = new PortfolioScan("o/r", "main", "two", Instant.now(), Map.of(Skill.JAVA, List.of(java1), Skill.SQL, List.of(sql2)), 2, 0, false);

    private GitHubPortfolioService github() {
        GitHubPortfolioService github = mock(GitHubPortfolioService.class);
        when(github.repository("o/r")).thenReturn(new GitHubRepository("o/r", "main", "https://github.com/o/r", false));
        when(github.scan("o/r", "main")).thenReturn(first, second);
        return github;
    }
    private AssessmentService service(GitHubPortfolioService github, AiCoach coach) {
        StateStore store = new StateStore(new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:affected_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "")));
        AssessmentService service = new AssessmentService(github, store, coach);
        return service;
    }

    @Test void onlySkillsTouchedByTheCommitAreReassessed() {
        GitHubPortfolioService github = github();
        when(github.compare("o/r", "one", "two")).thenReturn(new ChangeSet("one", "two",
                List.of(new ChangedFile("queries/app.sql", "added", "https://github.com/o/r/blob/two/queries/app.sql", null)), List.of(), List.of(), true));
        AiCoach coach = mock(AiCoach.class);
        when(coach.enabled()).thenReturn(true);
        when(coach.label()).thenReturn("groq:test");
        when(coach.candidates(any(), any())).thenCallRealMethod();
        when(coach.review(any(), any())).thenReturn(new PortfolioReview("Java shown, SQL missing.", List.of(
                new SkillVerdict(Skill.JAVA, Verdict.DEMONSTRATED, Confidence.HIGH, "App.java is real Java.", List.of("src/App.java"), "Add a service."),
                new SkillVerdict(Skill.SQL, Verdict.NOT_DEMONSTRATED, Confidence.MEDIUM, "No SQL yet.", List.of(), "Add a query."))));
        when(coach.review(any(), any(), anyMap())).thenReturn(new PortfolioReview("Java and SQL are now shown.", List.of(
                new SkillVerdict(Skill.SQL, Verdict.DEMONSTRATED, Confidence.HIGH, "app.sql runs a real query.", List.of("queries/app.sql"), "Add a join."))));
        AssessmentService service = service(github, coach);

        service.assess(job, "o/r", "");
        service.watch(new RepoRegistration("o/r", "main", ""));
        Assessment updated = service.reassessRepository("o/r", "two").get(0);

        // Only SQL went back to the AI, with Java given as unchanged context for the summary.
        verify(coach).review(argThat(j -> j.requiredSkills().equals(List.of(Skill.SQL))), any(), eq(Map.of(Skill.JAVA, "DEMONSTRATED")));
        assertThat(updated.change().reassessedSkills()).containsExactly(Skill.SQL);
        assertThat(updated.change().carriedOverSkills()).containsExactly(Skill.JAVA);
        assertThat(updated.change().files()).extracting(ChangedFile::path).containsExactly("queries/app.sql");
        assertThat(updated.gaps()).extracting(Gap::skill).containsExactly(Skill.JAVA, Skill.SQL);  // job order kept
        assertThat(updated.gaps()).extracting(Gap::status).containsExactly("DEMONSTRATED", "DEMONSTRATED");
        assertThat(updated.gaps().get(1).evidence()).extracting(Evidence::path).containsExactly("queries/app.sql");
        // Carried-over evidence now points at the new commit, where the unchanged file is identical.
        assertThat(updated.gaps().get(0).evidence().get(0).url()).isEqualTo("https://github.com/o/r/blob/two/src/App.java#L3");
        assertThat(updated.summary()).isEqualTo("Java and SQL are now shown.");
    }

    @Test void unknownOrIncompleteChangeListReassessesEverything() {
        GitHubPortfolioService github = github();
        when(github.compare("o/r", "one", "two")).thenReturn(new ChangeSet("one", "two", List.of(), List.of(), List.of(), false));
        AssessmentService service = service(github, AiCoach.disabled());

        service.assess(job, "o/r", "");
        Assessment rescanned = service.assess(job, "o/r", "");  // manual rescan of the newer commit

        assertThat(rescanned.change().complete()).isFalse();
        assertThat(rescanned.change().reassessedSkills()).containsExactly(Skill.JAVA, Skill.SQL);
        assertThat(rescanned.gaps()).extracting(Gap::status).containsExactly("DEMONSTRATED", "DEMONSTRATED");
    }

    @Test void commitThatTouchesNoEvidenceKeepsEveryVerdict() {
        GitHubPortfolioService github = github();
        when(github.scan("o/r", "main")).thenReturn(first, new PortfolioScan("o/r", "main", "two", Instant.now(), Map.of(Skill.JAVA, List.of(java1)), 1, 0, false));
        when(github.compare("o/r", "one", "two")).thenReturn(new ChangeSet("one", "two",
                List.of(new ChangedFile("README.md", "modified", "https://github.com/o/r/blob/two/README.md", null)), List.of(), List.of(), true));
        AiCoach coach = mock(AiCoach.class);
        AssessmentService service = service(github, coach);  // coach disabled by default on a mock

        service.assess(job, "o/r", "");
        Assessment rescanned = service.assess(job, "o/r", "");

        assertThat(rescanned.change().reassessedSkills()).isEmpty();
        assertThat(rescanned.change().carriedOverSkills()).containsExactly(Skill.JAVA, Skill.SQL);
        assertThat(rescanned.portfolio().revision()).isEqualTo("two");
        verify(coach, never()).review(any(), any(), anyMap());
    }
}
