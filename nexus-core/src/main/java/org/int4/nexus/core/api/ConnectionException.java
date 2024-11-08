package org.int4.nexus.core.api;

public class ConnectionException extends Exception {

  public ConnectionException(String message) {
    super(message);
  }

  public ConnectionException(String message, Throwable cause) {
    super(message, cause);
  }

}
