package org.int4.nexus.core.connector;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.net.URI;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.int4.nexus.core.api.ConnectionException;
import org.int4.nexus.core.api.Connector;

public class InputDeviceConnector implements Connector {
  private static final Linker LINKER = Linker.nativeLinker();
  private static final Logger LOGGER = System.getLogger(InputDeviceConnector.class.getName());
  private static final long LONG_PRESS_NANOS = 1000 * 1000 * 1000L;

  private static final int O_RDWR = 0x02;
  private static final int EVIOCGRAB = 0x40044590;

  private static final int INPUT_EVENT_SIZE = 24; // Size of struct input_event in bytes (with padding)

//  private static final int EVENT_TIME_SEC_OFFSET = 0; // tv_sec
//  private static final int EVENT_TIME_MSEC_OFFSET = 8; // tv_usec
  private static final int EVENT_TYPE_OFFSET = 16; // type
  private static final int EVENT_CODE_OFFSET = 18; // code
  private static final int EVENT_VALUE_OFFSET = 20; // value

  private static final int PATH_MAX = 4096; // Max path length

  private static final Linker.Option CAPTURE_ERR_NO = Linker.Option.captureCallState("errno");
  private static final StructLayout CAPTURED_STATE_LAYOUT = Linker.Option.captureStateLayout();
  private static final VarHandle ERR_NO_HANDLE = CAPTURED_STATE_LAYOUT.varHandle(PathElement.groupElement("errno"));

  private static final MethodHandle OPEN = LINKER.downcallHandle(
    LINKER.defaultLookup().find("open").get(),
    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    CAPTURE_ERR_NO
  );

  private static final MethodHandle IOCTL = LINKER.downcallHandle(
    LINKER.defaultLookup().find("ioctl").get(),
    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    CAPTURE_ERR_NO
  );

  private static final MethodHandle CLOSE = LINKER.downcallHandle(
    LINKER.defaultLookup().find("close").get(),
    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
  );

  private static final MethodHandle READ = LINKER.downcallHandle(
    LINKER.defaultLookup().find("read").get(),
    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
  );

  private static final MethodHandle READLINK = LINKER.downcallHandle(
    LINKER.defaultLookup().find("readlink").get(),
    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
  );

  public static int grabDevice(String devicePath) throws ConnectionException {
    try(Arena arena = Arena.ofConfined()) {
      MemorySegment capturedState = arena.allocate(CAPTURED_STATE_LAYOUT);
      int fd = (int)OPEN.invoke(capturedState, arena.allocateFrom(devicePath), O_RDWR);

      if(fd < 0) {
        int errno = (int)ERR_NO_HANDLE.get(capturedState, 0L);

        throw new ConnectionException("Unable to open device: " + devicePath + "; errno: " + errno);
      }

      try {
        int result = (int)IOCTL.invoke(capturedState, fd, EVIOCGRAB, 1); // Try to grab the device (EVIOCGRAB)

        if(result != 0) {
          int errno = (int)ERR_NO_HANDLE.get(capturedState, 0L);

          throw new ConnectionException("Unable to grab device for exclusive use: " + devicePath + "; errno: " + errno);
        }

        LOGGER.log(Level.INFO, "Grabbed " + devicePath + " with handle " + fd);

        return fd;
      }
      catch(Throwable t) {
        closeDevice(fd);

        throw t;
      }
    }
    catch(ConnectionException e) {
      throw e;
    }
    catch(Throwable t) {
      throw new IllegalStateException("Unexpected exception while trying to grab device: " + devicePath, t);
    }
  }

  private static Optional<String> readLink(String linkPath) {
    try(Arena arena = Arena.ofConfined()) {
      MemorySegment buffer = arena.allocate(ValueLayout.JAVA_BYTE, PATH_MAX);
      int bytesRead = (int)READLINK.invoke(arena.allocateFrom(linkPath), buffer, PATH_MAX);

      // Check if the readlink call was successful
      if(bytesRead < 0) {
        return Optional.empty();
      }

      return Optional.of(buffer.getString(0));
    }
    catch(Throwable t) {
      t.printStackTrace();
      return Optional.empty();
    }
  }

  private static void closeDevice(int fd) {
    try {
      CLOSE.invoke(fd);
    }
    catch(Throwable t) {
      throw new IllegalStateException("Unexpected exception while trying to close file descriptor: " + fd, t);
    }
  }

  static class EventHandler {
    private final Thread longPressHandlerThread = new Thread(this::handleLongPresses);
    private final Thread eventHandlerThread = new Thread(this::readEvents);
    private final Set<Modifier> activeModifiers = EnumSet.noneOf(Modifier.class);  // enum set because it traverses in natural order
    private final int fd;
    private final Consumer<URI> sink;
    private final String id;

    private volatile boolean ended;

    private long lastPressTime;
    private int lastPressedCode;
    private boolean longPressSent;

