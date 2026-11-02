package ru.mephi.shortener;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.mephi.shortener.cli.Console;
import ru.mephi.shortener.core.*;
import ru.mephi.shortener.infra.*;

class ShortenerTest {
  @TempDir Path temp;
  private final MutableClock clock = new MutableClock();
  private final MemoryStore memory = new MemoryStore();
  private final List<URI> opened = new ArrayList<>();

  private AppConfig config() {
    return new AppConfig(
        URI.create("https://short.local/"),
        Duration.ofHours(24),
        2,
        100,
        Duration.ofSeconds(1),
        temp.resolve("state.properties"));
  }

  private ShortenerService service() {
    return new ShortenerService(config(), memory, clock, opened::add);
  }

  @Test
  void identityIsLazy() {
    var s = service();
    assertNull(s.currentUser());
    assertTrue(s.list().isEmpty());
    assertTrue(s.notifications().isEmpty());
    assertNull(s.currentUser());
    s.create("https://example.com", null);
    assertNotNull(s.currentUser());
  }

  @Test
  void everyCreateHasDifferentCodeAcrossOwners() {
    var s = service();
    var a = s.create("https://example.com", null);
    var b = s.create("https://example.com", null);
    s.newUser();
    var c = s.create("https://example.com", null);
    assertEquals(3, Set.of(a.code(), b.code(), c.code()).size());
    assertNotEquals(a.owner(), c.owner());
  }

  @Test
  void lastAllowedVisitWorksThenBlocks() {
    var s = service();
    var l = s.create("https://example.com", 2L);
    s.open(l.code());
    s.open(l.code());
    assertEquals(2, opened.size());
    assertThrows(IllegalArgumentException.class, () -> s.open(l.code()));
    assertEquals(2, s.list().get(0).visits());
    assertEquals(1, s.notifications().size());
    assertEquals(1, s.notifications().size());
  }

  @Test
  void raiseLimitReactivatesWithoutResettingVisits() {
    var s = service();
    var l = s.create("https://example.com", 1L);
    s.open(l.code());
    s.updateLimit(l.code(), 2);
    assertEquals(1, s.list().get(0).visits());
    s.open(l.code());
    assertEquals(2, s.list().get(0).visits());
    assertEquals(2, s.notifications().size());
  }

  @Test
  void loweringLimitNotifiesOnlyOnce() {
    var s = service();
    var l = s.create("https://example.com", 3L);
    s.open(l.code());
    s.updateLimit(l.code(), 1);
    s.updateLimit(l.code(), 1);
    assertEquals(1, s.notifications().size());
  }

  @Test
  void exactExpiryRemovesLinkAndPreservesNotification() {
    var s = service();
    var l = s.create("https://example.com", null);
    clock.now = l.expiresAt();
    assertThrows(IllegalArgumentException.class, () -> s.open(l.code()));
    assertTrue(s.list().isEmpty());
    assertEquals("EXPIRED", s.notifications().get(0).kind().name());
    s.cleanup();
    assertEquals(1, s.notifications().size());
  }

  @Test
  void remainsUsableBeforeExpiry() {
    var s = service();
    var l = s.create("https://example.com", null);
    clock.now = l.expiresAt().minusNanos(1);
    s.open(l.code());
    assertEquals(1, opened.size());
  }

  @Test
  void editingDoesNotChangeTtl() {
    var s = service();
    var l = s.create("https://example.com", null);
    clock.now = clock.now.plusSeconds(100);
    s.updateUrl(l.code(), "http://example.org/path");
    s.updateLimit(l.code(), 10);
    var edited = s.list().get(0);
    assertEquals(l.expiresAt(), edited.expiresAt());
    assertEquals("http://example.org/path", edited.url().toString());
  }

  @Test
  void foreignOwnerCannotEditDeleteOrSeeData() {
    var s = service();
    var l = s.create("https://example.com", 1L);
    s.open(l.code());
    s.newUser();
    s.create("https://other.example", null);
    assertThrows(IllegalArgumentException.class, () -> s.updateLimit(l.code(), 5));
    assertThrows(IllegalArgumentException.class, () -> s.updateUrl(l.code(), "https://a.example"));
    assertThrows(IllegalArgumentException.class, () -> s.delete(l.code()));
    assertEquals(1, s.list().size());
    assertTrue(s.notifications().isEmpty());
    s.useUser(l.owner());
    assertEquals(1, s.notifications().size());
  }

