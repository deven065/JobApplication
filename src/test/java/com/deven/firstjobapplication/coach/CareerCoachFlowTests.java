package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CareerCoachFlowTests {
    @Test void editedJobCanBeAssessedWatchedAndRestored() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:flow_test;DB_CLOSE_DELAY=-1", "sa", ""));
        StateStore store = new StateStore(jdbc);
        GitHubPortfolioService github = mock(GitHubPortfolioService.class);
        when(github.repository("owner/repo")).thenReturn(new GitHubRepository("owner/repo", "develop", "https://github.com/owner/repo", false));
        Evidence java = new Evidence(Skill.JAVA, "src/App.java", "https://github.com/owner/repo/blob/one/src/App.java", "Java signal", "public class App {}");
        Evidence sql = new Evidence(Skill.SQL, "queries/app.sql", "https://github.com/owner/repo/blob/two/queries/app.sql", "SQL signal", "SELECT id FROM jobs");
        PortfolioScan first = new PortfolioScan("owner/repo", "develop", "one", Instant.now(), Map.of(Skill.JAVA, List.of(java)), 1, 0, false);
        PortfolioScan second = new PortfolioScan("owner/repo", "develop", "two", Instant.now().plusSeconds(1), Map.of(Skill.JAVA, List.of(java), Skill.SQL, List.of(sql)), 2, 0, false);
        when(github.scan("owner/repo", "develop")).thenReturn(first, second);

        AssessmentService assessments = new AssessmentService(github, store);
        CareerCoachController controller = new CareerCoachController(new JobDiscoveryService(), assessments, github, store);
        JobRequirement job = controller.discover(new CareerCoachController.DiscoverJob("Backend Intern", "Example", "https://example.com/job", "Java SQL"));
        controller.updateRequirements(job.id(), new CareerCoachController.RequirementsUpdate(List.of(Skill.JAVA, Skill.SQL)));
        Assessment initial = controller.assess(new CareerCoachController.AssessRequest(job.id(), "owner/repo", ""));
        assertThat(initial.portfolio().branch()).isEqualTo("develop");
        assertThat(initial.gaps()).extracting(Gap::status).containsExactly("DEMONSTRATED", "NOT_DEMONSTRATED");

        controller.watch(new CareerCoachController.WatchRequest("owner/repo", "", false));
        assertThat(controller.watchStatus("owner/repo").branch()).isEqualTo("develop");
        verify(github, times(1)).scan("owner/repo", "develop");
        assessments.reassessRepository("owner/repo");
        assertThat(controller.history(job.id(), "owner/repo")).hasSize(2);
        assertThat(controller.latestAssessment(job.id(), "owner/repo").gaps()).extracting(Gap::status)
                .containsExactly("DEMONSTRATED", "DEMONSTRATED");

        StateStore reopenedStore = new StateStore(jdbc);
        AssessmentService restarted = new AssessmentService(github, reopenedStore);
        assertThat(restarted.watches()).hasSize(1);
        assertThat(restarted.latestAssessment(job.id(), "owner/repo")).get().extracting(value -> value.portfolio().revision()).isEqualTo("two");
        CareerCoachController reopenedController = new CareerCoachController(new JobDiscoveryService(), restarted, github, reopenedStore);
        reopenedController.updateRequirements(job.id(), new CareerCoachController.RequirementsUpdate(List.of(Skill.SQL)));
        assertThat(restarted.latestAssessment(job.id(), "owner/repo")).isEmpty();
        assertThat(restarted.history(job.id(), "owner/repo")).hasSize(2);
        assertThat(new AssessmentService(github, new StateStore(jdbc)).latestAssessment(job.id(), "owner/repo")).isEmpty();
    }
}
