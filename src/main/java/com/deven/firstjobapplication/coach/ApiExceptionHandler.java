package com.deven.firstjobapplication.coach;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;
import java.util.Map;
import java.util.NoSuchElementException;

/** Converts GitHub failures into actionable API responses instead of stack traces. */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(RestClientResponseException.class)
    ResponseEntity<Map<String, String>> githubFailure(RestClientResponseException error) {
        String body = error.getResponseBodyAsString();
        if (error.getStatusCode().value() == 403 && body.toLowerCase().contains("rate limit"))
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("message", "GitHub rate limit reached. Set GITHUB_TOKEN and restart the backend, then try again."));
        if (error.getStatusCode().value() == 404)
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "GitHub repository or branch was not found, or your token cannot access it."));
        if (error.getStatusCode().value() == 401)
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", "GitHub rejected the configured token. Check GITHUB_TOKEN and restart the backend."));
        String detail = githubMessage(body);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", "GitHub API request failed (" + error.getStatusCode().value() + ")"
                + (detail.isBlank() ? "." : ": " + detail)));
    }
    /** GitHub explains most failures in a JSON "message" (and sometimes "errors") field; pass it on. */
    private static String githubMessage(String body) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            String message = node.path("message").asText("");
            String first = node.path("errors").path(0).path("message").asText("");
            return first.isBlank() ? message : message + " (" + first + ")";
        } catch (Exception ignored) { return ""; }
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<Map<String, String>> invalidRequest(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> missing(NoSuchElementException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", error.getMessage()));
    }
    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Map<String, String>> forbidden(SecurityException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", error.getMessage()));
    }
}
