package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GrowthFeatureTests {
    private final JobRequirement job = new JobRequirement("j1", "Backend Intern", "Acme", "https://example.com/job", "", List.of(Skill.SQL, Skill.JAVA), Instant.now());
    private final Evidence javaFile = new Evidence(Skill.JAVA, "src/App.java", "https://github.com/o/r/blob/abc/src/App.java", "class", "public class App {");
    private final Evidence sqlFile = new Evidence(Skill.SQL, "db/schema.sql", "https://github.com/o/r/blob/abc/db/schema.sql", "create", "CREATE TABLE jobs (id int)");
    private final PortfolioScan scan = new PortfolioScan("o/r", "main", "abc1234", Instant.now(), Map.of(Skill.JAVA, List.of(javaFile)), 5, 0, false);
    private Assessment assessment() {
        return new Assessment(job, scan, List.of(
                new Gap(Skill.JAVA, "DEMONSTRATED", "Real Java.", List.of(javaFile)),
                new Gap(Skill.SQL, "NOT_DEMONSTRATED", "No SQL files.", List.of(), "HIGH", "Add db/schema.sql with a SELECT ... JOIN.", "rules")));
    }
    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:growth_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
    }

    @Test void everySkillHasAPlanWithWorkingLinksAndSteps() {
        for (Skill skill : Skill.values()) {
            LearningPath path = LearningPaths.of(skill);
            assertThat(path).as(skill.name()).isNotNull();
            assertThat(path.resources()).hasSizeGreaterThanOrEqualTo(2).allSatisfy(r -> assertThat(r.url()).startsWith("https://"));
            assertThat(path.steps()).hasSizeGreaterThanOrEqualTo(3);
            assertThat(path.estimate()).startsWith("about");
            assertThat(path.project()).isNotBlank();
        }
    }

    @Test void resumeBulletsComeOnlyFromDemonstratedSkillsAndNameTheirFile() {
        var draft = ResumeService.build(scan, ResumeService.demonstrated(List.of(assessment())));
        assertThat(draft.bullets()).hasSize(1);
        assertThat(draft.bullets().get(0).skill()).isEqualTo(Skill.JAVA);
        assertThat(draft.bullets().get(0).text()).contains("src/App.java");
        assertThat(draft.bullets().get(0).url()).contains("/blob/abc/");
        assertThat(draft.notYetProven()).contains(Skill.SQL).doesNotContain(Skill.JAVA);
        assertThat(draft.projectLine()).isEqualTo("r | Java");
    }

    @Test void interviewQuestionsQuoteTheStudentsOwnCodeAndRejectInventedFiles() {
        InterviewQuestion templated = InterviewService.template(javaFile);
        assertThat(templated.question()).contains("src/App.java").contains("public class App {");

        List<Evidence> picks = InterviewService.picks(List.of(assessment()));
        assertThat(picks).containsExactly(javaFile);  // SQL has no evidence, so nothing is asked about it
        List<InterviewQuestion> kept = InterviewService.fromAi(picks, List.of(
                new AiCoach.InterviewItem("src/App.java", "Why is App a plain class?", "Mentions entry points."),
                new AiCoach.InterviewItem("src/Invented.java", "Explain Invented", ""),
                new AiCoach.InterviewItem("src/App.java", "Duplicate for the same file", "")));
        assertThat(kept).extracting(InterviewQuestion::path).containsExactly("src/App.java");
        assertThat(kept.get(0).skill()).isEqualTo(Skill.JAVA);
    }

    @Test void issueBodyHasAChecklistAndExplainsTheAutomaticRecheck() {
        Gap sqlGap = assessment().gaps().get(1);
        String body = GapActionService.body(job, sqlGap, "deven065");
        assertThat(body).contains("- [ ] Add db/schema.sql with a SELECT ... JOIN.").contains("- [ ] Write a SELECT ... JOIN across them")
                .contains("re-reads the changed files").contains("@deven065");
        assertThat(GapActionService.title(job, Skill.SQL)).isEqualTo("Show SQL in this repo (Backend Intern)");
    }

    @Test void fixGapOpensOneIssueAsTheUserAndWatchesTheRepository() {
        GitHubPortfolioService github = mock(GitHubPortfolioService.class);
        AssessmentService assessments = mock(AssessmentService.class);
        when(assessments.latestAssessment("j1", "o/r")).thenReturn(Optional.of(assessment()));
        when(assessments.watch("o/r")).thenReturn(Optional.empty());
        when(github.createIssue(anyString(), anyString(), anyString())).thenReturn(new GitHubPortfolioService.CreatedIssue(7, "https://github.com/o/r/issues/7"));
        when(github.issueIsOpen(anyString(), anyInt())).thenReturn(true);
        GapActionService service = new GapActionService(jdbc(), github, assessments);

        assertThatThrownBy(() -> service.fixGap(job, "o/r", Skill.SQL, null)).isInstanceOf(SecurityException.class);
        verify(github, never()).createIssue(any(), any(), any());

        IssueResult first = GitHubCredentials.as("deven065", "tok", () -> service.fixGap(job, "o/r", Skill.SQL, "deven065"));
        assertThat(first.number()).isEqualTo(7);
        assertThat(first.alreadyOpen()).isFalse();
        verify(assessments).watch(new RepoRegistration("o/r", "main", ""));

        IssueResult again = GitHubCredentials.as("deven065", "tok", () -> service.fixGap(job, "o/r", Skill.SQL, "deven065"));
        assertThat(again.alreadyOpen()).isTrue();  // the open issue is reused, not duplicated
        verify(github, org.mockito.Mockito.times(1)).createIssue(any(), any(), any());

        assertThatThrownBy(() -> GitHubCredentials.as("deven065", "tok", () -> service.fixGap(job, "o/r", Skill.JAVA, "deven065")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already demonstrated");
    }

    @Test void onlyPublicRepositoriesYouOwnCanBePublishedAndOnlyVerifiedSkillsAppear() {
        GitHubPortfolioService github = mock(GitHubPortfolioService.class);
        AssessmentService assessments = mock(AssessmentService.class);
        when(assessments.lastScan("deven065/app")).thenReturn(Optional.of(new PortfolioScan("deven065/app", "main", "abc1234", Instant.now(), Map.of(), 1, 0, false)));
        when(assessments.latestForRepository("deven065/app")).thenReturn(List.of(assessment()));
        when(assessments.latestForOwner("deven065/app", "deven065")).thenReturn(List.of(assessment()));
        when(github.repository("deven065/app")).thenReturn(new GitHubRepository("deven065/app", "main", "https://github.com/deven065/app", false));
        when(github.repository("deven065/secret")).thenReturn(new GitHubRepository("deven065/secret", "main", "https://github.com/deven065/secret", true));
        ProfileService profiles = new ProfileService(jdbc(), assessments, github, "https://proofpath.example");
        AuthUser user = new AuthUser("deven065", "Deven", null, false);

        assertThatThrownBy(() -> profiles.publish(user, "someone-else/app")).hasMessageContaining("you own");
        assertThatThrownBy(() -> profiles.publish(user, "deven065/secret")).hasMessageContaining("Private repositories cannot be published");
        assertThat(profiles.find("deven065")).isEmpty();

        PublicProfile published = profiles.publish(user, "deven065/app");
        assertThat(published.skills()).extracting(ProfileSkill::skill).containsExactly(Skill.JAVA);  // SQL is a gap, so it is not shown
        assertThat(published.skills().get(0).evidence().get(0).url()).contains("/blob/abc/");
        assertThat(published.profileUrl()).isEqualTo("https://proofpath.example/u/deven065");
        assertThat(published.badgeMarkdown()).contains("https://proofpath.example/api/public/deven065/badge.svg");
        assertThat(profiles.find("DEVEN065")).isPresent();

        // The page keeps its own copy: wiping the session's assessments afterwards does not empty it.
        when(assessments.latestForOwner("deven065/app", "deven065")).thenReturn(List.of());
        assertThat(profiles.find("deven065").orElseThrow().skills()).extracting(ProfileSkill::skill).containsExactly(Skill.JAVA);

        profiles.unpublish("deven065");
        assertThat(profiles.find("deven065")).isEmpty();
    }

    @Test void badgesAreEscapedSvg() {
        String svg = ProfileService.badge("SQL ✓ verified", true);
        assertThat(svg).startsWith("<svg").contains("SQL ✓ verified").contains("#0F6E56");
        assertThat(ProfileService.badge("<script>", false)).doesNotContain("<script>").contains("&lt;script&gt;");
    }
}
