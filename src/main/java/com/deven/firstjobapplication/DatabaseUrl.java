package com.deven.firstjobapplication;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Hosting platforms (Render, Railway, Neon, Heroku) hand out DATABASE_URL as postgres://user:pass@host:port/db,
 * but Spring needs jdbc:postgresql://host:port/db with the credentials passed separately.
 * A jdbc: URL (or none at all) is left untouched.
 */
final class DatabaseUrl {
    private DatabaseUrl() { }

    static Map<String, String> toSpringProperties(String databaseUrl) {
        if (databaseUrl == null || databaseUrl.isBlank()) return Map.of();
        String value = databaseUrl.trim();
        if (!value.startsWith("postgres://") && !value.startsWith("postgresql://")) return Map.of();
        URI uri = URI.create(value.replaceFirst("^postgres(ql)?://", "postgresql://"));
        String jdbc = "jdbc:postgresql://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + uri.getRawPath()
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        String userInfo = uri.getRawUserInfo() == null ? "" : uri.getRawUserInfo();
        int colon = userInfo.indexOf(':');
        String user = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
        String password = colon < 0 ? "" : decode(userInfo.substring(colon + 1));
        return user.isEmpty()
                ? Map.of("spring.datasource.url", jdbc)
                : Map.of("spring.datasource.url", jdbc, "spring.datasource.username", user, "spring.datasource.password", password);
    }

    private static String decode(String part) { return URLDecoder.decode(part, StandardCharsets.UTF_8); }
}
