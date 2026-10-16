package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StateStoreTests {
    @Test void jobsWatchesAndHistorySurviveStoreRecreation() {
        String url = "jdbc:h2:mem:store_test;DB_CLOSE_DELAY=-1";
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        StateStore first = new StateStore(jdbc);
        JobRequirement job = new JobRequirement("job-1", "Backend Intern", "Example", "https://example.com/job",
                "Java and SQL", List.of(Skill.JAVA, Skill.SQL), Instant.now());
        PortfolioScan scan = new PortfolioScan("owner/repo", "develop", "abc123", Instant.now(), Map.of(), 7, 2, false);
        Assessment assessment = new Assessment(job, scan, List.of(new Gap(Skill.SQL, "NOT_DEMONSTRATED", "Missing in scan", List.of())));
        first.saveJob(job);
        first.saveWatch(new RepoRegistration("owner/repo", "develop", "secret"));
        first.saveAssessment("job-1|owner/repo", "develop", assessment);

        StateStore restarted = new StateStore(jdbc);
        assertThat(restarted.jobs().get("job-1").requiredSkills()).containsExactly(Skill.JAVA, Skill.SQL);
        assertThat(restarted.watches()).extracting(RepoRegistration::branch).containsExactly("develop");
        assertThat(restarted.assessments()).singleElement().satisfies(saved -> {
            assertThat(saved.branch()).isEqualTo("develop");
            assertThat(saved.assessment().portfolio().scannedFiles()).isEqualTo(7);
        });
        assertThat(restarted.history("job-1|owner/repo")).hasSize(1);
    }
}
