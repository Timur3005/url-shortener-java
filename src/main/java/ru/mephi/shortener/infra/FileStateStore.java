package ru.mephi.shortener.infra;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import ru.mephi.shortener.core.*;

public final class FileStateStore implements StateStore {
  private final Path path;
  private final FileChannel lockChannel;
  private final FileLock lock;
  private boolean closed;

  public FileStateStore(Path file) throws IOException {
    Path absolute = file.toAbsolutePath().normalize();
    Files.createDirectories(absolute.getParent());
    // Resolve parent symlinks so aliases use the same stable lock file.
    path = absolute.getParent().toRealPath().resolve(absolute.getFileName());
    if (Files.isSymbolicLink(path))
      throw new IOException("Файл данных не должен быть символической ссылкой");
    lockChannel =
        FileChannel.open(
            path.resolveSibling(path.getFileName() + ".lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    FileLock acquired;
    try {
      acquired = lockChannel.tryLock();
      if (acquired == null) throw new IOException("Файл данных уже используется другим процессом");
    } catch (OverlappingFileLockException e) {
      lockChannel.close();
      throw new IOException("Файл данных уже используется другим экземпляром", e);
    } catch (IOException | RuntimeException e) {
      lockChannel.close();
      throw e;
    }
    lock = acquired;
  }

  @Override
  public synchronized State load() throws IOException {
    ensureOpen();
    if (!Files.exists(path)) return State.empty();
    Properties p = new Properties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      p.load(reader);
    } catch (IllegalArgumentException e) {
      throw new IOException("Повреждён файл данных: " + e.getMessage(), e);
    }
    try {
      Decoder d = new Decoder(p);
      if (!d.get("version").equals("1"))
        throw new IllegalArgumentException("Неподдерживаемая версия данных");
      String user = d.get("currentUser");
      State state = State.empty().withUser(user.isEmpty() ? null : uuid(user));
      int usedCount = d.count("used.count");
      for (int i = 0; i < usedCount; i++) {
        String code = d.get("used." + i);
        if (!ShortenerService.validCode(code) || !state.usedCodes().add(code))
          throw new IllegalArgumentException("Некорректный или повторный использованный код");
      }
      int linkCount = d.count("link.count");
      for (int i = 0; i < linkCount; i++) {
        String prefix = "link." + i + ".";
        String code = d.get(prefix + "code");
        UUID owner = uuid(d.get(prefix + "owner"));
        var url = Urls.parse(d.get(prefix + "url"));
        Instant created = Instant.parse(d.get(prefix + "created"));
        Instant expires = Instant.parse(d.get(prefix + "expires"));
        long limit = Long.parseLong(d.get(prefix + "limit"));
        long visits = Long.parseLong(d.get(prefix + "visits"));
        String notified = d.get(prefix + "limitNotified");
        if (!notified.equals("true") && !notified.equals("false"))
          throw new IllegalArgumentException("Некорректный флаг уведомления");
        boolean flag = Boolean.parseBoolean(notified);
        if (!ShortenerService.validCode(code)
            || !state.usedCodes().contains(code)
            || !expires.isAfter(created)
            || limit <= 0
            || limit > 1_000_000_000L
            || visits < 0
            || visits > 1_000_000_000L
            || flag != (visits >= limit))
          throw new IllegalArgumentException("Некорректная запись ссылки");
        if (state
                .links()
                .put(code, new Link(code, owner, url, created, expires, limit, visits, flag))
            != null) throw new IllegalArgumentException("Повторный код ссылки");
      }
      int notificationCount = d.count("notification.count");
      for (int i = 0; i < notificationCount; i++) {
        String prefix = "notification." + i + ".";
        UUID owner = uuid(d.get(prefix + "owner"));
        String code = d.get(prefix + "code");
        if (!state.usedCodes().contains(code))
          throw new IllegalArgumentException("Неизвестный код уведомления");
        state
            .notifications()
            .add(
                new Notification(
                    owner,
                    code,
                    Notification.Kind.valueOf(d.get(prefix + "kind")),
                    Instant.parse(d.get(prefix + "at"))));
      }
      if (!d.read.equals(p.stringPropertyNames()))
        throw new IllegalArgumentException("Неизвестные или несогласованные поля данных");
      return state;
    } catch (RuntimeException e) {
      throw new IOException("Повреждён файл данных: " + e.getMessage(), e);
    }
  }

  @Override
  public synchronized void save(State state) throws IOException {
    ensureOpen();
    Properties p = new Properties();
    p.setProperty("version", "1");
    p.setProperty("currentUser", state.currentUser() == null ? "" : state.currentUser().toString());
    p.setProperty("used.count", Integer.toString(state.usedCodes().size()));
    int i = 0;
    for (String code : state.usedCodes()) p.setProperty("used." + i++, code);
    p.setProperty("link.count", Integer.toString(state.links().size()));
    i = 0;
    for (Link link : state.links().values()) {
      String prefix = "link." + i++ + ".";
      p.setProperty(prefix + "code", link.code());
      p.setProperty(prefix + "owner", link.owner().toString());
      p.setProperty(prefix + "url", link.url().toString());
      p.setProperty(prefix + "created", link.createdAt().toString());
      p.setProperty(prefix + "expires", link.expiresAt().toString());
      p.setProperty(prefix + "limit", Long.toString(link.limit()));
      p.setProperty(prefix + "visits", Long.toString(link.visits()));
      p.setProperty(prefix + "limitNotified", Boolean.toString(link.limitNotified()));
    }
    p.setProperty("notification.count", Integer.toString(state.notifications().size()));
    i = 0;
    for (Notification notification : state.notifications()) {
      String prefix = "notification." + i++ + ".";
      p.setProperty(prefix + "owner", notification.owner().toString());
      p.setProperty(prefix + "code", notification.code());
      p.setProperty(prefix + "kind", notification.kind().name());
      p.setProperty(prefix + "at", notification.at().toString());
    }
    Path temporary = Files.createTempFile(path.getParent(), path.getFileName() + ".", ".tmp");
    try {
      try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
        p.store(writer, "URL shortener state v1");
      }
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      try {
        Files.move(
            temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        throw new IOException("Файловая система не поддерживает атомарное сохранение", e);
      }
    } finally {
      try {
        Files.deleteIfExists(temporary);
      } catch (IOException e) {
        System.err.println("Не удалось удалить временный файл: " + e.getMessage());
      }
    }
  }

  @Override
  public synchronized void close() throws IOException {
    if (!closed) {
      closed = true;
      try {
        lock.release();
      } finally {
        lockChannel.close();
      }
    }
  }

  private void ensureOpen() throws IOException {
    if (closed) throw new IOException("Хранилище закрыто");
  }

  public static UUID uuid(String value) {
    UUID uuid = UUID.fromString(value);
    if (!uuid.toString().equalsIgnoreCase(value))
      throw new IllegalArgumentException("Нужен полный UUID");
    return uuid;
  }

  private static final class Decoder {
    final Properties p;
    final Set<String> read = new HashSet<>();

    Decoder(Properties p) {
      this.p = p;
    }

    String get(String key) {
      read.add(key);
      String value = p.getProperty(key);
      if (value == null) throw new IllegalArgumentException("Отсутствует поле " + key);
      return value;
    }

    int count(String key) {
      int value = Integer.parseInt(get(key));
      if (value < 0 || value > 1_000_000)
        throw new IllegalArgumentException("Некорректное количество " + key);
      return value;
    }
  }
}