    EventHandler(int fd, Consumer<URI> sink, String id, String threadName) {
      this.fd = fd;
      this.sink = sink;
      this.id = id;

      longPressHandlerThread.setName(threadName);
      eventHandlerThread.setName(threadName);
    }

    void start() {
      longPressHandlerThread.start();
      eventHandlerThread.start();
    }

    void handleLongPresses() {
      while(!ended) {
        try {
          Duration sleepDuration = Duration.ofMinutes(1);

          synchronized(this) {
            long now = System.nanoTime();
            long diff = now - lastPressTime;

            if(diff < LONG_PRESS_NANOS) {
              sleepDuration = Duration.ofNanos(LONG_PRESS_NANOS - diff);
            }
            else if(diff >= LONG_PRESS_NANOS && lastPressedCode != 0 && !longPressSent) {
              longPressSent = true;

              KeyCode keyCode = KeyCode.fromId(lastPressedCode);
              String modifiers = "";

              if(!activeModifiers.isEmpty()) {
                modifiers = ":" + activeModifiers.stream().map(Object::toString).map(String::toLowerCase).collect(Collectors.joining("+"));
              }

              sink.accept(URI.create("input-device:%s:KEY:%s:%s%s".formatted(
                id,
                keyCode == null ? "%04x".formatted(lastPressedCode) : keyCode.name().substring(4),
                "long-pressed",
                modifiers
              )));
            }
          }

          // Sleep outside synchronized block
          Thread.sleep(sleepDuration);  // just sleep, will be interrupted if a long press may need handling
        }
        catch(InterruptedException e) {
          // fall-through, check ended flag
        }
      }
    }

    void readEvents() {

      try(Arena arena = Arena.ofConfined()) {
        MemorySegment eventSegment = arena.allocate(INPUT_EVENT_SIZE);

        for(;;) {
          int bytesRead = (int)READ.invoke(fd, eventSegment, INPUT_EVENT_SIZE);

          if(bytesRead < 0) {
            LOGGER.log(Level.INFO, "EOF encountered on handle " + fd);
            break;
          }
          else if(bytesRead == 0) {
            continue;
          }

          short type = eventSegment.get(ValueLayout.JAVA_SHORT, EVENT_TYPE_OFFSET);
          short code = eventSegment.get(ValueLayout.JAVA_SHORT, EVENT_CODE_OFFSET);
          int value = eventSegment.get(ValueLayout.JAVA_INT, EVENT_VALUE_OFFSET);

          if(type == 1) {
            String state = switch(value) {
              case 0 -> "released";
              case 1 -> "pressed";
              case 2 -> "held";
              default -> "unknown";
            };

            String modifiers = "";
            KeyCode keyCode = KeyCode.fromId(code);

            if(keyCode != null && keyCode.modifier() != null) {
              if(value == 1) {
                activeModifiers.add(keyCode.modifier());
              }
              else if(value == 0) {
                activeModifiers.remove(keyCode.modifier());
              }
            }

            if(!activeModifiers.isEmpty()) {
              modifiers = ":" + activeModifiers.stream().map(Object::toString).map(String::toLowerCase).collect(Collectors.joining("+"));
            }

            if(value == 0 && System.nanoTime() - lastPressTime < LONG_PRESS_NANOS) {
              sink.accept(URI.create("input-device:%s:KEY:%s:%s%s".formatted(
                id,
                keyCode == null ? "%04x".formatted(code) : keyCode.name().substring(4),
                "short-pressed",
                modifiers
              )));
            }

            synchronized(this) {
              sink.accept(URI.create("input-device:%s:KEY:%s:%s%s".formatted(
                id,
                keyCode == null ? "%04x".formatted(code) : keyCode.name().substring(4),
                state,
                modifiers
              )));

              if(value == 1) {
                lastPressedCode = code;
                lastPressTime = System.nanoTime();
                longPressSent = false;

                longPressHandlerThread.interrupt();  // interrupt to trigger long press timer
              }
              else if(value == 0) {
                lastPressedCode = 0;
              }
            }
          }
          else if(type != 0) { // 0 are typically sync events, they don't have identifying information
            sink.accept(URI.create("input-device:%s:%04x:%04x:%08x".formatted(id, type, code, value)));
          }
        }
      }
      catch(Throwable t) {
        throw new IllegalStateException("Unexpected exception while reading events from handle: " + fd, t);
      }
      finally {
        ended = true;
        longPressHandlerThread.interrupt();

        closeDevice(fd);
      }
    }
  }

  @Override
  public void connect(Consumer<URI> sink, Map<String, Object> parameters) throws ConnectionException {
    String id = (String)parameters.get("id");
    String devicePath = (String)parameters.get("device");
    String threadName = readLink(devicePath).orElse(devicePath);
    int fd = grabDevice(devicePath);
    EventHandler handler = new EventHandler(fd, sink, id, threadName);

    handler.start();
  }
}
