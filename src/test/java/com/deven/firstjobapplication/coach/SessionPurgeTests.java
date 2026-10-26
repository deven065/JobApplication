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
import static org.mockito.Mockito.when;

class SessionPurgeTests {
    private static final String ALICE = "guest:aliceaaaaaaaaaaaaaaaaaaaa", BOB = "guest:bobbbbbbbbbbbbbbbbbbbbbbb";

    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:purge_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
    private final StateStore store = new StateStore(jdbc);
    private final AuthStore auth = new AuthStore(jdbc, "secret", "");
    private final GitHubPortfolioService github = mock(GitHubPortfolioService.class);
    private final AssessmentService assessments = new AssessmentService(github, store);
    private final CareerCoachController controller = new CareerCoachController(new JobDiscoveryService(), assessments, github, store);

    SessionPurgeTests() {
        controller.useAuthStore(auth);
        Evidence java = new Evidence(Skill.JAVA, "src/App.java", "https://github.com/o/r/blob/one/src/App.java", "Java", "public class App {}");
        when(github.repository("o/r")).thenReturn(new GitHubRepository("o/r", "main", "https://github.com/o/r", false));
        when(github.scan("o/r", "main")).thenReturn(new PortfolioScan("o/r", "main", "one", Instant.now(), Map.of(Skill.JAVA, List.of(java)), 1, 0, false));
    }

    private JobRequirement jobFor(String who) {
        return GitHubCredentials.as(who, null, () -> controller.discover(new CareerCoachController.DiscoverJob("Intern", "Example", "https://example.com/job", "Java")));
    }

    @Test void endingASessionDeletesItsJobsAssessmentsHistoryAndWatchesButNobodyElses() {
        JobRequirement alices = jobFor(ALICE), bobs = jobFor(BOB);
        GitHubCredentials.as(ALICE, null, () -> {
            controller.assess(new CareerCoachController.AssessRequest(alices.id(), "o/r", ""));
            controller.watch(new CareerCoachController.WatchRequest("o/r", "", false));
            return null;
        });
        assertThat(assessments.history(alices.id(), "o/r")).isNotEmpty();
        assertThat(assessments.watch("o/r")).isPresent();

        controller.onSessionEnded(new SessionEnded(ALICE));

        assertThat(store.jobs()).doesNotContainKey(alices.id()).containsKey(bobs.id());
        assertThat(GitHubCredentials.as(ALICE, null, controller::savedJobs)).isEmpty();
        assertThat(GitHubCredentials.as(BOB, null, controller::savedJobs)).extracting(JobRequirement::id).containsExactly(bobs.id());
        assertThat(assessments.latestAssessment(alices.id(), "o/r")).isEmpty();
        assertThat(assessments.history(alices.id(), "o/r")).isEmpty();  // the scan history rows are gone from the database too
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coach_history", Integer.class)).isZero();
        assertThat(assessments.watch("o/r")).isEmpty();
        assertThat(assessments.lastScan("o/r")).isEmpty();
        assertThat(auth.jobOwners()).doesNotContainKey(alices.id()).containsKey(bobs.id());
    }

    @Test void oneVisitorCanNeverReadAnotherVisitorsJobOrHistory() {
        JobRequirement alices = jobFor(ALICE);
        assertThatThrownBy(() -> GitHubCredentials.as(BOB, null, () -> controller.history(alices.id(), "o/r"))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> GitHubCredentials.as(BOB, null, () -> controller.assess(new CareerCoachController.AssessRequest(alices.id(), "o/r", "")))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> controller.history(alices.id(), "o/r")).isInstanceOf(SecurityException.class);  // no identity at all
        assertThat(controller.savedJobs()).isEmpty();
    }

    @Test void scanDataIsOnlyShownToWhoeverAssessedTheRepository() {
        JobRequirement alices = jobFor(ALICE);
        GitHubCredentials.as(ALICE, null, () -> controller.assess(new CareerCoachController.AssessRequest(alices.id(), "o/r", "")));
        assertThat(GitHubCredentials.as(ALICE, null, () -> controller.lastScan("o", "r")).revision()).isEqualTo("one");
        assertThatThrownBy(() -> GitHubCredentials.as(BOB, null, () -> controller.lastScan("o", "r"))).hasMessageContaining("not been assessed in your session");
        assertThat(GitHubCredentials.as(BOB, null, () -> assessments.latestForRepository("o/r"))).isEmpty();
    }

    @Test void jobsSavedBeforeOwnershipExistedAreDeletedAtStartup() {
        store.saveJob(new JobRequirement("legacy-1", "Old", "Co", "https://x", "", List.of(Skill.JAVA), Instant.now()));
        CareerCoachController restarted = new CareerCoachController(new JobDiscoveryService(), new AssessmentService(github, store), github, store);
        restarted.useAuthStore(auth);
        assertThat(store.jobs()).doesNotContainKey("legacy-1");
        assertThat(GitHubCredentials.as(ALICE, null, restarted::savedJobs)).isEmpty();
    }

    @Test void idleGuestsAndOldSessionsAreFoundForClean_up() {
        auth.touchGuest("idleguestidxxxxxxxxxxxxxx");
        assertThat(auth.idleGuests(System.currentTimeMillis() + 1000)).contains("idleguestidxxxxxxxxxxxxxx");
        assertThat(auth.idleGuests(System.currentTimeMillis() - 60_000)).doesNotContain("idleguestidxxxxxxxxxxxxxx");
        auth.forgetGuest("idleguestidxxxxxxxxxxxxxx");
        assertThat(auth.idleGuests(System.currentTimeMillis() + 1000)).doesNotContain("idleguestidxxxxxxxxxxxxxx");

        AuthUser user = new AuthUser("deven065", "Deven", null, false);
        String session = auth.signIn(user, "token");
        assertThat(auth.expireSessions(Instant.now().minusSeconds(3600))).isEmpty();      // fresh session is kept
        assertThat(auth.session(session)).isPresent();
        assertThat(auth.expireSessions(Instant.now().plusSeconds(3600))).containsExactly("deven065");  // an old one ends
        assertThat(auth.session(session)).isEmpty();
    }
}
