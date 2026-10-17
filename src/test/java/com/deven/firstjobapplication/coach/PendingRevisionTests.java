package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PendingRevisionTests {
    private final JobRequirement job = new JobRequirement("job", "Intern", "Example", "https://example.com/job", "", List.of(Skill.SQL), Instant.now());

    private PortfolioScan scan(String sha) {
        return new PortfolioScan("o/r", "main", sha, Instant.now(), Map.of(), 1, 0, false);
    }

    @Test void detectedCommitIsPendingUntilReassessmentFinishes() {
        GitHubPortfolioService github = mock(GitHubPortfolioService.class);
        when(github.repository("o/r")).thenReturn(new GitHubRepository("o/r", "main", "https://github.com/o/r", false));
        StateStore store = new StateStore(new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:pending_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "")));
        AssessmentService service = new AssessmentService(github, store);
        AtomicReference<String> seenDuringScan = new AtomicReference<>();
        AtomicReference<String> lastScanDuringScan = new AtomicReference<>();
        when(github.scan("o/r", "main")).thenReturn(scan("one")).thenAnswer(call -> {
            seenDuringScan.set(service.pendingRevision("o/r").orElse(null));
            lastScanDuringScan.set(service.lastScan("o/r").map(PortfolioScan::revision).orElse(null));
            return scan("two");
        }).thenThrow(new IllegalStateException("GitHub down"));

        service.assess(job, "o/r", "");
        service.watch(new RepoRegistration("o/r", "main", ""));
        service.reassessRepository("o/r", "two");

        assertThat(seenDuringScan.get()).isEqualTo("two");      // UI can show "reassessing two"
        assertThat(lastScanDuringScan.get()).isEqualTo("one");  // the old result stays current until the new one is ready
        assertThat(service.pendingRevision("o/r")).isEmpty();
        assertThat(service.latestAssessment("job", "o/r")).get().extracting(a -> a.portfolio().revision()).isEqualTo("two");

        assertThatThrownBy(() -> service.reassessRepository("o/r", "three")).hasMessage("GitHub down");
        assertThat(service.pendingRevision("o/r")).isEmpty();   // a failed check never leaves the UI stuck on "reassessing"
    }
}
