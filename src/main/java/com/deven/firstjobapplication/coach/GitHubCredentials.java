package com.deven.firstjobapplication.coach;

import java.util.function.Supplier;

/**
 * The signed-in user's GitHub token for the current thread. Set for each web request by {@link SessionFilter} and
 * for each watched repository by the background monitor, so every GitHub call made while it is set uses that
 * user's access (their private repositories and their own 5,000 requests/hour limit).
 */
public final class GitHubCredentials {
    private static final ThreadLocal<String> TOKEN = new ThreadLocal<>();
    private static final ThreadLocal<String> LOGIN = new ThreadLocal<>();
    private GitHubCredentials() { }

    public static String token() { return TOKEN.get(); }
    public static String login() { return LOGIN.get(); }

    static void set(String login, String token) { LOGIN.set(login); TOKEN.set(token); }
    static void clear() { LOGIN.remove(); TOKEN.remove(); }

    /** Runs work as the given user, restoring whatever was set before. */
    public static <T> T as(String login, String token, Supplier<T> work) {
        String previousLogin = LOGIN.get(), previousToken = TOKEN.get();
        set(login, token);
        try { return work.get(); }
        finally { if (previousLogin == null && previousToken == null) clear(); else set(previousLogin, previousToken); }
    }
}