  @Test
  void visitorsNeedNoIdentity() {
    var s = service();
    var l = s.create("https://example.com", null);
    s.newUser();
    s.open(l.code());
    assertNull(s.currentUser());
  }

  @Test
  void browserFailureDoesNotConsumeVisit() {
    var s =
        new ShortenerService(
            config(),
            memory,
            clock,
            uri -> {
              throw new IOException("headless");
            });
    var l = s.create("https://example.com", null);
    assertThrows(IllegalArgumentException.class, () -> s.open(l.code()));
    assertEquals(0, s.list().get(0).visits());
    assertTrue(s.notifications().isEmpty());
  }

  @Test
  void saveFailureRollsBackIdentityAndLink() {
    var s = service();
    memory.fail = true;
    assertThrows(IllegalStateException.class, () -> s.create("https://example.com", null));
    assertNull(s.currentUser());
    memory.fail = false;
    assertTrue(s.list().isEmpty());
  }

  @Test
  void saveFailureRollsBackVisitsAndNotifications() {
    var s = service();
    var l = s.create("https://example.com", 1L);
    memory.fail = true;
    assertThrows(IllegalStateException.class, () -> s.open(l.code()));
    memory.fail = false;
    assertEquals(0, s.list().get(0).visits());
    assertTrue(s.notifications().isEmpty());
  }

  @Test
  void failedReservationNeverOpensBrowserEvenAfterRepeatedAttempts() {
    var s = service();
    var l = s.create("https://example.com", 1L);
    memory.fail = true;
    assertThrows(IllegalStateException.class, () -> s.open(l.code()));
    assertThrows(IllegalStateException.class, () -> s.open(l.code()));
    assertTrue(opened.isEmpty(), "Браузер нельзя вызывать без сохранённого визита");
    assertEquals(0, memory.state.links().get(l.code()).visits());
    assertTrue(memory.state.notifications().isEmpty());
  }

  @Test
  void failedBrowserAndFailedRollbackKeepDurableReservation() {
    AtomicInteger attempts = new AtomicInteger();
    var s =
        new ShortenerService(
            config(),
            memory,
            clock,
            uri -> {
              attempts.incrementAndGet();
              memory.fail = true;
              throw new IOException("headless");
            });
    var l = s.create("https://example.com", 1L);
    var error = assertThrows(IllegalStateException.class, () -> s.open(l.code()));
    assertTrue(error.getMessage().contains("зарезервирован"), error.getMessage());
    memory.fail = false;
    assertEquals(1, s.list().get(0).visits());
    assertEquals(1, s.notifications().size());
    assertEquals(1, memory.state.links().get(l.code()).visits());
    var restarted = service();
    assertThrows(IllegalArgumentException.class, () -> restarted.open(l.code()));
    assertEquals(1, attempts.get());
  }

  @Test
  void failedBrowserRollbackRemovesExhaustionNotificationOnDisk() {
    var s =
        new ShortenerService(
            config(),
            memory,
            clock,
            uri -> {
              throw new IOException("headless");
            });
    var l = s.create("https://example.com", 1L);
    assertThrows(IllegalArgumentException.class, () -> s.open(l.code()));
    var restarted = service();
    assertEquals(0, restarted.list().get(0).visits());
    assertFalse(restarted.list().get(0).limitNotified());
    assertTrue(restarted.notifications().isEmpty());
  }

  @Test
  void collisionIsRetriedIncludingDeletedCodes() {
    var sequence = new ArrayDeque<>(List.of("same", "same", "second", "same", "third"));
    var s = new ShortenerService(config(), memory, clock, opened::add, sequence::remove);
    var first = s.create("https://example.com", null);
    assertEquals("second", s.create("https://example.com", null).code());
    s.delete(first.code());
    assertEquals("third", s.create("https://example.com", null).code());
  }

