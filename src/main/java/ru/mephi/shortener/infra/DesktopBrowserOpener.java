package ru.mephi.shortener.infra;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import ru.mephi.shortener.core.BrowserOpener;

public final class DesktopBrowserOpener implements BrowserOpener {
  @Override
  public void open(URI uri) throws IOException {
    if (!Desktop.isDesktopSupported())
      throw new IOException("Открытие браузера недоступно в среде без рабочего стола (headless)");
    Desktop desktop = Desktop.getDesktop();
    if (!desktop.isSupported(Desktop.Action.BROWSE))
      throw new IOException("Система не поддерживает открытие URL в браузере");
    desktop.browse(uri);
  }
}
