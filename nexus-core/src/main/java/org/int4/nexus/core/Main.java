package org.int4.nexus.core;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.int4.nexus.core.api.Action;
import org.int4.nexus.core.api.ActionHandler;
import org.int4.nexus.core.api.Connector;
import org.int4.nexus.core.util.GlobalLoggerSetup;
import org.int4.nexus.core.util.Throwables;

// TODO Support YAML reloading so software may not need to be altered to reconfigure

public class Main {
  private static final YAMLFactory YAML_FACTORY = new YAMLFactory();
  private static final ObjectMapper OBJECT_MAPPER;
  private static final Logger LOGGER = System.getLogger(Main.class.getName());

  static {
    OBJECT_MAPPER = new ObjectMapper(YAML_FACTORY)
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    OBJECT_MAPPER
      .configOverride(Map.class)
      .setSetterInfo(JsonSetter.Value.forValueNulls(Nulls.AS_EMPTY));
  }

  public static void main(String[] args) throws StreamReadException, DatabindException, IOException {
    GlobalLoggerSetup.setupLogging();

    Configuration config = OBJECT_MAPPER.readValue(new File("nexus.yaml"), Configuration.class);

    LOGGER.log(Level.DEBUG, Util.makePretty(config.toString()));

    InputHandler handler = new InputHandler(config);

    for(Map.Entry<String, Template> entry : config.templates().entrySet()) {
      handler.register(entry.getKey(), new TemplateHandler(entry.getValue()));
    }

    handler.register("device", new DeviceHandler(config.devices()));
    handler.register("delay", new DelayActionHandler());

    CommandProcessor commandProcessor = new CommandProcessor(handler::process);

    new Thread(commandProcessor).start();

    Consumer<URI> sink = uri -> {
      LOGGER.log(Level.INFO, "Received input event: " + uri);

      try {
        if(!commandProcessor.queue.offer(uri, 50, TimeUnit.MILLISECONDS)) {
          LOGGER.log(Level.DEBUG, "Not ready, ignoring command: " + uri);
        }
      }
      catch(InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };

    for(Entry<String, List<Map<String, Object>>> entry : config.connectors().entrySet()) {
      try {
        MethodHandle constructor = MethodHandles.publicLookup().findConstructor(Class.forName(entry.getKey()), MethodType.methodType(void.class));

        Connector connector = (Connector)constructor.invoke();

        for(Map<String, Object> parameters : entry.getValue()) {
          connector.connect(sink, parameters);
        }
      }
      catch(Throwable e) {
        LOGGER.log(Level.WARNING, "Unable to construct connector: " + entry.getKey() + " because: " + Throwables.formatAsOneLine(e));
      }
    }

    for(Entry<String, List<Map<String, Object>>> entry : config.handlers().entrySet()) {
      try {
        Class<?> parametersClass = findClass(entry.getKey() + "$Parameters").orElse(null);
        MethodType methodType = parametersClass == null ? MethodType.methodType(void.class) : MethodType.methodType(void.class, parametersClass);
        MethodHandle constructor = MethodHandles.publicLookup().findConstructor(Class.forName(entry.getKey()), methodType);

        for(Map<String, Object> parameters : entry.getValue()) {
          ActionHandler actionHandler = parametersClass == null
            ? (ActionHandler)constructor.invoke()
            : (ActionHandler)constructor.invoke(OBJECT_MAPPER.convertValue(parameters, parametersClass));

          handler.register((String)parameters.get("id"), actionHandler);
        }
      }
      catch(Throwable e) {
        LOGGER.log(Level.WARNING, "Unable to construct handler: " + entry.getKey() + " because: " + Throwables.formatAsOneLine(e));
      }
    }
  }

  static Optional<Class<?>> findClass(String name) {
    try {
      return Optional.of(Class.forName(name));
    }
    catch(ClassNotFoundException e) {
      return Optional.empty();
    }
  }

  static class CommandProcessor implements Runnable {
    public final BlockingQueue<URI> queue = new ArrayBlockingQueue<>(1);

    private final Consumer<URI> sink;

    public CommandProcessor(Consumer<URI> sink) {
      this.sink = sink;
    }

    @Override
    public void run() {
      for(;;) {
        try {
          sink.accept(queue.take());
        }
        catch(InterruptedException e) {
          e.printStackTrace();
        }
      }
    }
  }

  static class InputHandler {
    private final Configuration config;
    private final Map<URI, Action> deviceMappings = new HashMap<>();
    private final Map<String, Device> participantMappings = new HashMap<>();
    private final List<Device> activeDevices = new ArrayList<>();  // List for predictable order

    private Activity currentActivity;

    public InputHandler(Configuration config) {
      this.config = config;

      for(Device device : config.devices()) {
        for(Map.Entry<String, Action> mapping : device.mappings().entrySet()) {
          deviceMappings.put(URI.create(device.id() + ":" + mapping.getKey()), mapping.getValue());
        }

        participantMappings.put(device.id(), device);
      }
    }

    private final Map<String, ActionHandler> actionHandlers = new HashMap<>();

    void register(String scheme, ActionHandler handler) {
      actionHandlers.put(scheme, handler);
    }

    void process(URI input) {
      // First convert to mapping:
      Action action = config.inputMappings().get(input);

      if(action == null) {
        return;
      }

      if(action.uri().getScheme().equals("activity")) {
        if(action.uri().getSchemeSpecificPart().equals("off")) {
          setParticipants(List.of());
        }
        else if(action.uri().getSchemeSpecificPart().startsWith("switch:")) {

          // Find and possibly activate new activity
          for(Activity activity : config.activities()) {
            if(activity.id().equals(action.uri().getSchemeSpecificPart().substring(7))) {
              setParticipants(activity.participants());

              LOGGER.log(Level.INFO, "Setting up activity " + activity.id());

              doSteps(activity.setup());
              currentActivity = activity;
              break;
            }
          }
        }
      }
      else {
        executeAction(action);
      }
    }

    void setParticipants(List<Device> participants) {
      for(Device participant : participants) {
        if(!activeDevices.contains(participant)) {
          LOGGER.log(Level.INFO, "Activating " + participant.id());

          doSteps(participant.activation());
        }
      }

      List<Device> superfluousDevices = new ArrayList<>(activeDevices);

      superfluousDevices.removeAll(participants);

      activeDevices.clear();
      activeDevices.addAll(participants);

      for(Device device : superfluousDevices) {
        LOGGER.log(Level.INFO, "Deactivating " + device.id());

        doSteps(device.deactivation());
      }
    }

    // Converts the cmd scheme recursively until a non-cmd scheme is found:

    void executeAction(Action action) {
      Action a = action;

      for(;;) {
        LOGGER.log(Level.INFO, "  -> " + a.uri());

        String scheme = a.uri().getScheme();

        if(scheme.equals("cmd") && currentActivity != null) {
          Action cmd = currentActivity.mappings().get(a.uri().getSchemeSpecificPart());

          if(cmd != null) {
            a = cmd;
            continue;
          }
        }

        ActionHandler actionHandler = actionHandlers.get(scheme);

        if(actionHandler != null) {
          a = actionHandler.handle(a).orElse(null);

          if(a == null) {
            break;
          }

          continue;
        }

        LOGGER.log(Level.INFO, "No handler for " + action);

        break;
      }
    }

    void doSteps(List<Action> steps) {
      for(Action action : steps) {
        executeAction(action);
      }
    }
  }
}