  @Test
  void fullShortUrlAndCodeWork() {
    var s = service();
    var l = s.create("https://example.com", null);
    s.open(s.shortUrl(l.code()));
    s.open(l.code());
    assertEquals(2, opened.size());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ftp://example.com",
        "https:///missing",
        "https://u:p@example.com",
        "https://example.com/\n",
        "https://example.com/a b",
        "javascript:alert(1)",
        "https://example.com/%0afoo"
      })
  void invalidUrlsRejected(String url) {
    assertThrows(IllegalArgumentException.class, () -> service().create(url, null));
    assertNull(service().currentUser());
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1, 101, Long.MAX_VALUE})
  void invalidLimitsRejected(long limit) {
    assertThrows(
        IllegalArgumentException.class, () -> service().create("https://example.com", limit));
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://evil.example/abc", "https://short.local/abc?x=1", "../abc", ""})
  void invalidReferencesRejected(String reference) {
    assertThrows(IllegalArgumentException.class, () -> service().open(reference));
  }

  @Test
  void realStorageRestoresIdentityLinksVisitsAndNotifications() throws Exception {
    UUID owner;
    String code;
    try (var store = new FileStateStore(config().storagePath())) {
      var s = new ShortenerService(config(), store, clock, opened::add);
      var l = s.create("https://example.com", 1L);
      owner = l.owner();
      code = l.code();
      s.open(code);
    }
    try (var store = new FileStateStore(config().storagePath())) {
      var s = new ShortenerService(config(), store, clock, opened::add);
      assertEquals(owner, s.currentUser());
      assertEquals(1, s.list().get(0).visits());
      assertEquals(1, s.notifications().size());
      assertThrows(IllegalArgumentException.class, () -> s.open(code));
      assertEquals(1, s.notifications().size());
    }
  }

  @Test
  void startupCleanupPersistsExpirationAfterOfflineTime() throws Exception {
    try (var store = new FileStateStore(config().storagePath())) {
      var s = new ShortenerService(config(), store, clock, opened::add);
      s.create("https://example.com", null);
    }
    clock.now = clock.now.plus(Duration.ofDays(2));
    try (var store = new FileStateStore(config().storagePath())) {
      var s = new ShortenerService(config(), store, clock, opened::add);
      assertTrue(s.list().isEmpty());
      assertEquals(1, s.notifications().size());
    }
    try (var store = new FileStateStore(config().storagePath())) {
      var s = new ShortenerService(config(), store, clock, opened::add);
      assertEquals(1, s.notifications().size());
    }
  }

  @Test
  void secondStorageCannotAcquireLockAndCanOpenAfterClose() throws Exception {
    try (var store = new FileStateStore(config().storagePath())) {
      assertThrows(IOException.class, () -> new FileStateStore(config().storagePath()));
    }
    try (var store = new FileStateStore(config().storagePath())) {
      assertNotNull(store.load());
    }
  }

  @Test
  void corruptionFailsWithoutReset() throws Exception {
    Files.writeString(config().storagePath(), "version=1\nlink.count=garbage\n");
    assertThrows(
        IOException.class,
        () -> {
          try (var store = new FileStateStore(config().storagePath())) {
            store.load();
          }
        });
    assertTrue(Files.readString(config().storagePath()).contains("garbage"));
  }

  @Test
  void externalConfigurationLoaded() throws Exception {
    var file = writeConfig("PT24H", "PT1S", "2", "100");
    var c = AppConfig.load(file);
    assertEquals(Duration.ofDays(1), c.ttl());
    assertEquals(2, c.defaultLimit());
    assertTrue(c.storagePath().isAbsolute());
    assertEquals(temp.resolve("state.properties").toAbsolutePath().normalize(), c.storagePath());
  }

  @ParameterizedTest
  @ValueSource(strings = {"PT0S", "-PT1S", "broken", "P999999999D"})
  void invalidTtlRejected(String value) throws Exception {
    var file = writeConfig(value, "PT1S", "2", "100");
    assertThrows(IllegalArgumentException.class, () -> AppConfig.load(file));
  }

  @Test
  void invalidCleanupAndLimitConfigurationRejected() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> AppConfig.load(writeConfig("PT1H", "PT0S", "2", "100")));
    assertThrows(
        IllegalArgumentException.class,
        () -> AppConfig.load(writeConfig("PT1H", "PT1S", "101", "100")));
    assertThrows(IOException.class, () -> AppConfig.load(temp.resolve("missing")));
  }

  @Test
  void cliHandlesHelpErrorsEofAndLazyIdentity() {
    var s = service();
    var out = runCli(s, "help\nlist\nwat\ncreate bad\nuser\n");
    assertTrue(out.contains("create"));
    assertTrue(out.contains("Ошибка:"));
    assertNull(s.currentUser());
  }

  @Test
  void cliCreatesEditsOpensAndDeletes() {
    var s = new ShortenerService(config(), memory, clock, opened::add, () -> "fixed");
    var out =
        runCli(
            s,
            "create https://example.com 1\nhttps://short.local/fixed\nupdate fixed limit 3\nupdate fixed url https://example.org\nopen fixed\nnotifications\nlist\ndelete fixed\nexit\n");
    assertTrue(out.contains("UUID:"));
    assertEquals(
        List.of(URI.create("https://example.com"), URI.create("https://example.org")), opened);
    assertTrue(s.list().isEmpty());
    assertFalse(out.contains("Ошибка:"), out);
  }

  @Test
  void cliOpensEmittedShortUrlWithUppercaseScheme() {
    var cfg =
        new AppConfig(
            URI.create("HTTPS://short.local/"),
            config().ttl(),
            2,
            100,
            config().cleanupInterval(),
            config().storagePath());
    var s = new ShortenerService(cfg, memory, clock, opened::add);
    var l = s.create("https://example.com", null);
    var out = runCli(s, s.shortUrl(l.code()) + "\nexit\n");
    assertEquals(List.of(URI.create("https://example.com")), opened);
    assertFalse(out.contains("Ошибка:"), out);
  }

  @Test
  void cliUserSwitchAndMalformedCommandsRecover() {
    var s = service();
    var uuid = UUID.randomUUID();
    var out =
        runCli(
            s,
            "user use "
                + uuid
                + "\nuser\nuser use bad\nupdate x ttl PT1H\ncreate https://example.com -1\nuser new\nexit extra\nexit\n");
    assertTrue(out.contains(uuid.toString()));
    assertTrue(out.contains("Ошибка:"));
    assertNull(s.currentUser());
  }

  @Test
  void mainRestoresCliSessionAcrossRestart() throws Exception {
    Path file = writeConfig("PT24H", "PT1S", "2", "100");
    StringWriter first = new StringWriter();
    assertEquals(
        0,
        Main.run(
            new String[] {file.toString()},
            new StringReader("create https://example.com\nexit\n"),
            new PrintWriter(first, true),
            opened::add));
    String uuid =
        first
            .toString()
            .lines()
            .filter(line -> line.startsWith("UUID: "))
            .findFirst()
            .orElseThrow()
            .substring(6);
    StringWriter second = new StringWriter();
    assertEquals(
        0,
        Main.run(
            new String[] {file.toString()},
            new StringReader("user\nlist\n"),
            new PrintWriter(second, true),
            opened::add));
    assertTrue(second.toString().contains(uuid));
    assertTrue(second.toString().contains("https://example.com"));
  }

  @Test
  void mainRejectsMissingConfigurationAndTooManyArguments() {
    var out = new StringWriter();
    assertEquals(
        1,
        Main.run(
            new String[] {temp.resolve("missing").toString()},
            new StringReader(""),
            new PrintWriter(out, true),
            opened::add));
    assertEquals(
        1,
        Main.run(
            new String[] {"one", "two"},
            new StringReader(""),
            new PrintWriter(out, true),
            opened::add));
    assertTrue(out.toString().contains("Ошибка"));
  }

  @Test
  void anonymousSessionRemainsAnonymousAfterRestart() throws Exception {
    try (var store = new FileStateStore(config().storagePath())) {
      var s = new ShortenerService(config(), store, clock, opened::add);
      s.create("https://example.com", null);
      s.newUser();
    }
    try (var store = new FileStateStore(config().storagePath())) {
      var s = new ShortenerService(config(), store, clock, opened::add);
      assertNull(s.currentUser());
      assertTrue(s.list().isEmpty());
    }
  }

  @Test
  void deletedCodeIsNeverReusedAfterRestart() throws Exception {
    try (var store = new FileStateStore(config().storagePath())) {
      var s = new ShortenerService(config(), store, clock, opened::add, () -> "deleted");
      s.delete(s.create("https://example.com", null).code());
    }
    var codes = new ArrayDeque<>(List.of("deleted", "fresh"));
    try (var store = new FileStateStore(config().storagePath())) {
      var s = new ShortenerService(config(), store, clock, opened::add, codes::remove);
      assertEquals("fresh", s.create("https://example.com", null).code());
    }
  }

  @Test
  void cleanupFailureLeavesExpiredLinkForSuccessfulRetry() {
    var s = service();
    var l = s.create("https://example.com", null);
    clock.now = l.expiresAt();
    memory.fail = true;
    assertThrows(IllegalStateException.class, s::cleanup);
    memory.fail = false;
    s.cleanup();
    assertTrue(s.list().isEmpty());
    assertEquals(1, s.notifications().size());
  }

  @Test
  void realFileSaveFailurePreservesOldDiskAndMemory() throws Exception {
    Path statePath = config().storagePath();
    try (var store = new FileStateStore(statePath)) {
      var s = new ShortenerService(config(), store, clock, opened::add);
      var l = s.create("https://example.com", null);
      byte[] previous = Files.readAllBytes(statePath);
      Files.delete(statePath);
      Files.createDirectory(statePath);
      Files.writeString(statePath.resolve("blocker"), "keep");
      assertThrows(
          IllegalStateException.class, () -> s.updateUrl(l.code(), "https://changed.example"));
      assertEquals(URI.create("https://example.com"), s.list().get(0).url());
      Files.delete(statePath.resolve("blocker"));
      Files.delete(statePath);
      Files.write(statePath, previous);
      assertEquals(URI.create("https://example.com"), store.load().links().get(l.code()).url());
    }
  }

  @Test
  void schedulerRetriesAfterStorageFailureAndStopsOnClose() throws Exception {
    CountDownLatch failed = new CountDownLatch(1);
    CountDownLatch saved = new CountDownLatch(1);
    var store =
        new StateStore() {
          private State state = State.empty();
          private int saves;

          public State load() {
            return state;
          }

          public void save(State next) throws IOException {
            if (++saves == 2) throw new IOException("temporary disk problem");
            state = next.copy();
            if (saves > 2) saved.countDown();
          }

          public void close() {}
        };
    var s = new ShortenerService(config(), store, clock, opened::add);
    var link = s.create("https://example.com", null);
    clock.now = link.expiresAt();
    try (var scheduler =
        new CleanupScheduler(s, Duration.ofMillis(5), message -> failed.countDown())) {
      assertTrue(failed.await(5, TimeUnit.SECONDS));
      assertTrue(saved.await(5, TimeUnit.SECONDS));
    }
    assertTrue(s.list().isEmpty());
    assertEquals(1, s.notifications().size());
  }

  @Test
  void concurrentOpensCannotExceedLimit() throws Exception {
    var s = service();
    var l = s.create("https://example.com", 5L);
    var pool = Executors.newFixedThreadPool(8);
    AtomicInteger accepted = new AtomicInteger();
    try {
      var tasks = new ArrayList<Callable<Void>>();
      for (int i = 0; i < 30; i++)
        tasks.add(
            () -> {
              try {
                s.open(l.code());
                accepted.incrementAndGet();
              } catch (IllegalArgumentException expected) {
              }
              return null;
            });
      for (var result : pool.invokeAll(tasks)) result.get();
    } finally {
      pool.shutdownNow();
    }
    assertEquals(5, accepted.get());
    assertEquals(5, s.list().get(0).visits());
    assertEquals(1, s.notifications().size());
  }

  private String runCli(ShortenerService s, String input) {
    var output = new StringWriter();
    new Console(s, new StringReader(input), new PrintWriter(output, true)).run();
    return output.toString();
  }

  private Path writeConfig(String ttl, String interval, String limit, String max)
      throws IOException {
    var file = temp.resolve("app.properties");
    Files.writeString(
        file,
        "short.base-url=https://short.local/\nlink.ttl="
            + ttl
            + "\nlink.default-limit="
            + limit
            + "\nlink.max-limit="
            + max
            + "\ncleanup.interval="
            + interval
            + "\nstorage.path="
            + temp.resolve("state.properties").toString().replace('\\', '/')
            + "\n");
    return file;
  }

  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }

  static final class MemoryStore implements StateStore {
    State state = State.empty();
    boolean fail;

    public State load() {
      return state.copy();
    }

    public void save(State state) throws IOException {
      if (fail) throw new IOException("disk failure");
      this.state = state.copy();
    }

    public void close() {}
  }
}
