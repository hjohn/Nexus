package org.int4.nexus.core.api;

import java.util.Optional;

public interface ActionHandler {
  Optional<Action> handle(Action action);
}
