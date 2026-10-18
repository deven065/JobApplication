package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.AuthUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/**
 * Signed-in users, their sessions, and which jobs and watched repositories belong to whom. GitHub tokens are stored
 * encrypted (AES-GCM) with a key derived from SESSION_SECRET, so a database dump alone does not reveal them, and
 * sessions survive restarts of a free hosting instance.
 */
@Component
public class AuthStore {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private final SecretKeySpec key;

    public AuthStore(JdbcTemplate jdbc, @Value("${app.session-secret:}") String sessionSecret,
                     @Value("${github.oauth.client-secret:}") String oauthSecret) {
        this.jdbc = jdbc;
        String secret = sessionSecret != null && !sessionSecret.isBlank() ? sessionSecret : oauthSecret == null ? "" : oauthSecret;
        this.key = new SecretKeySpec(sha256(secret.isBlank() ? "proofpath-local-only" : secret), "AES");
        jdbc.execute("CREATE TABLE IF NOT EXISTS coach_users (login VARCHAR(100) PRIMARY KEY, payload TEXT NOT NULL, token TEXT NOT NULL, updated_at VARCHAR(40) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS coach_sessions (id VARCHAR(80) PRIMARY KEY, login VARCHAR(100) NOT NULL, created_at VARCHAR(40) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS coach_job_owners (job_id VARCHAR(128) PRIMARY KEY, login VARCHAR(100) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS coach_watch_owners (repository VARCHAR(255) PRIMARY KEY, login VARCHAR(100) NOT NULL)");
    }

    public record Session(AuthUser user, String token) { }

    @Transactional public String signIn(AuthUser user, String githubToken) {
        String now = Instant.now().toString();
        jdbc.update("DELETE FROM coach_users WHERE login = ?", user.login());
        jdbc.update("INSERT INTO coach_users (login, payload, token, updated_at) VALUES (?, ?, ?, ?)", user.login(), write(user), encrypt(githubToken), now);
        String id = randomId();
        jdbc.update("INSERT INTO coach_sessions (id, login, created_at) VALUES (?, ?, ?)", id, user.login(), now);
        return id;
    }
    public Optional<Session> session(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Optional.empty();
        List<String> logins = jdbc.queryForList("SELECT login FROM coach_sessions WHERE id = ?", String.class, sessionId);
        return logins.isEmpty() ? Optional.empty() : user(logins.get(0));
    }
    public void signOut(String sessionId) { jdbc.update("DELETE FROM coach_sessions WHERE id = ?", sessionId); }
    /** Ends every session of one account (used after a password reset). */
    public void signOutEverywhere(String login) { jdbc.update("DELETE FROM coach_sessions WHERE login = ?", login); }

    public Optional<Session> user(String login) {
        return jdbc.query("SELECT payload, token FROM coach_users WHERE login = ?", (row, i) -> {
            try { return new Session(json.readValue(row.getString("payload"), AuthUser.class), decrypt(row.getString("token"))); }
            catch (Exception error) { return null; }  // key changed: treat as signed out
        }, login).stream().filter(Objects::nonNull).findFirst();
    }

    @Transactional public void ownJob(String jobId, String login) {
        jdbc.update("DELETE FROM coach_job_owners WHERE job_id = ?", jobId);
        jdbc.update("INSERT INTO coach_job_owners (job_id, login) VALUES (?, ?)", jobId, login);
    }
    public Map<String, String> jobOwners() {
        Map<String, String> owners = new HashMap<>();
        jdbc.query("SELECT job_id, login FROM coach_job_owners", row -> { owners.put(row.getString("job_id"), row.getString("login")); });
        return owners;
    }
    @Transactional public void ownWatch(String repository, String login) {
        String key = repository.toLowerCase(Locale.ROOT);
        jdbc.update("DELETE FROM coach_watch_owners WHERE repository = ?", key);
        jdbc.update("INSERT INTO coach_watch_owners (repository, login) VALUES (?, ?)", key, login);
    }
    /** The session of whoever watches this repository, so background checks can use their access. */
    public Optional<Session> watchOwner(String repository) {
        List<String> logins = jdbc.queryForList("SELECT login FROM coach_watch_owners WHERE repository = ?", String.class, repository.toLowerCase(Locale.ROOT));
        return logins.isEmpty() ? Optional.empty() : user(logins.get(0));
    }

    String encrypt(String plain) {
        try {
            byte[] iv = new byte[12]; RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (Exception error) { throw new IllegalStateException("Could not protect the GitHub token", error); }
    }
    String decrypt(String stored) throws Exception {
        byte[] all = Base64.getDecoder().decode(stored);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
        return new String(cipher.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static String randomId() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static byte[] sha256(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
