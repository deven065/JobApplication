package com.deven.firstjobapplication.coach;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;
import java.util.Map;

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
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", "GitHub API request failed: " + error.getStatusCode().value()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<Map<String, String>> invalidRequest(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
