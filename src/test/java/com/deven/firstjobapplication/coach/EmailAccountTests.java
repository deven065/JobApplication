package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.AuthUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailAccountTests {
    private JdbcTemplate jdbc;
    private AuthStore store;
    private AccountService accounts() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:email_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        store = new AuthStore(jdbc, "test-secret", "");
        return new AccountService(jdbc, store);
    }

    @Test void registerThenSignInWithTheSamePassword() {
        AccountService accounts = accounts();
        var created = accounts.register("Asha", "  Asha@Example.com ", "correct horse");
        assertThat(created.user().login()).isEqualTo("email:asha@example.com");
        assertThat(created.user().provider()).isEqualTo("email");
        assertThat(store.session(created.sessionId())).isPresent();

        var again = accounts.login("ASHA@example.com", "correct horse");
        assertThat(again.user().name()).isEqualTo("Asha");
        assertThat(again.sessionId()).isNotEqualTo(created.sessionId());
    }

    @Test void passwordsAreStoredOnlyAsSaltedHashes() {
        AccountService accounts = accounts();
        accounts.register("Asha", "asha@example.com", "correct horse");
        String stored = jdbc.queryForObject("SELECT password_hash FROM coach_accounts", String.class);
        assertThat(stored).startsWith("pbkdf2$").doesNotContain("correct horse");
        assertThat(AccountService.hash("same")).isNotEqualTo(AccountService.hash("same"));  // fresh salt each time
        assertThat(AccountService.verify("same", AccountService.hash("same"))).isTrue();
        assertThat(AccountService.verify("other", AccountService.hash("same"))).isFalse();
    }

    @Test void badInputAndDuplicatesAreRejectedWithClearMessages() {
        AccountService accounts = accounts();
        assertThatThrownBy(() -> accounts.register("A", "not-an-email", "longenough")).hasMessageContaining("valid email");
        assertThatThrownBy(() -> accounts.register("A", "a@example.com", "short")).hasMessageContaining("at least 8");
        accounts.register("A", "a@example.com", "longenough");
        assertThatThrownBy(() -> accounts.register("B", "A@example.com", "longenough")).hasMessageContaining("already exists");
    }

    @Test void wrongPasswordAndUnknownEmailLookTheSameAndRepeatedFailuresLockTheAccount() {
        AccountService accounts = accounts();
        accounts.register("A", "a@example.com", "longenough");
        assertThatThrownBy(() -> accounts.login("a@example.com", "wrong")).hasMessage("Incorrect email or password.");
        assertThatThrownBy(() -> accounts.login("nobody@example.com", "wrong")).hasMessage("Incorrect email or password.");
        for (int i = 0; i < 4; i++) assertThatThrownBy(() -> accounts.login("a@example.com", "wrong")).hasMessage("Incorrect email or password.");
        assertThatThrownBy(() -> accounts.login("a@example.com", "longenough")).hasMessageContaining("Too many failed attempts");
    }

    @Test void anEmailAccountNeverInheritsGitHubAccess() {
        AccountService accounts = accounts();
        var created = accounts.register("A", "a@example.com", "longenough");
        var session = store.session(created.sessionId()).orElseThrow();
        assertThat(session.token()).isEmpty();
        // A blank token is "no GitHub access", not "use the server's token".
        GitHubCredentials.as(session.user().login(), session.token(), () -> {
            assertThat(GitHubCredentials.token()).isNull();
            return null;
        });
        // Old stored users have no provider and count as GitHub accounts.
        assertThat(new AuthUser("deven065", "Deven", null, false).provider()).isEqualTo("github");
    }
}
