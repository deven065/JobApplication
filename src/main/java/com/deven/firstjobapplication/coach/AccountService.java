package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.AuthUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Email and password accounts for people who would rather not use GitHub. Passwords are stored only as salted PBKDF2
 * hashes. An email account has no GitHub token, so it can assess public repositories (with the server's token) but not
 * list private ones or open issues. Its login key is "email:" plus the address, which a GitHub username can never be.
 */
@Service
public class AccountService {
    static final String PROVIDER = "email";
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int ITERATIONS = 210_000, MAX_FAILURES = 5;
    private static final Duration LOCKOUT = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DUMMY_HASH = hash("not-a-real-password");

    private final JdbcTemplate jdbc;
    private final AuthStore auth;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public AccountService(JdbcTemplate jdbc, AuthStore auth) {
        this.jdbc = jdbc; this.auth = auth;
        jdbc.execute("CREATE TABLE IF NOT EXISTS coach_accounts (email VARCHAR(254) PRIMARY KEY, name VARCHAR(120) NOT NULL, password_hash TEXT NOT NULL, created_at VARCHAR(40) NOT NULL)");
    }

    public record Signed(AuthUser user, String sessionId) { }

    public Signed register(String name, String email, String password) {
        String address = normalise(email);
        if (!EMAIL.matcher(address).matches() || address.length() > 254) throw new IllegalArgumentException("Enter a valid email address.");
        if (password == null || password.length() < 8) throw new IllegalArgumentException("Use a password of at least 8 characters.");
        if (password.length() > 128) throw new IllegalArgumentException("Use a password of at most 128 characters.");
        String shown = name == null || name.isBlank() ? address.substring(0, address.indexOf('@')) : name.trim();
        if (shown.length() > 80) shown = shown.substring(0, 80);
        if (!jdbc.queryForList("SELECT email FROM coach_accounts WHERE email = ?", String.class, address).isEmpty())
            throw new IllegalArgumentException("An account with this email already exists. Sign in instead.");
        jdbc.update("INSERT INTO coach_accounts (email, name, password_hash, created_at) VALUES (?, ?, ?, ?)", address, shown, hash(password), Instant.now().toString());
        return open(address, shown);
    }

    public Signed login(String email, String password) {
        String address = normalise(email);
        assertNotLocked(address);
        var rows = jdbc.queryForList("SELECT name, password_hash FROM coach_accounts WHERE email = ?", address);
        // Hash a dummy when the account is unknown so the response time does not reveal which emails exist.
        boolean ok = verify(password == null ? "" : password, rows.isEmpty() ? DUMMY_HASH : (String) rows.get(0).get("password_hash")) && !rows.isEmpty();
        if (!ok) { recordFailure(address); throw new IllegalArgumentException("Incorrect email or password."); }
        failures.remove(address);
        return open(address, (String) rows.get(0).get("name"));
    }

    private Signed open(String address, String name) {
        AuthUser user = new AuthUser(PROVIDER + ":" + address, name, null, false, false, PROVIDER);
        return new Signed(user, auth.signIn(user, ""));
    }

    private static String normalise(String email) { return email == null ? "" : email.trim().toLowerCase(Locale.ROOT); }

    private void assertNotLocked(String address) {
        Deque<Instant> recent = failures.get(address);
        if (recent == null) return;
        synchronized (recent) {
            Instant cutoff = Instant.now().minus(LOCKOUT);
            while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) recent.pollFirst();
            if (recent.size() >= MAX_FAILURES) throw new IllegalStateException("Too many failed attempts. Try again in a few minutes.");
        }
    }
    private void recordFailure(String address) {
        Deque<Instant> recent = failures.computeIfAbsent(address, key -> new ArrayDeque<>());
        synchronized (recent) { recent.addLast(Instant.now()); }
    }

    static String hash(String password) {
        byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
        return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }
    static boolean verify(String password, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals("pbkdf2")) return false;
        byte[] expected = Base64.getDecoder().decode(parts[3]);
        return MessageDigest.isEqual(expected, derive(password, Base64.getDecoder().decode(parts[2]), Integer.parseInt(parts[1])));
    }
    private static byte[] derive(String password, byte[] salt, int iterations) {
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, 256)).getEncoded(); }
        catch (Exception error) { throw new IllegalStateException("Could not hash the password", error); }
    }
}
