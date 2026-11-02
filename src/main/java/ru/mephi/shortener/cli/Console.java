package ru.mephi.shortener.cli;

import java.io.*;
import ru.mephi.shortener.core.*;
import ru.mephi.shortener.infra.FileStateStore;

public final class Console {
  private final ShortenerService service;
  private final BufferedReader input;
  private final PrintWriter output;

  public Console(ShortenerService service, Reader input, PrintWriter output) {
    this.service = service;
    this.input = new BufferedReader(input);
    this.output = output;
  }

  public void run() {
    output.println("Сервис коротких ссылок. help — команды; exit — выход.");
    output.flush();
    try {
      String line;
      while ((line = input.readLine()) != null) {
        if (line.isBlank()) continue;
        String[] args = line.trim().split("\\s+");
        try {
          if (!execute(args)) return;
        } catch (IllegalArgumentException | IllegalStateException e) {
          output.println("Ошибка: " + e.getMessage());
        }
        output.flush();
      }
    } catch (IOException e) {
      throw new IllegalStateException("Не удалось прочитать команду: " + e.getMessage(), e);
    }
  }

  private boolean execute(String[] a) {
    switch (a[0]) {
      case "help" -> {
        arity(a, 1);
        output.println(
            """
            create <url> [limit] — создать ссылку; UUID появится при первом создании
            open <code-or-short-url> — открыть ссылку в браузере
            <полная-короткая-ссылка> — также открыть ссылку
            list — ссылки текущего UUID
            update <code-or-short-url> limit <number> — изменить лимит
            update <code-or-short-url> url <url> — изменить адрес
            delete <code-or-short-url> — удалить свою ссылку
            notifications — журнал уведомлений текущего UUID (сохраняется после чтения)
            user — показать UUID; user new — новая анонимная сессия
            user use <uuid> — переключиться на UUID владельца
            help — справка; exit — выход
            URL с пробелами нужно percent-encode; TTL меняется только в конфигурации.
            """);
      }
      case "create" -> {
        if (a.length != 2 && a.length != 3) throw usage();
        boolean anonymous = service.currentUser() == null;
        Link l = service.create(a[1], a.length == 3 ? number(a[2]) : null);
        if (anonymous) output.println("UUID: " + l.owner());
        output.println(
            "Короткая ссылка: "
                + service.shortUrl(l.code())
                + " | лимит: "
                + l.limit()
                + " | действует до: "
                + l.expiresAt());
      }
      case "open" -> {
        arity(a, 2);
        open(a[1]);
      }
      case "list" -> {
        arity(a, 1);
        var links = service.list();
        if (links.isEmpty()) output.println("Нет ссылок текущего UUID.");
        for (Link l : links)
          output.println(
              service.shortUrl(l.code())
                  + " -> "
                  + l.url()
                  + " | переходы: "
                  + l.visits()
                  + "/"
                  + l.limit()
                  + " | действует до: "
                  + l.expiresAt());
      }
      case "update" -> {
        arity(a, 4);
        switch (a[2]) {
          case "limit" -> service.updateLimit(a[1], number(a[3]));
          case "url" -> service.updateUrl(a[1], a[3]);
          default -> throw usage();
        }
        output.println("Ссылка обновлена.");
      }
      case "delete" -> {
        arity(a, 2);
        service.delete(a[1]);
        output.println("Ссылка удалена.");
      }
      case "notifications" -> {
        arity(a, 1);
        var notifications = service.notifications();
        if (notifications.isEmpty()) output.println("Нет уведомлений текущего UUID.");
        for (Notification n : notifications)
          output.println(
              n.at()
                  + " | "
                  + service.shortUrl(n.code())
                  + " | "
                  + (n.kind() == Notification.Kind.EXPIRED
                      ? "Срок действия истёк, ссылка удалена."
                      : "Лимит переходов исчерпан."));
      }
      case "user" -> {
        if (a.length == 1)
          output.println(
              service.currentUser() == null
                  ? "Анонимная сессия. UUID появится при первом create."
                  : "UUID: " + service.currentUser());
        else if (a.length == 2 && a[1].equals("new")) {
          service.newUser();
          output.println("Начата новая анонимная сессия.");
        } else if (a.length == 3 && a[1].equals("use")) {
          service.useUser(FileStateStore.uuid(a[2]));
          output.println("UUID: " + service.currentUser());
        } else throw usage();
      }
      case "exit" -> {
        arity(a, 1);
        output.flush();
        return false;
      }
      default -> {
        if (a.length == 1
            && (a[0].regionMatches(true, 0, "http://", 0, 7)
                || a[0].regionMatches(true, 0, "https://", 0, 8))) open(a[0]);
        else throw new IllegalArgumentException("Неизвестная команда. Введите help.");
      }
    }
    return true;
  }

  private void open(String reference) {
    Link l = service.open(reference);
    output.println("Запрос на открытие отправлен браузеру: " + l.url());
    if (l.limitNotified())
      output.println("Лимит переходов исчерпан; владельцу сохранено уведомление.");
  }

  private static long number(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Лимит должен быть целым числом");
    }
  }

  private static void arity(String[] args, int count) {
    if (args.length != count) throw usage();
  }

  private static IllegalArgumentException usage() {
    return new IllegalArgumentException("Некорректные аргументы. Введите help.");
  }
}
