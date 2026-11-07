package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.AuthUser;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;

/** The two browser redirects of "Continue with Google": the way out to Google and the way back. */
@RestController
@RequestMapping("/api")
public class GoogleAuthController {
    private static final Logger log = LoggerFactory.getLogger(GoogleAuthController.class);
    private static final String STATE_COOKIE = "pp_google_state";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final GoogleOAuth google;
    private final AuthStore auth;
    private final ApplicationEventPublisher events;
    private final String baseUrl;

    public GoogleAuthController(GoogleOAuth google, AuthStore auth, ApplicationEventPublisher events, @Value("${app.base-url:http://localhost:5173}") String baseUrl) {
        this.google = google; this.auth = auth; this.events = events; this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
    }
    private boolean secure() { return baseUrl.startsWith("https://"); }

    @GetMapping("/auth/google/login") public ResponseEntity<Void> login() {
        if (!google.configured()) return redirect(baseUrl + "/?authError=" + enc("Google sign-in is not configured on this server."), null);
        byte[] bytes = new byte[24]; RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        ResponseCookie cookie = ResponseCookie.from(STATE_COOKIE, state).httpOnly(true).secure(secure()).sameSite("Lax").path("/api/auth").maxAge(Duration.ofMinutes(10)).build();
        return redirect(google.authorizeUrl(state), cookie);
    }

    @GetMapping("/auth/google/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error, HttpServletRequest request) {
        ResponseCookie clearState = ResponseCookie.from(STATE_COOKIE, "").path("/api/auth").maxAge(0).build();
        String expected = request.getCookies() == null ? null : Arrays.stream(request.getCookies()).filter(c -> STATE_COOKIE.equals(c.getName())).map(Cookie::getValue).findFirst().orElse(null);
        if (error != null) return redirect(baseUrl + "/?authError=" + enc("Google sign-in was cancelled."), clearState);
        if (code == null || state == null || expected == null || !expected.equals(state))
            return redirect(baseUrl + "/?authError=" + enc("Sign-in expired or was not started here. Please try again."), clearState);
        try {
            AuthUser user = google.fetchUser(code);
            String session = auth.signIn(user, "");
            // A new sign-in starts clean; jobs this browser read as a guest move to the account.
            events.publishEvent(new SessionEnded(user.login()));
            String guest = SessionFilter.guestId(request);
            if (guest != null) { events.publishEvent(new SessionAdopted(SessionFilter.GUEST_PREFIX + guest, user.login())); auth.forgetGuest(guest); }
            ResponseCookie sessionCookie = ResponseCookie.from(SessionFilter.COOKIE, session).httpOnly(true).secure(secure()).sameSite("Lax").path("/").maxAge(SessionSweeper.SESSION_TTL).build();
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(baseUrl + "/?signedIn=google"))
                    .header(HttpHeaders.SET_COOKIE, sessionCookie.toString()).header(HttpHeaders.SET_COOKIE, clearState.toString()).build();
        } catch (IllegalArgumentException refused) {
            return redirect(baseUrl + "/?authError=" + enc(refused.getMessage()), clearState);
        } catch (Exception failure) {
            log.warn("Google sign-in failed", failure);
            return redirect(baseUrl + "/?authError=" + enc("Google sign-in failed. Please try again."), clearState);
        }
    }

    private static ResponseEntity<Void> redirect(String location, ResponseCookie cookie) {
        ResponseEntity.BodyBuilder reply = ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location));
        if (cookie != null) reply.header(HttpHeaders.SET_COOKIE, cookie.toString());
        return reply.build();
    }
    private static String enc(String value) { return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
