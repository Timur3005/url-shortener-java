package ru.mephi.shortener.core;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

public final class Urls {
  private static final Pattern ENCODED_CONTROL =
      Pattern.compile("%(?:0[0-9a-f]|1[0-9a-f]|7f)", Pattern.CASE_INSENSITIVE);

  private Urls() {}

  public static URI parse(String value) {
    if (value == null
        || value.chars().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c))
        || ENCODED_CONTROL.matcher(value).find()) {
      throw new IllegalArgumentException("URL содержит пробелы или управляющие символы");
    }
    try {
      URI uri = URI.create(value);
      String scheme = uri.getScheme();
      if (scheme == null
          || !(scheme.toLowerCase(Locale.ROOT).equals("http")
              || scheme.toLowerCase(Locale.ROOT).equals("https"))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getPort() > 65535) {
        throw new IllegalArgumentException(
            "Нужен абсолютный http/https URL с host и без логина/пароля");
      }
      return uri;
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Некорректный URL: " + e.getMessage(), e);
    }
  }
}
