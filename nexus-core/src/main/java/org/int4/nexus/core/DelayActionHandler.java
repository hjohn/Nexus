package org.int4.nexus.core;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import org.int4.nexus.core.api.Action;
import org.int4.nexus.core.api.TerminalActionHandler;

class DelayActionHandler implements TerminalActionHandler {

  @Override
  public void accept(Action action) {
    String[] parts = action.uri().getSchemeSpecificPart().split(":");

    int amount = Integer.parseInt(parts[0]);

    try {
      Thread.sleep(Duration.of(amount, convert(parts[1])));
    }
    catch(InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static ChronoUnit convert(String unit) {
    return switch(unit) {
      case "ms" -> ChronoUnit.MILLIS;
      case "s" -> ChronoUnit.SECONDS;
      case "ns" -> ChronoUnit.NANOS;
      case "m" -> ChronoUnit.MINUTES;
      default -> ChronoUnit.valueOf(unit);
    };
  }
}