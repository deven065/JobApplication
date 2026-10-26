package com.deven.firstjobapplication.coach;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Ends sessions that were never closed: signed-in sessions after 24 hours, guests after an hour without activity. */
@Component
public class SessionSweeper {
    static final Duration SESSION_TTL = Duration.ofHours(24), GUEST_IDLE = Duration.ofHours(1);
    private static final Logger log = LoggerFactory.getLogger(SessionSweeper.class);
    private final AuthStore auth;
    private final ApplicationEventPublisher events;
    public SessionSweeper(AuthStore auth, ApplicationEventPublisher events) { this.auth = auth; this.events = events; }

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT10M")
    public void sweep() {
        try {
            for (String login : auth.expireSessions(Instant.now().minus(SESSION_TTL))) events.publishEvent(new SessionEnded(login));
            for (String guest : auth.idleGuests(Instant.now().minus(GUEST_IDLE).toEpochMilli())) {
                events.publishEvent(new SessionEnded(SessionFilter.GUEST_PREFIX + guest));
                auth.forgetGuest(guest);
            }
        } catch (RuntimeException failure) { log.warn("Session clean-up failed: {}", failure.getMessage()); }
    }
}
