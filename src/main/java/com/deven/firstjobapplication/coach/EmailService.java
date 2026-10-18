package com.deven.firstjobapplication.coach;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Sends account emails through Resend's HTTP API (free tier). With no RESEND_API_KEY the service is disabled:
 * accounts are treated as verified and password reset is unavailable, so nothing breaks without email.
 */
@Service
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private final RestClient http;
    private final String from;
    private final boolean enabled;

    public EmailService(@Value("${email.resend-api-key:}") String apiKey, @Value("${email.from:ProofPath <onboarding@resend.dev>}") String from) {
        String key = apiKey == null ? "" : apiKey.trim();
        this.enabled = !key.isEmpty();
        this.from = from;
        this.http = enabled ? RestClient.builder().baseUrl("https://api.resend.com").defaultHeader("Authorization", "Bearer " + key).build() : null;
        if (enabled) log.info("Account emails enabled");
    }
    /** Tests and local runs use this to get a disabled sender. */
    public static EmailService disabled() { return new EmailService("", ""); }

    public boolean enabled() { return enabled; }

    /** Returns false (and logs why) instead of throwing, so a mail outage never breaks sign-up. */
    public boolean send(String to, String subject, String html) {
        if (!enabled) return false;
        try {
            http.post().uri("/emails").body(Map.of("from", from, "to", List.of(to), "subject", subject, "html", html)).retrieve().toBodilessEntity();
            return true;
        } catch (RuntimeException failure) {
            log.warn("Could not send email: {}", failure.getMessage());
            return false;
        }
    }
}
