package com.deven.firstjobapplication;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class JobApplication {

    public static void main(String[] args) {
        // PostgreSQL rejects legacy zone names such as "Asia/Calcutta" that Windows JVMs report.
        // All timestamps are Instants, and the browser formats them in the viewer's local time.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        // A platform-style URL (postgresql://user:pass@host/db) works from a real environment variable or from the local .env file.
        String databaseUrl = System.getenv("DATABASE_URL");
        DatabaseUrl.toSpringProperties(databaseUrl != null && !databaseUrl.isBlank() ? databaseUrl : DatabaseUrl.fromDotEnv(java.nio.file.Path.of(".env")))
                .forEach(System::setProperty);
        SpringApplication.run(JobApplication.class, args);
    }

}
