package org.int4.nexus.core.api;

import java.net.URI;
import java.util.Map;
import java.util.function.Consumer;

public interface Connector {
  void connect(Consumer<URI> sink, Map<String, Object> parameters) throws ConnectionException;
}
