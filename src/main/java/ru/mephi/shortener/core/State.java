package ru.mephi.shortener.core;

import java.util.*;

public record State(
    Map<String, Link> links,
    Set<String> usedCodes,
    List<Notification> notifications,
    UUID currentUser) {
  public static State empty() {
    return new State(new LinkedHashMap<>(), new LinkedHashSet<>(), new ArrayList<>(), null);
  }

  public State copy() {
    return new State(
        new LinkedHashMap<>(links),
        new LinkedHashSet<>(usedCodes),
        new ArrayList<>(notifications),
        currentUser);
  }

  public State withUser(UUID user) {
    return new State(links, usedCodes, notifications, user);
  }
}
