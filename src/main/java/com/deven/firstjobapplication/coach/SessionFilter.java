package com.deven.firstjobapplication.coach;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Works out who is calling. A signed-in user's session cookie gives their login and GitHub token. Anyone else is a guest with a
 * private random id kept in a browser-session cookie, so one visitor never sees another's jobs or scan history.
 */
@Component
public class SessionFilter extends OncePerRequestFilter {
    static final String COOKIE = "pp_session", GUEST_COOKIE = "pp_guest", GUEST_PREFIX = "guest:";
    private static final Pattern GUEST_ID = Pattern.compile("^[A-Za-z0-9_-]{20,64}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AuthStore auth;
    private final boolean secure;
    public SessionFilter(AuthStore auth, @Value("${app.base-url:http://localhost:5173}") String baseUrl) {
        this.auth = auth;
        this.secure = baseUrl.trim().startsWith("https://");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        try {
            var session = auth.session(sessionId(request));
            if (session.isPresent()) GitHubCredentials.set(session.get().user().login(), session.get().token());
            else {
                String guest = guestId(request);
                if (guest == null && startsGuestSession(request)) {
                    guest = newGuestId();
                    response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(GUEST_COOKIE, guest).httpOnly(true).secure(secure).sameSite("Lax").path("/").build().toString());
                }
                if (guest != null) { auth.touchGuest(guest); GitHubCredentials.set(GUEST_PREFIX + guest, null); }
            }
            chain.doFilter(request, response);
        } finally {
            GitHubCredentials.clear();
        }
    }
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/"); }

    /** Only a write from a browser creates a guest, so health checks, crawlers, badges and webhooks never do. */
    private static boolean startsGuestSession(HttpServletRequest request) {
        String path = request.getRequestURI(), method = request.getMethod();
        boolean write = method.equals("POST") || method.equals("PUT") || method.equals("DELETE");
        return write && !path.startsWith("/api/webhooks") && !path.startsWith("/api/public") && !path.startsWith("/api/auth/");
    }
    private static String newGuestId() { byte[] bytes = new byte[24]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }

    static String sessionId(HttpServletRequest request) { return cookie(request, COOKIE); }
    /** The guest id from the cookie, or null when absent or malformed. */
    static String guestId(HttpServletRequest request) {
        String value = cookie(request, GUEST_COOKIE);
        return value != null && GUEST_ID.matcher(value).matches() ? value : null;
    }
    private static String cookie(HttpServletRequest request, String name) {
        return request.getCookies() == null ? null : Arrays.stream(request.getCookies())
                .filter(cookie -> name.equals(cookie.getName())).map(Cookie::getValue).findFirst().orElse(null);
    }
}
