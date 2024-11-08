package org.int4.nexus.core.api;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.net.URI;

public record Action(URI action, String method, String payload) {
  @JsonCreator  // TODO required?
  public Action(String action) {
    this(URI.create(action), null, null);
  }

  public URI uri() {
    return action;
  }
}