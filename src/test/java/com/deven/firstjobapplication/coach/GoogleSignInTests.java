package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.AuthUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoogleSignInTests {
    private final ObjectMapper json = new ObjectMapper();

    @Test void googleProfileBecomesItsOwnAccountTypeAndUnverifiedEmailsAreRefused() throws Exception {
        AuthUser user = GoogleOAuth.toUser(json.readTree("{\"sub\":\"1234567890\",\"email\":\"asha@gmail.com\",\"email_verified\":true,\"name\":\"Asha Rao\",\"picture\":\"https://lh3.example/a.jpg\"}"));
        assertThat(user.login()).isEqualTo("google:1234567890");   // Google's stable id, never the email, so it cannot clash with a GitHub or email account
        assertThat(user.provider()).isEqualTo("google");
        assertThat(user.name()).isEqualTo("Asha Rao");
        assertThat(user.avatarUrl()).isEqualTo("https://lh3.example/a.jpg");
        assertThat(user.privateAccess()).isFalse();
        assertThat(user.issueAccess()).isFalse();

        assertThat(GoogleOAuth.toUser(json.readTree("{\"sub\":\"9\",\"email\":\"ravi@gmail.com\",\"email_verified\":\"true\"}")).name()).isEqualTo("ravi");  // no display name: use the email's local part
        assertThatThrownBy(() -> GoogleOAuth.toUser(json.readTree("{\"sub\":\"9\",\"email\":\"x@gmail.com\",\"email_verified\":false}"))).hasMessageContaining("not verified");
        assertThatThrownBy(() -> GoogleOAuth.toUser(json.readTree("{\"email\":\"x@gmail.com\",\"email_verified\":true}"))).hasMessageContaining("did not identify");
    }

    @Test void theAuthorizeUrlCarriesTheRightParametersAndAnUnconfiguredServerSaysSo() {
        GoogleOAuth configured = new GoogleOAuth("client-123", "secret-456", "https://proofpath.example/");
        assertThat(configured.configured()).isTrue();
        assertThat(configured.callbackUrl()).isEqualTo("https://proofpath.example/api/auth/google/callback");
        String url = configured.authorizeUrl("state-abc");
        assertThat(url).startsWith("https://accounts.google.com/o/oauth2/v2/auth?")
                .contains("client_id=client-123").contains("response_type=code").contains("state=state-abc")
                .contains("redirect_uri=https://proofpath.example/api/auth/google/callback").contains("scope=openid%20email%20profile");
        assertThat(new GoogleOAuth("", "", "https://proofpath.example").configured()).isFalse();
        assertThat(new GoogleOAuth("client-only", "", "https://proofpath.example").configured()).isFalse();
    }

    private record Rig(GoogleAuthController controller, AuthStore auth, List<Object> events) { }
    private Rig rig(GoogleOAuth google) {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:google_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        AuthStore auth = new AuthStore(jdbc, "secret", "");
        List<Object> events = new ArrayList<>();
        return new Rig(new GoogleAuthController(google, auth, events::add, "https://proofpath.example"), auth, events);
    }

    @Test void theCallbackChecksStateThenSignsInAndMovesGuestJobsToTheAccount() throws Exception {
        GoogleOAuth google = mock(GoogleOAuth.class);
        AuthUser user = new AuthUser("google:42", "Asha", null, false, false, "google");
        when(google.fetchUser("good-code")).thenReturn(user);
        Rig rig = rig(google);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("pp_google_state", "s1"), new Cookie("pp_guest", "guestidxxxxxxxxxxxxxxxxxxxx"));
        ResponseEntity<Void> done = rig.controller().callback("good-code", "s1", null, request);

        assertThat(done.getStatusCode().value()).isEqualTo(302);
        assertThat(done.getHeaders().getLocation().toString()).isEqualTo("https://proofpath.example/?signedIn=google");
        List<String> cookies = done.getHeaders().get(HttpHeaders.SET_COOKIE);
        String session = cookies.stream().filter(c -> c.startsWith("pp_session=")).findFirst().orElseThrow();
        assertThat(session).contains("HttpOnly").contains("Secure").contains("SameSite=Lax");
        String sessionId = session.substring("pp_session=".length(), session.indexOf(';'));
        assertThat(rig.auth().session(sessionId)).get().extracting(s -> s.user().login()).isEqualTo("google:42");
        assertThat(rig.auth().session(sessionId).orElseThrow().token()).isEmpty();      // no GitHub token for a Google account
        assertThat(rig.events()).contains(new SessionEnded("google:42"), new SessionAdopted("guest:guestidxxxxxxxxxxxxxxxxxxxx", "google:42"));
    }

    @Test void aWrongStateACancelledConsentOrAGoogleFailureNeverSignsAnyoneIn() throws Exception {
        GoogleOAuth google = mock(GoogleOAuth.class);
        when(google.fetchUser("boom")).thenThrow(new IllegalStateException("network"));
        when(google.fetchUser("unverified")).thenThrow(new IllegalArgumentException("Your Google email address is not verified, so it cannot be used to sign in."));
        Rig rig = rig(google);

        MockHttpServletRequest withState = new MockHttpServletRequest();
        withState.setCookies(new Cookie("pp_google_state", "s1"));
        MockHttpServletRequest noCookie = new MockHttpServletRequest();

        assertThat(rig.controller().callback("code", "forged", null, withState).getHeaders().getLocation().toString()).contains("authError=Sign-in+expired");
        assertThat(rig.controller().callback("code", "s1", null, noCookie).getHeaders().getLocation().toString()).contains("authError=Sign-in+expired");
        assertThat(rig.controller().callback(null, null, "access_denied", withState).getHeaders().getLocation().toString()).contains("cancelled");
        assertThat(rig.controller().callback("boom", "s1", null, withState).getHeaders().getLocation().toString()).contains("Google+sign-in+failed");
        assertThat(rig.controller().callback("unverified", "s1", null, withState).getHeaders().getLocation().toString()).contains("not+verified");
        assertThat(rig.events()).isEmpty();   // none of these started or ended any session
    }
}
