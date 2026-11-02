package ru.mephi.shortener.core;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

public final class ShortenerService {
  private final AppConfig config;
  private final StateStore store;
  private final Clock clock;
  private final BrowserOpener browser;
  private final Supplier<String> codes;
  private State state;

  public ShortenerService(AppConfig config, StateStore store, Clock clock, BrowserOpener browser) {
    this(config, store, clock, browser, randomCodes());
  }

  public ShortenerService(
      AppConfig config,
      StateStore store,
      Clock clock,
      BrowserOpener browser,
      Supplier<String> codes) {
    this.config = config;
    this.store = store;
    this.clock = clock;
    this.browser = browser;
    this.codes = codes;
    try {
      state = store.load().copy();
    } catch (IOException e) {
      throw new IllegalStateException("Не удалось загрузить данные: " + e.getMessage(), e);
    }
    cleanup();
  }

  private static Supplier<String> randomCodes() {
    SecureRandom random = new SecureRandom();
    return () -> {
      byte[] bytes = new byte[9];
      random.nextBytes(bytes);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    };
  }

  public synchronized UUID currentUser() {
    return state.currentUser();
  }

  public synchronized void newUser() {
    commit(state.copy().withUser(null));
  }

  public synchronized void useUser(UUID user) {
    commit(state.copy().withUser(Objects.requireNonNull(user)));
  }

  public synchronized Link create(String url, Long requestedLimit) {
    var target = Urls.parse(url);
    long limit = requestedLimit == null ? config.defaultLimit() : requestedLimit;
    checkLimit(limit);
    cleanup();
    State next = state.copy();
    if (next.currentUser() == null) next = next.withUser(UUID.randomUUID());
    String code = null;
    for (int attempt = 0; attempt < 100; attempt++) {
      String candidate = codes.get();
      if (!validCode(candidate))
        throw new IllegalStateException("Генератор вернул некорректный код");
      if (!next.usedCodes().contains(candidate)) {
        code = candidate;
        break;
      }
    }
    if (code == null) throw new IllegalStateException("Не удалось получить уникальный код");
    Instant now = clock.instant();
    Link link =
        new Link(code, next.currentUser(), target, now, now.plus(config.ttl()), limit, 0, false);
    next.links().put(code, link);
    next.usedCodes().add(code);
    commit(next);
    return link;
  }

  public synchronized Link open(String reference) {
    cleanup();
    Link link = find(reference);
    if (link.visits() >= link.limit())
      throw new IllegalArgumentException("Лимит переходов исчерпан");
    State previous = state;
    State next = state.copy();
    long visits = link.visits() + 1;
    boolean exhausted = visits >= link.limit();
    Link updated =
        new Link(
            link.code(),
            link.owner(),
            link.url(),
            link.createdAt(),
            link.expiresAt(),
            link.limit(),
            visits,
            exhausted);
    next.links().put(link.code(), updated);
    if (exhausted && !link.limitNotified())
      next.notifications()
          .add(
              new Notification(
                  link.owner(), link.code(), Notification.Kind.LIMIT_REACHED, clock.instant()));
    commit(next);
    try {
      browser.open(link.url());
    } catch (IOException | RuntimeException e) {
      try {
        commit(previous);
      } catch (IllegalStateException rollbackError) {
        rollbackError.addSuppressed(e);
        throw new IllegalStateException(
            "Не удалось открыть браузер; переход зарезервирован и не восстановлен из-за ошибки сохранения: "
                + rollbackError.getMessage(),
            rollbackError);
      }
      throw new IllegalArgumentException("Не удалось открыть браузер: " + e.getMessage(), e);
    }
    return updated;
  }

  public synchronized List<Link> list() {
    cleanup();
    return state.links().values().stream()
        .filter(l -> l.owner().equals(state.currentUser()))
        .toList();
  }

  public synchronized List<Notification> notifications() {
    cleanup();
    return state.notifications().stream()
        .filter(n -> n.owner().equals(state.currentUser()))
        .toList();
  }

  public synchronized void updateLimit(String reference, long limit) {
    checkLimit(limit);
    cleanup();
    Link link = owned(reference);
    State next = state.copy();
    boolean exhausted = link.visits() >= limit;
    next.links()
        .put(
            link.code(),
            new Link(
                link.code(),
                link.owner(),
                link.url(),
                link.createdAt(),
                link.expiresAt(),
                limit,
                link.visits(),
                exhausted));
    if (exhausted && !link.limitNotified())
      next.notifications()
          .add(
              new Notification(
                  link.owner(), link.code(), Notification.Kind.LIMIT_REACHED, clock.instant()));
    commit(next);
  }

  public synchronized void updateUrl(String reference, String url) {
    var target = Urls.parse(url);
    cleanup();
    Link link = owned(reference);
    State next = state.copy();
    next.links()
        .put(
            link.code(),
            new Link(
                link.code(),
                link.owner(),
                target,
                link.createdAt(),
                link.expiresAt(),
                link.limit(),
                link.visits(),
                link.limitNotified()));
    commit(next);
  }

  public synchronized void delete(String reference) {
    cleanup();
    Link link = owned(reference);
    State next = state.copy();
    next.links().remove(link.code());
    commit(next);
  }

  public synchronized void cleanup() {
    State next = state.copy();
    Instant now = clock.instant();
    for (Link link : state.links().values()) {
      if (!now.isBefore(link.expiresAt())) {
        next.links().remove(link.code());
        next.notifications()
            .add(new Notification(link.owner(), link.code(), Notification.Kind.EXPIRED, now));
      }
    }
    if (next.links().size() != state.links().size()) commit(next);
  }

  public String shortUrl(String code) {
    return config.baseUrl().toString() + code;
  }

  private Link find(String reference) {
    String code = reference;
    if (code != null && code.startsWith(config.baseUrl().toString()))
      code = code.substring(config.baseUrl().toString().length());
    if (!validCode(code))
      throw new IllegalArgumentException("Некорректная короткая ссылка или код");
    Link link = state.links().get(code);
    if (link == null)
      throw new IllegalArgumentException("Ссылка не найдена или срок её действия истёк");
    return link;
  }

  private Link owned(String reference) {
    Link link = find(reference);
    if (!link.owner().equals(state.currentUser()))
      throw new IllegalArgumentException("Изменять ссылку может только владелец UUID");
    return link;
  }

  public static boolean validCode(String code) {
    return code != null && code.matches("[A-Za-z0-9_-]{1,64}");
  }

  private void checkLimit(long limit) {
    if (limit <= 0 || limit > config.maxLimit())
      throw new IllegalArgumentException("Лимит должен быть от 1 до " + config.maxLimit());
  }

  private void commit(State next) {
    try {
      store.save(next);
    } catch (IOException e) {
      throw new IllegalStateException(
          "Не удалось сохранить данные; изменение отменено: " + e.getMessage(), e);
    }
    state = next;
  }
}
