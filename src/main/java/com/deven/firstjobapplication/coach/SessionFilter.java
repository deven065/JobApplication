package com.deven.firstjobapplication.coach;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

/** Resolves the session cookie and makes the user's GitHub token the one used for this request. */
@Component
public class SessionFilter extends OncePerRequestFilter {
    static final String COOKIE = "pp_session";
    private final AuthStore auth;
    public SessionFilter(AuthStore auth) { this.auth = auth; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String id = sessionId(request);
        try {
            auth.session(id).ifPresent(session -> GitHubCredentials.set(session.user().login(), session.token()));
            chain.doFilter(request, response);
        } finally {
            GitHubCredentials.clear();
        }
    }
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/"); }

    static String sessionId(HttpServletRequest request) {
        return request.getCookies() == null ? null : Arrays.stream(request.getCookies())
                .filter(cookie -> COOKIE.equals(cookie.getName())).map(Cookie::getValue).findFirst().orElse(null);
    }
}
