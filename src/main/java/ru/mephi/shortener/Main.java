package ru.mephi.shortener;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import ru.mephi.shortener.cli.Console;
import ru.mephi.shortener.core.*;
import ru.mephi.shortener.infra.*;

public final class Main {
  private Main() {}

  public static void main(String[] args) {
    int code =
        run(
            args,
            new InputStreamReader(System.in, StandardCharsets.UTF_8),
            new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true),
            new DesktopBrowserOpener());
    if (code != 0) System.exit(code);
  }

  public static int run(String[] args, Reader input, PrintWriter output, BrowserOpener browser) {
    if (args.length > 1) {
      output.println("Ошибка: запуск java -jar url-shortener.jar [config-path]");
      output.flush();
      return 1;
    }
    try {
      AppConfig config =
          AppConfig.load(Path.of(args.length == 0 ? "config/application.properties" : args[0]));
      try (FileStateStore store = new FileStateStore(config.storagePath())) {
        ShortenerService service = new ShortenerService(config, store, Clock.systemUTC(), browser);
        try (CleanupScheduler cleanup =
            new CleanupScheduler(
                service,
                config.cleanupInterval(),
                message -> {
                  output.println(message);
                  output.flush();
                })) {
          new Console(service, input, output).run();
        }
      }
      return 0;
    } catch (IOException | RuntimeException e) {
      output.println("Ошибка запуска/завершения: " + e.getMessage());
      output.flush();
      return 1;
    }
  }
}
