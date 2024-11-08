package org.int4.nexus.core.util;

import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class GlobalLoggerSetup {
  public static void setupLogging() {
    try {
      LogManager logManager = LogManager.getLogManager();
      Logger rootLogger = logManager.getLogger("");

      for(Handler handler : rootLogger.getHandlers()) {
        rootLogger.removeHandler(handler);
      }

      ConsoleHandler consoleHandler = new ConsoleHandler();

      consoleHandler.setFormatter(new MyLoggingFormatter());
      rootLogger.addHandler(consoleHandler);
      rootLogger.setLevel(Level.ALL); // Set the desired log level
    }
    catch(Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
