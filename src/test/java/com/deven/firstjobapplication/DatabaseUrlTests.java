package com.deven.firstjobapplication;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseUrlTests {
    @Test void platformUrlBecomesJdbcWithSeparateCredentials() {
        assertThat(DatabaseUrl.toSpringProperties("postgres://app:p%40ss:w0rd@db.example.com:5432/proofpath?sslmode=require"))
                .containsEntry("spring.datasource.url", "jdbc:postgresql://db.example.com:5432/proofpath?sslmode=require")
                .containsEntry("spring.datasource.username", "app")
                .containsEntry("spring.datasource.password", "p@ss:w0rd");
        assertThat(DatabaseUrl.toSpringProperties("postgresql://app:pw@host/db"))
                .containsEntry("spring.datasource.url", "jdbc:postgresql://host/db");
    }

    @Test void jdbcAndMissingUrlsAreLeftAlone() {
        assertThat(DatabaseUrl.toSpringProperties("jdbc:postgresql://localhost:5432/proofpath")).isEmpty();
        assertThat(DatabaseUrl.toSpringProperties(null)).isEmpty();
        assertThat(DatabaseUrl.toSpringProperties("")).isEmpty();
    }
}
