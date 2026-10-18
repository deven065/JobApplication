package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AccountsAndReadyJobsTests {
    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:accounts_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
    }

    @Test void tokensAreStoredEncryptedAndSessionsSurviveRestart() {
        JdbcTemplate jdbc = jdbc();
        AuthStore store = new AuthStore(jdbc, "a-long-test-secret", "");
        String session = store.signIn(new AuthUser("deven065", "Deven", null, false), "gho_exampleToken123");

        String stored = jdbc.queryForObject("SELECT token FROM coach_users WHERE login = 'deven065'", String.class);
        assertThat(stored).doesNotContain("gho_exampleToken123");
        AuthStore restarted = new AuthStore(jdbc, "a-long-test-secret", "");
        assertThat(restarted.session(session)).get().extracting(AuthStore.Session::token).isEqualTo("gho_exampleToken123");
        assertThat(new AuthStore(jdbc, "a-different-secret", "").session(session)).isEmpty();  // wrong key: signed out, not an error

        restarted.signOut(session);
        assertThat(restarted.session(session)).isEmpty();
    }

    @Test void signedInUsersSeeOnlyTheirOwnJobs() {
        JdbcTemplate jdbc = jdbc();
        StateStore state = new StateStore(jdbc);
        AuthStore auth = new AuthStore(jdbc, "secret", "");
        GitHubPortfolioService github = mock(GitHubPortfolioService.class);
        CareerCoachController controller = new CareerCoachController(new JobDiscoveryService(), new AssessmentService(github, state), github, state);
        controller.useAuthStore(auth);
        var posting = new CareerCoachController.DiscoverJob("Intern", "Example", "https://example.com/job", "Java and SQL");

        JobRequirement mine = GitHubCredentials.as("deven065", "token", () -> controller.discover(posting));
        JobRequirement anonymous = controller.discover(posting);

        assertThat(GitHubCredentials.as("deven065", "token", controller::savedJobs)).extracting(JobRequirement::id).containsExactly(mine.id());
        assertThat(controller.savedJobs()).extracting(JobRequirement::id).containsExactly(anonymous.id());
        assertThatThrownBy(() -> GitHubCredentials.as("someone-else", "token", () -> controller.history(mine.id(), "o/r")))
                .isInstanceOf(SecurityException.class);
        // Ownership is persisted, so the same account sees its jobs on another device after a restart.
        CareerCoachController restarted = new CareerCoachController(new JobDiscoveryService(), new AssessmentService(github, state), github, state);
        restarted.useAuthStore(new AuthStore(jdbc, "secret", ""));
        assertThat(GitHubCredentials.as("deven065", "token", restarted::savedJobs)).extracting(JobRequirement::id).containsExactly(mine.id());
    }

    @Test void jobTextSkillsUseWordBoundaries() {
        assertThat(JobSkillMatcher.skillsIn("Experience with JavaScript and digital marketing")).doesNotContain(Skill.JAVA, Skill.GIT);
        assertThat(JobSkillMatcher.skillsIn("Strong Java, Spring Boot, PostgreSQL and Git; unit tests are a plus"))
                .contains(Skill.JAVA, Skill.SPRING_BOOT, Skill.POSTGRESQL, Skill.SQL, Skill.GIT, Skill.TESTING);
        assertThat(JobSkillMatcher.skillsIn("React Native mobile developer")).doesNotContain(Skill.REACT);
    }

    @Test void postingsAreScoredByWhatTheRepositoryProves() {
        PortfolioScan scan = new PortfolioScan("o/r", "main", "abc", Instant.now(),
                Map.of(Skill.DOCKER, List.of(new Evidence(Skill.DOCKER, "Dockerfile", "u", "r", "FROM node"))), 3, 0, false);
        JobRequirement job = new JobRequirement("j", "Intern", "Example", "https://example.com", "", List.of(Skill.JAVA, Skill.SQL, Skill.TESTING), Instant.now());
        Assessment assessed = new Assessment(job, scan, List.of(
                new Gap(Skill.JAVA, "DEMONSTRATED", "", List.of()), new Gap(Skill.SQL, "PARTIAL", "", List.of()), new Gap(Skill.TESTING, "NOT_DEMONSTRATED", "", List.of())));
        var levels = ReadyJobsService.levels(scan, List.of(assessed));

        ReadyJob ready = ReadyJobsService.score(new JobListing("GREENHOUSE", "b", "1", "Backend Intern", "Co", "", "https://x"),
                List.of(Skill.JAVA, Skill.SQL, Skill.DOCKER, Skill.TESTING), levels);

        assertThat(ready.met()).containsExactly(Skill.JAVA);
        assertThat(ready.partial()).containsExactly(Skill.SQL, Skill.DOCKER);  // Docker: unreviewed scan signal
        assertThat(ready.missing()).containsExactly(Skill.TESTING);
        assertThat(ready.score()).isEqualTo(50);  // (1 + 0.5 × 2) / 4

        // A posting with only two checkable requirements cannot reach 100%: thin evidence is scored as if there were three.
        ReadyJob thin = ReadyJobsService.score(new JobListing("GREENHOUSE", "b", "2", "AI Engineer", "Co", "", "https://x"), List.of(Skill.JAVA, Skill.SQL), levels);
        assertThat(thin.score()).isEqualTo(50);  // (1 + 0.5) / 3
    }
}
