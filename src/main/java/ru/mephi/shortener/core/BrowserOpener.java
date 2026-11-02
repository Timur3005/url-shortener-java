package ru.mephi.shortener.core;

import java.io.IOException;
import java.net.URI;

@FunctionalInterface
public interface BrowserOpener {
  void open(URI uri) throws IOException;
}
