package ru.mephi.shortener.core;

import java.io.IOException;

public interface StateStore extends AutoCloseable {
  State load() throws IOException;

  void save(State state) throws IOException;

  @Override
  void close() throws IOException;
}
