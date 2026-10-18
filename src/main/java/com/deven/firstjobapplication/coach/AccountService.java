package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.AuthUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Email and password accounts for people who would rather not use GitHub. Passwords are stored only as salted PBKDF2
 * hashes. An email account has no GitHub token, so it can assess public repositories (with the server's token) but not
 * list private ones or open issues. Its login key is "email:" plus the address, which a GitHub username can never be.
 * With email configured, sign-up sends a verification link and "forgot password" sends a one-hour reset link.
 */
@Service
public class AccountService {
    static final String PROVIDER = "email";
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int ITERATIONS = 210_000, MAX_FAILURES = 5;
    private static final Duration LOCKOUT = Duration.ofMinutes(10), VERIFY_TTL = Duration.ofDays(3), RESET_TTL = Duration.ofHours(1), RESET_COOLDOWN = Duration.ofMinutes(1);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DUMMY_HASH = hash("not-a-real-password");

    private final JdbcTemplate jdbc;
    private final AuthStore auth;
    private final EmailService email;
    private final String baseUrl;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastResetMail = new ConcurrentHashMap<>();

    public AccountService(JdbcTemplate jdbc, AuthStore auth) { this(jdbc, auth, EmailService.disabled(), "http://localhost:5173"); }

    @Autowired
    public AccountService(JdbcTemplate jdbc, AuthStore auth, EmailService email, @Value("${app.base-url:http://localhost:5173}") String baseUrl) {
        this.jdbc = jdbc; this.auth = auth; this.email = email; this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        jdbc.execute("CREATE TABLE IF NOT EXISTS coach_accounts (email VARCHAR(254) PRIMARY KEY, name VARCHAR(120) NOT NULL, password_hash TEXT NOT NULL, created_at VARCHAR(40) NOT NULL)");
        // Accounts created before email verification existed are treated as verified.
        jdbc.execute("ALTER TABLE coach_accounts ADD COLUMN IF NOT EXISTS verified BOOLEAN NOT NULL DEFAULT TRUE");
        jdbc.execute("CREATE TABLE IF NOT EXISTS coach_email_tokens (token_hash VARCHAR(64) PRIMARY KEY, email VARCHAR(254) NOT NULL, purpose VARCHAR(16) NOT NULL, expires_at BIGINT NOT NULL)");
    }

    public record Signed(AuthUser user, String sessionId) { }
    public boolean emailEnabled() { return email.enabled(); }

    public Signed register(String name, String emailAddress, String password) {
        String address = normalise(emailAddress);
        if (!EMAIL.matcher(address).matches() || address.length() > 254) throw new IllegalArgumentException("Enter a valid email address.");
        checkPassword(password);
        String shown = name == null || name.isBlank() ? address.substring(0, address.indexOf('@')) : name.trim();
        if (shown.length() > 80) shown = shown.substring(0, 80);
        if (!jdbc.queryForList("SELECT email FROM coach_accounts WHERE email = ?", String.class, address).isEmpty())
            throw new IllegalArgumentException("An account with this email already exists. Sign in instead.");
        // Without an email service nobody can click a link, so such accounts start verified.
        jdbc.update("INSERT INTO coach_accounts (email, name, password_hash, created_at, verified) VALUES (?, ?, ?, ?, ?)", address, shown, hash(password), Instant.now().toString(), !email.enabled());
        if (email.enabled()) sendVerification(address, shown);
        return open(address, shown);
    }

    public Signed login(String emailAddress, String password) {
        String address = normalise(emailAddress);
        assertNotLocked(address);
        var rows = jdbc.queryForList("SELECT name, password_hash FROM coach_accounts WHERE email = ?", address);
        // Hash a dummy when the account is unknown so the response time does not reveal which emails exist.
        boolean ok = verify(password == null ? "" : password, rows.isEmpty() ? DUMMY_HASH : (String) rows.get(0).get("password_hash")) && !rows.isEmpty();
        if (!ok) { recordFailure(address); throw new IllegalArgumentException("Incorrect email or password."); }
        failures.remove(address);
        return open(address, (String) rows.get(0).get("name"));
    }

    // ---- Verification ----------------------------------------------------------------------------

    /** True for an email account whose address has not been confirmed yet. */
    public boolean unverified(String login) {
        if (login == null || !login.startsWith(PROVIDER + ":")) return false;
        var rows = jdbc.queryForList("SELECT verified FROM coach_accounts WHERE email = ?", Boolean.class, login.substring(PROVIDER.length() + 1));
        return !rows.isEmpty() && !rows.get(0);
    }
    public void verifyEmail(String token) {
        String address = consume(token, "verify");
        jdbc.update("UPDATE coach_accounts SET verified = TRUE WHERE email = ?", address);
    }
    public boolean resendVerification(String login) {
        if (!email.enabled() || !unverified(login)) return false;
        String address = login.substring(PROVIDER.length() + 1);
        return sendVerification(address, jdbc.queryForObject("SELECT name FROM coach_accounts WHERE email = ?", String.class, address));
    }
    private boolean sendVerification(String address, String name) {
        String link = baseUrl + "/verify?token=" + issue(address, "verify", VERIFY_TTL);
        return email.send(address, "Confirm your ProofPath email", "<p>Hi " + escape(name) + ",</p><p>Confirm your email to finish setting up ProofPath:</p><p><a href=\"" + link + "\">Confirm my email</a></p><p>If you did not create an account you can ignore this message.</p>");
    }

    // ---- Password reset --------------------------------------------------------------------------

    /** Always looks successful, so the form cannot be used to discover which emails have accounts. */
    public void requestReset(String emailAddress) {
        String address = normalise(emailAddress);
        if (!email.enabled() || !EMAIL.matcher(address).matches()) return;
        Instant previous = lastResetMail.get(address);
        if (previous != null && previous.isAfter(Instant.now().minus(RESET_COOLDOWN))) return;
        var rows = jdbc.queryForList("SELECT name FROM coach_accounts WHERE email = ?", String.class, address);
        if (rows.isEmpty()) return;
        lastResetMail.put(address, Instant.now());
        String link = baseUrl + "/reset?token=" + issue(address, "reset", RESET_TTL);
        email.send(address, "Reset your ProofPath password", "<p>Hi " + escape(rows.get(0)) + ",</p><p>Choose a new password within one hour:</p><p><a href=\"" + link + "\">Reset my password</a></p><p>If you did not ask for this you can ignore this message; your password has not changed.</p>");
    }
    /** Sets the new password, ends every existing session of the account and signs the user in. */
    public Signed resetPassword(String token, String newPassword) {
        checkPassword(newPassword);
        String address = consume(token, "reset");
        jdbc.update("UPDATE coach_accounts SET password_hash = ?, verified = TRUE WHERE email = ?", hash(newPassword), address);
        auth.signOutEverywhere(PROVIDER + ":" + address);
        failures.remove(address);
        return open(address, jdbc.queryForObject("SELECT name FROM coach_accounts WHERE email = ?", String.class, address));
    }
    public boolean resetAvailable() { return email.enabled(); }

    // ---- Internals -------------------------------------------------------------------------------

    private String issue(String address, String purpose, Duration ttl) {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("DELETE FROM coach_email_tokens WHERE email = ? AND purpose = ?", address, purpose);  // only the newest link works
        jdbc.update("INSERT INTO coach_email_tokens (token_hash, email, purpose, expires_at) VALUES (?, ?, ?, ?)", sha256(token), address, purpose, Instant.now().plus(ttl).toEpochMilli());
        return token;
    }
    private String consume(String token, String purpose) {
        String hash = token == null ? "" : sha256(token.trim());
        var rows = jdbc.queryForList("SELECT email, purpose, expires_at FROM coach_email_tokens WHERE token_hash = ?", hash);
        if (rows.isEmpty()) throw new IllegalArgumentException("This link is invalid or has expired.");
        jdbc.update("DELETE FROM coach_email_tokens WHERE token_hash = ?", hash);  // single use
        boolean fresh = ((Number) rows.get(0).get("expires_at")).longValue() > System.currentTimeMillis();
        if (!fresh || !purpose.equals(rows.get(0).get("purpose"))) throw new IllegalArgumentException("This link is invalid or has expired.");
        return (String) rows.get(0).get("email");
    }
    private Signed open(String address, String name) {
        AuthUser user = new AuthUser(PROVIDER + ":" + address, name, null, false, false, PROVIDER);
        return new Signed(user, auth.signIn(user, ""));
    }
    private static void checkPassword(String password) {
        if (password == null || password.length() < 8) throw new IllegalArgumentException("Use a password of at least 8 characters.");
        if (password.length() > 128) throw new IllegalArgumentException("Use a password of at most 128 characters.");
    }
    private static String normalise(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

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
