package org.int4.nexus.core.api;

import java.util.Optional;

public interface TerminalActionHandler extends ActionHandler {
  @Override
  default Optional<Action> handle(Action action) {
    accept(action);

    return Optional.empty();
  }

  void accept(Action action);
}
