package com.deven.firstjobapplication;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** API keys belong in .env or the hosting platform's settings, never in files that get committed or baked into images. */
class NoSecretsInSourceTests {
    private static final Pattern SECRET = Pattern.compile("gsk_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|ghp_[A-Za-z0-9]{20,}|sk-ant-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{30,}");

    @Test void sourceAndConfigContainNoApiKeys() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Stream.of("src/main", "frontend/src", "Dockerfile", "docker-compose.yml", "render.yaml")
                .map(Path::of).filter(Files::exists).flatMap(NoSecretsInSourceTests::walk)) {
            offenders = files.filter(Files::isRegularFile).filter(NoSecretsInSourceTests::containsSecret).map(Path::toString).toList();
        }
        assertThat(offenders).as("Move these keys to .env (gitignored) and use ${VAR:} placeholders").isEmpty();
    }

    private static Stream<Path> walk(Path path) {
        try { return Files.walk(path); } catch (IOException error) { throw new IllegalStateException(error); }
    }
    private static boolean containsSecret(Path file) {
        try { return SECRET.matcher(Files.readString(file)).find(); } catch (IOException error) { return false; }
    }
}
