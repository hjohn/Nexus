package org.int4.nexus.core.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

public class MyLoggingFormatter extends Formatter {
  private static final int THREAD_NAME_LENGTH_LIMIT = 30;
  private static final int LOGGER_NAME_LENGTH_LIMIT = 30;
  private static final String PIPE = "\u2502";
  private static final String ELLIPSIS = "\u2026";
  private static final Map<Level, String> INDICATOR_BY_LEVEL = Map.of(
    Level.SEVERE, "\u203C",
    Level.WARNING, "!",
    Level.INFO, " ",
    Level.CONFIG, "\u00a9",
    Level.FINE, "-",
    Level.FINER, "=",
    Level.FINEST, "\u2261"
  );

  private final String emptyLine;
  private final DateFormat dateFormat;

  public MyLoggingFormatter() {
    this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    this.emptyLine = ("%" + this.dateFormat.format(new Date()).length() + "s" + PIPE + "%1s" + PIPE + "%" + THREAD_NAME_LENGTH_LIMIT + "s" + PIPE + "%" + LOGGER_NAME_LENGTH_LIMIT + "s" + PIPE).formatted("", "", "", "");
  }

  @Override
  public String format(LogRecord logRecord) {
    StringBuilder builder = new StringBuilder();

    builder
      .append(dateFormat.format(new Date(logRecord.getMillis())))
      .append(PIPE)
      .append(INDICATOR_BY_LEVEL.get(logRecord.getLevel()))
      .append(PIPE);

    appendPaddedAndLimitedClassName(builder, logRecord.getLoggerName(), LOGGER_NAME_LENGTH_LIMIT);

    builder.append(PIPE);

    appendPaddedAndLimited(builder, Thread.currentThread().getName(), THREAD_NAME_LENGTH_LIMIT);

    builder.append(PIPE);

    appendMessage(logRecord, builder);

    builder.append("\n");

    if(logRecord.getThrown() != null) {
      StringWriter sw = new StringWriter();

      try(PrintWriter pw = new PrintWriter(sw)) {
        logRecord.getThrown().printStackTrace(pw);
      }

      builder.append(sw.toString()).append("\n");
    }

    return builder.toString();
  }

  private static void appendPaddedAndLimited(StringBuilder builder, String text, int maxLength) {
    int len = text.length();

    if(len <= maxLength) {
      builder.append(text);

      for(int i = len; i < maxLength; i++) {
        builder.append(" ");
      }
    }
    else {
      builder.append(text, 0, maxLength - 1);
      builder.append(ELLIPSIS);
    }
  }

  private static void appendPaddedAndLimitedClassName(StringBuilder builder, String text, int maxLength) {
    int start = 0;
    int lengthLeft = maxLength;
    int lengthNeeded = text.length();

    while(lengthNeeded > lengthLeft) {
      char c = text.charAt(start);

      if(!Character.isLowerCase(c)) {
        break;
      }

      builder.append(c);
      builder.append('.');

      start = text.indexOf('.', start) + 1;
      lengthLeft -= 2;
      lengthNeeded = text.length() - start;
    }

    if(lengthNeeded <= lengthLeft) {
      builder.append(text, start, start + lengthNeeded);

      for(int i = lengthNeeded; i < lengthLeft; i++) {
        builder.append(" ");
      }
    }
    else {
      builder.append(text, start, start + lengthLeft - 1);
      builder.append(ELLIPSIS);
    }
  }

  private void appendMessage(LogRecord logRecord, StringBuilder builder) {
    String message = logRecord.getMessage();

    int lineBreak = message.indexOf('\n');

    if(lineBreak >= 0) {
      int start = 0;
      builder.append(message.substring(start, lineBreak));

      do {
        builder.append("\n");
        builder.append(emptyLine);

        start = lineBreak + 1;
        lineBreak = message.indexOf('\n', start);

        builder.append(message.substring(start, lineBreak == -1 ? message.length() : lineBreak));
      } while(lineBreak != -1);
    }
    else {
      builder.append(message);
    }
  }
}
