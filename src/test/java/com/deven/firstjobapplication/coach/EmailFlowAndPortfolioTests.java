package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailFlowAndPortfolioTests {
    /** An enabled sender that records mail instead of sending it. */
    static class Outbox extends EmailService {
        final List<String[]> sent = new ArrayList<>();
        Outbox() { super("test-key", "ProofPath <test@example.com>"); }
        @Override public boolean send(String to, String subject, String html) { sent.add(new String[]{to, subject, html}); return true; }
        String lastToken() {
            Matcher m = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(sent.get(sent.size() - 1)[2]);
            assertThat(m.find()).isTrue();
            return m.group(1);
        }
    }
    private JdbcTemplate jdbc;
    private AuthStore store;
    private AccountService accounts(EmailService email) {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:flow_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        store = new AuthStore(jdbc, "test-secret", "");
        return new AccountService(jdbc, store, email, "https://proofpath.example");
    }

    @Test void newAccountsMustConfirmTheirEmailWithASingleUseLink() {
        Outbox mail = new Outbox();
        AccountService accounts = accounts(mail);
        accounts.register("Asha", "asha@example.com", "longenough");
        assertThat(accounts.unverified("email:asha@example.com")).isTrue();
        assertThat(mail.sent.get(0)[0]).isEqualTo("asha@example.com");
        assertThat(mail.sent.get(0)[2]).contains("https://proofpath.example/verify?token=");

        String token = mail.lastToken();
        accounts.verifyEmail(token);
        assertThat(accounts.unverified("email:asha@example.com")).isFalse();
        assertThatThrownBy(() -> accounts.verifyEmail(token)).hasMessageContaining("invalid or has expired");
    }

    @Test void resetLinkSetsANewPasswordEndsOldSessionsAndWorksOnce() {
        Outbox mail = new Outbox();
        AccountService accounts = accounts(mail);
        String oldSession = accounts.register("Asha", "asha@example.com", "longenough").sessionId();
        mail.sent.clear();

        accounts.requestReset("nobody@example.com");  // unknown address: same silent result, no mail
        assertThat(mail.sent).isEmpty();
        accounts.requestReset("ASHA@example.com");
        assertThat(mail.sent).hasSize(1);
        String token = mail.lastToken();

        assertThatThrownBy(() -> accounts.resetPassword(token, "short")).hasMessageContaining("at least 8");
        var signedIn = accounts.resetPassword(token, "a brand new password");
        assertThat(store.session(signedIn.sessionId())).isPresent();
        assertThat(store.session(oldSession)).isEmpty();  // every earlier session is ended
        assertThatThrownBy(() -> accounts.login("asha@example.com", "longenough")).hasMessage("Incorrect email or password.");
        assertThat(accounts.login("asha@example.com", "a brand new password").user().name()).isEqualTo("Asha");
        assertThatThrownBy(() -> accounts.resetPassword(token, "another password")).hasMessageContaining("invalid or has expired");
    }

    @Test void expiredAndWrongPurposeLinksAreRejected() {
        Outbox mail = new Outbox();
        AccountService accounts = accounts(mail);
        accounts.register("Asha", "asha@example.com", "longenough");
        String verifyToken = mail.lastToken();
        assertThatThrownBy(() -> accounts.resetPassword(verifyToken, "a brand new password")).hasMessageContaining("invalid or has expired");  // a verify link cannot reset

        accounts.requestReset("asha@example.com");
        String resetToken = mail.lastToken();
        jdbc.update("UPDATE coach_email_tokens SET expires_at = ?", System.currentTimeMillis() - 1000);
        assertThatThrownBy(() -> accounts.resetPassword(resetToken, "a brand new password")).hasMessageContaining("invalid or has expired");
    }

    @Test void withoutAnEmailServiceAccountsStartVerifiedAndResetIsUnavailable() {
        AccountService accounts = accounts(EmailService.disabled());
        accounts.register("Asha", "asha@example.com", "longenough");
        assertThat(accounts.unverified("email:asha@example.com")).isFalse();
        assertThat(accounts.resetAvailable()).isFalse();
        accounts.requestReset("asha@example.com");  // quietly does nothing
    }

    // ---- Combined portfolio ----------------------------------------------------------------------

    private Evidence file(Skill skill, String path) { return new Evidence(skill, path, "https://github.com/x/blob/abc/" + path, "r", "line"); }
    private Assessment assessment(JobRequirement job, String repo, Gap... gaps) {
        PortfolioScan scan = new PortfolioScan(repo, "main", "abc1234", Instant.now(), Map.of(), 3, 0, false);
        return new Assessment(job, scan, List.of(gaps));
    }

    @Test void theBestEvidenceAcrossRepositoriesWinsAndIsCitedWithItsRepository() {
        JobRequirement job = new JobRequirement("j", "Intern", "Acme", "https://x", "", List.of(Skill.JAVA, Skill.SQL), Instant.now());
        Assessment api = assessment(job, "me/api",
                new Gap(Skill.JAVA, "DEMONSTRATED", "Real Java.", List.of(file(Skill.JAVA, "App.java"))),
                new Gap(Skill.SQL, "NOT_DEMONSTRATED", "No SQL here.", List.of()));
        Assessment data = assessment(job, "me/data",
                new Gap(Skill.JAVA, "PARTIAL", "Thin Java.", List.of(file(Skill.JAVA, "Tool.java"))),
                new Gap(Skill.SQL, "DEMONSTRATED", "Joins and indexes.", List.of(file(Skill.SQL, "queries.sql"))));

        CombinedPortfolio combined = PortfolioMerger.merge(job, List.of(api, data));

        assertThat(combined.gaps()).extracting(Gap::status).containsExactly("DEMONSTRATED", "DEMONSTRATED");
        assertThat(combined.readiness()).isEqualTo(100);
        assertThat(combined.perRepository()).extracting(RepoScore::readiness).containsExactly(50, 75);  // each repo alone is weaker
        Gap sql = combined.gaps().get(1);
        assertThat(sql.explanation()).startsWith("Best evidence is in me/data.");
        assertThat(sql.evidence()).extracting(Evidence::path).containsExactly("me/data · queries.sql");
        assertThat(combined.provenBy().get(Skill.JAVA)).containsExactly("me/api");  // the partial repo does not count as proof
    }

    @Test void aSingleRepositoryKeepsItsPlainEvidencePathsAndMissingSkillsStayGaps() {
        JobRequirement job = new JobRequirement("j", "Intern", "Acme", "https://x", "", List.of(Skill.JAVA, Skill.SQL), Instant.now());
        Assessment only = assessment(job, "me/api",
                new Gap(Skill.JAVA, "DEMONSTRATED", "Real Java.", List.of(file(Skill.JAVA, "App.java"))),
                new Gap(Skill.SQL, "NOT_DEMONSTRATED", "No SQL here.", List.of()));
        CombinedPortfolio combined = PortfolioMerger.merge(job, List.of(only));
        assertThat(combined.gaps().get(0).evidence()).extracting(Evidence::path).containsExactly("App.java");
        assertThat(combined.gaps().get(1).status()).isEqualTo("NOT_DEMONSTRATED");
        assertThat(combined.readiness()).isEqualTo(50);
        assertThatThrownBy(() -> PortfolioMerger.merge(job, List.of())).hasMessageContaining("at least one");
    }
}
