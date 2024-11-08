package org.int4.nexus.core.handler;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.int4.nexus.core.api.Action;
import org.int4.nexus.core.api.TerminalActionHandler;

public class HttpProtocolHandler implements TerminalActionHandler {
  private static final Logger LOGGER = System.getLogger(HttpProtocolHandler.class.getName());
  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  @Override
  public void accept(Action action) {
    URI uri = action.uri();

    // TODO scheme is irrelevant, should be replaced?
    if(!uri.getScheme().equals("http")) {
      throw new IllegalArgumentException("only http scheme is supported: " + uri);
    }

    String command = action.payload();
    String method = action.method();

    HttpRequest request = createRequest(uri, method, command);

    try {
      HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

      if(response.statusCode() < 200 || response.statusCode() >= 300) {
        LOGGER.log(Level.WARNING, "HTTP action " + uri + " was unsuccesful, status code: " + response.statusCode());
      }
    }
    catch(IOException e) {
      LOGGER.log(Level.ERROR, "HTTP action " + uri + " was unsuccesful: " + e + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")"));
    }
    catch(InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static HttpRequest createRequest(URI uri, String method, String payload) {
    if(method.equals("GET")) {
      return HttpRequest.newBuilder()
        .uri(uri)
        .GET()
        .build();
    }

    return HttpRequest.newBuilder()
      .uri(uri)
      .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
      .header("Content-Type", "text/plain")
      .build();
  }
}
