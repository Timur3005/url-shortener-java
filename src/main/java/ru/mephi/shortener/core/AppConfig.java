package ru.mephi.shortener.core;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

public record AppConfig(
    URI baseUrl,
    Duration ttl,
    long defaultLimit,
    long maxLimit,
    Duration cleanupInterval,
    Path storagePath) {
  public AppConfig {
    baseUrl = Urls.parse(baseUrl.toString());
    if (baseUrl.getQuery() != null || baseUrl.getFragment() != null)
      throw new IllegalArgumentException("short.base-url не должен содержать query/fragment");
    if (!baseUrl.toString().endsWith("/")) baseUrl = URI.create(baseUrl + "/");
    if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofDays(3650)) > 0)
      throw new IllegalArgumentException(
          "link.ttl должен быть положительным и не больше 3650 дней");
    if (cleanupInterval == null
        || cleanupInterval.compareTo(Duration.ofMillis(1)) < 0
        || cleanupInterval.compareTo(Duration.ofDays(365)) > 0)
      throw new IllegalArgumentException("cleanup.interval должен быть от PT0.001S до P365D");
    if (maxLimit <= 0 || maxLimit > 1_000_000_000L || defaultLimit <= 0 || defaultLimit > maxLimit)
      throw new IllegalArgumentException(
          "Лимиты должны быть положительными: default <= max <= 1000000000");
    storagePath = storagePath.toAbsolutePath().normalize();
  }

  public static AppConfig load(Path path) throws IOException {
    Properties p = new Properties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      p.load(reader);
    }
    try {
      return new AppConfig(
          URI.create(required(p, "short.base-url")),
          Duration.parse(required(p, "link.ttl")),
          Long.parseLong(required(p, "link.default-limit")),
          Long.parseLong(required(p, "link.max-limit")),
          Duration.parse(required(p, "cleanup.interval")),
          Path.of(required(p, "storage.path")));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Ошибка конфигурации: " + e.getMessage(), e);
    }
  }

  private static String required(Properties p, String key) {
    String value = p.getProperty(key);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Не задан " + key);
    return value.trim();
  }
}
