package org.int4.nexus.core.util;

import java.io.PrintWriter;
import java.io.StringWriter;

public class Throwables {

  public static String formatAsOneLine(Throwable throwable) {
    StringBuilder builder = new StringBuilder();
    Throwable current = throwable;

    for(;;) {
      builder.append(current.getClass().getName());

      if(current.getMessage() != null) {
        builder.append(" [");
        builder.append(current.getMessage());
        builder.append("]");
      }

      builder.append(" @ ");

      StackTraceElement[] stackTrace = current.getStackTrace();

      if(stackTrace.length > 0) {
        builder.append(stackTrace[0]);
      }
      else {
        builder.append("<unknown element>");
      }

      current = current.getCause();

      if(current == null) {
        break;
      }

      builder.append(" --> ");
    }

    return builder.toString();
  }

  public static String toString(Throwable throwable) {
    StringWriter stringWriter = new StringWriter();

    throwable.printStackTrace(new PrintWriter(stringWriter));

    return stringWriter.toString();
  }

  public static boolean isProgrammingError(Throwable throwable) {
    if(!(throwable instanceof RuntimeException)) {
      return false;
    }

    if(!throwable.getClass().getName().startsWith("java.lang.")) {
      return false;
    }

    return true;
  }

  public interface ExceptionalRunnable {
    void run() throws Exception;
  }
}
