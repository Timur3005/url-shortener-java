package ru.mephi.shortener.core;

import java.time.Instant;
import java.util.UUID;

public record Notification(UUID owner, String code, Kind kind, Instant at) {
  public enum Kind {
    EXPIRED,
    LIMIT_REACHED
  }
}
