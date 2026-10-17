package com.deven.firstjobapplication.coach;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.util.Arrays;

/** Keeps the React application's browser requests explicit and configurable. */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {
    private final String frontendOrigin;

    public WebConfiguration(@Value("${coach.web.allowed-origin}") String frontendOrigin) {
        this.frontendOrigin = frontendOrigin;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(Arrays.stream(frontendOrigin.split(",")).map(String::trim).toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "OPTIONS")
                .allowedHeaders("Content-Type", "X-GitHub-Event", "X-Hub-Signature-256")
                .maxAge(3600);
    }
}
