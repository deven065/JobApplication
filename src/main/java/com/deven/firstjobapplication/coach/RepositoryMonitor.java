package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polling is a durable fallback when GitHub webhooks cannot reach a localhost demo. */
@Component
public class RepositoryMonitor {
    private final AssessmentService assessments;
    private final GitHubPortfolioService github;
    public RepositoryMonitor(AssessmentService assessments, GitHubPortfolioService github) { this.assessments = assessments; this.github = github; }
    private AuthStore authStore;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void useAuthStore(AuthStore authStore) { this.authStore = authStore; }
    @Scheduled(fixedDelayString = "${coach.poll-ms:60000}")
    public void detectChanges() {
        for (RepoRegistration watch : assessments.watches()) {
            try {
                // Check each repository with its watcher's GitHub access when they signed in.
                var owner = authStore == null ? java.util.Optional.<AuthStore.Session>empty() : authStore.watchOwner(watch.repository());
                if (owner.isPresent()) GitHubCredentials.as(owner.get().user().login(), owner.get().token(), () -> { check(watch); return null; });
                else check(watch);
            } catch (Exception ignored) { /* transient GitHub failure: retry next interval */ }
        }
    }
    private void check(RepoRegistration watch) {
        String current = github.latestRevision(watch.repository(), watch.branch());
        String previous = assessments.lastScan(watch.repository()).map(PortfolioScan::revision).orElse("");
        if (!current.equals(previous)) assessments.reassessRepository(watch.repository(), current);
    }
}
