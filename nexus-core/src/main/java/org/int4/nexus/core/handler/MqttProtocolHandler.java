package org.int4.nexus.core.handler;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.int4.nexus.core.api.Action;
import org.int4.nexus.core.api.TerminalActionHandler;

public class MqttProtocolHandler implements TerminalActionHandler {
  public record Parameters(String id, @JsonProperty("max-delay") int maxDelay, @JsonProperty("result-topic") String resultTopic) {}

  private final Map<String, Mqtt5BlockingClient> clients = new HashMap<>();
  private final Semaphore semaphore = new Semaphore(0);
  private final Parameters parameters;

  public MqttProtocolHandler(Parameters parameters) {
    this.parameters = parameters;
  }

  @Override
  public void accept(Action action) {
    URI uri = action.uri();

    if(!uri.getScheme().equals("mqtt")) {
      throw new IllegalArgumentException("only mqtt scheme is supported: " + uri);
    }

    String clientIdentifier = uri.getHost() + ":" + (uri.getPort() == -1 ? 1883 : uri.getPort());
    Mqtt5BlockingClient client = clients.computeIfAbsent(clientIdentifier, k -> {
      Mqtt5BlockingClient c = Mqtt5Client.builder()
        .identifier("Nexus")
        .serverHost(uri.getHost())
        .serverPort(uri.getPort() == -1 ? 1883 : uri.getPort())
        .buildBlocking();

      c.connect();

      if(parameters.resultTopic != null) {
        c.subscribeWith().topicFilter(parameters.resultTopic).send();
        c.toAsync().publishes(MqttGlobalPublishFilter.ALL, msg -> semaphore.release());
      }

      return c;
    });

    if(!client.getState().isConnectedOrReconnect()) {
      client.connect();
    }

    semaphore.drainPermits();

    client.publishWith()
      .topic(uri.getPath().substring(1))
      .qos(MqttQos.AT_LEAST_ONCE)
      .payload(action.payload().getBytes(StandardCharsets.UTF_8))
      .retain(false)
      .messageExpiryInterval(1)
      .send();

    /*
     * Below the command is delayed (up to a maximum) or until a publish is received on
     * a specific topic. As the system is single threaded, any concurrent command will
     * wait until the MQTT command has fully completed.
     */

    try {
      semaphore.tryAcquire(parameters.maxDelay != 0 ? parameters.maxDelay : 250, TimeUnit.MILLISECONDS);
    }
    catch(InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
