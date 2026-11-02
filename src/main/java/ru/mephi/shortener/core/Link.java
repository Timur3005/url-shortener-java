package ru.mephi.shortener.core;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public record Link(
    String code,
    UUID owner,
    URI url,
    Instant createdAt,
    Instant expiresAt,
    long limit,
    long visits,
    boolean limitNotified) {}
