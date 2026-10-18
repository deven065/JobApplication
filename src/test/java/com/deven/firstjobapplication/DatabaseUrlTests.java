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

    @Test void databaseUrlIsReadFromTheLocalDotEnvFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path env = dir.resolve(".env");
        java.nio.file.Files.writeString(env, "GROQ_API_KEY=x\n# DATABASE_URL=postgresql://old:old@old/db\nDATABASE_URL=\"postgresql://app:pw@host/db?sslmode=require\"\n");
        assertThat(DatabaseUrl.fromDotEnv(env)).isEqualTo("postgresql://app:pw@host/db?sslmode=require");
        assertThat(DatabaseUrl.fromDotEnv(dir.resolve("missing.env"))).isNull();
    }

    @Test void jdbcAndMissingUrlsAreLeftAlone() {
        assertThat(DatabaseUrl.toSpringProperties("jdbc:postgresql://localhost:5432/proofpath")).isEmpty();
        assertThat(DatabaseUrl.toSpringProperties(null)).isEmpty();
        assertThat(DatabaseUrl.toSpringProperties("")).isEmpty();
    }
}
