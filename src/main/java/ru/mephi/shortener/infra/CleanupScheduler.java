package ru.mephi.shortener.infra;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Consumer;
import ru.mephi.shortener.core.ShortenerService;

public final class CleanupScheduler implements AutoCloseable {
  private final ScheduledExecutorService executor;

  public CleanupScheduler(ShortenerService service, Duration interval, Consumer<String> errors) {
    executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread thread = new Thread(r, "link-cleanup");
              thread.setDaemon(true);
              return thread;
            });
    executor.scheduleWithFixedDelay(
        () -> {
          try {
            service.cleanup();
          } catch (RuntimeException e) {
            errors.accept("Ошибка фоновой очистки: " + e.getMessage());
          }
        },
        interval.toNanos(),
        interval.toNanos(),
        TimeUnit.NANOSECONDS);
  }

  @Override
  public void close() {
    executor.shutdown();
    boolean interrupted = false;
    try {
      while (!executor.isTerminated()) {
        try {
          if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
          interrupted = true;
          executor.shutdownNow();
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
}
