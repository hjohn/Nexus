package org.int4.nexus.core.connector;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Consumer;

import org.int4.nexus.core.api.ConnectionException;
import org.int4.nexus.core.api.Connector;

public class HciDumpConnector implements Connector {
  private static final long LONG_PRESS_NANOS = 1000 * 1000 * 1000L;
  private static final long KEY_REPEAT_DELAY_NANOS = 500 * 1000 * 1000L;
  private static final long KEY_REPEAT_INTERVAL_NANOS = 50 * 1000 * 1000L;
  private static final Logger LOGGER = System.getLogger(HciDumpConnector.class.getName());

  // > 02 01 2E 0B 00 07 00 04 00 1B 1F 00 41 00 00 00

  // > 02 02 2E 0E 00 0A 00 04 00 1B 43 00 00 00 1E 00 00 00 00

  // > 02 01 2E 08 00 04 00 04 00 1B 40 00 07
  // > 02 01 2E 0B 00 07 00 04 00 1B 1F 00 00 00 00 00
  // > 02 01 2E 0F 00 0B 00 04 00 1B 23 00 00 00 1E 00 00 00 00 00
  // > 02 01 2E 0F 00 0B 00 04 00 1B 23 00 00 00 00 00 00 00 00 00
  // > 02 01 2E 0F 00 0B 00 04 00 1B 23 00 00 00 1F 00 00 00 00 00
  // > 02 01 2E 0F 00 0B 00 04 00 1B 23 00 00 00 00 00 00 00 00 00
  // > 02 01 2E 0F 00 0B 00 04 00 1B 23 00 00 00 20 00 00 00 00 00
  // > 02 01 2E 0F 00 0B 00 04 00 1B 23 00 00 00 00 00 00 00 00 00



  //  02 01 2E 0B 00 07 00 04 00 1B 1F 00 41 00 00 00
  //     ^---^ ^---^ ^---^       ^^ ^---^ ^---------^
  //       |     |     |          |   |        |
  //       |     |     |          |   |        +-- payload
  //       |     |     |          |   |
  //       |     |     |          |   +-- handle (some sort of class or discriminator)
  //       |     |     |          |
  //       |     |     |          +-- value notification
  //       |     |     |
  //       |     |     +-- another length (7)
  //       |     |
  //       |     +-- length (11)
  //       |
  //       +-- 12 bits indicating connection number
  //   |
  //   +-- opcode (ACL Data message)

  static class EventHandler {
    private final Thread longPressHandlerThread = new Thread(this::handleLongPresses);
    private final Thread eventHandlerThread = new Thread(this::readEvents);
    private final Consumer<URI> sink;
    private final Process process;

    private volatile boolean ended;

    private long lastPressTime;
    private String lastPressedCode;
    private boolean longPressSent;
    private long nextRepeatNanos;

    EventHandler(Consumer<URI> sink, Process process) {
      this.sink = sink;
      this.process = process;
    }

    void start() {
      longPressHandlerThread.start();
      eventHandlerThread.start();
    }

    void readEvents() {
      ByteBuffer payload = ByteBuffer.allocate(256);

      payload.order(ByteOrder.LITTLE_ENDIAN);

      try(LineNumberReader reader = new LineNumberReader(new InputStreamReader(process.getInputStream()))) {
        for(;;) {
          String line = reader.readLine();

          if(line == null) {
            LOGGER.log(Level.INFO, "EOF encountered");
            break;
          }

          if(!line.startsWith(">")) {
            continue;
          }

          line = line.substring(1).replace(" ", "");

          for(int i = 0; i < line.length(); i += 2) {
            int hexByte = HexFormat.fromHexDigits(line, i, i + 2);

            payload.put((byte)hexByte);
          }

          payload.flip();

          processKey(payload);

          payload.clear();
        }
      }
      catch(IOException e) {
        throw new IllegalStateException("Unexpected exception while reading from hcidump", e);
      }
      finally {
        ended = true;
        longPressHandlerThread.interrupt();

        process.destroy();
      }
    }

    void processKey(ByteBuffer payload) {
      byte pdu = payload.get();

      if(pdu == 0x02) {  // data payload
        short connectionHandleAndFlags = payload.getShort();
        int flags = (connectionHandleAndFlags & 0xf000) >> 12;

        if(flags == 0x02) {
          short length = payload.getShort();
          short dataLength = payload.getShort();

          if(length == dataLength + 4) {
            short unknown = payload.getShort();

            if(unknown == 0x0004) {
              byte type = payload.get();

              if(type == 0x1b) {  // value notification
                short handle = payload.getShort();

                // TODO add short-pressed

                // filter here TODO make configurable?
                if(handle == 0x001f || handle == 0x0023) {
                  byte[] data = new byte[dataLength - 3];

                  payload.get(data, 0, dataLength - 3);

                  boolean allZeroes = isAllZeroes(data);

                  if(allZeroes) {
                    clearKeyPress();
                  }
                  else {
                    String state = "pressed";
                    String keyCode = HexFormat.of().toHexDigits(handle) + ":" + HexFormat.of().formatHex(data);

                    synchronized(this) {
                      sink.accept(URI.create("hci:%s:%s".formatted(
                        keyCode,
                        state
                      )));

                      triggerRepeatAndLongPressHandler(keyCode);
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    private static boolean isAllZeroes(byte[] data) {
      for(int i = 0; i < data.length; i++) {
        if(data[i] != 0) {
          return false;
        }
      }

      return true;
    }

    void triggerRepeatAndLongPressHandler(String keyCode) {
      synchronized(this) {
        this.longPressSent = false;
        this.lastPressedCode = keyCode;
        this.nextRepeatNanos = KEY_REPEAT_DELAY_NANOS;
        this.lastPressTime = System.nanoTime();

        longPressHandlerThread.interrupt();
      }
    }

    void clearKeyPress() {
      synchronized(this) {
        this.longPressSent = false;
        this.lastPressedCode = null;
        this.nextRepeatNanos = KEY_REPEAT_DELAY_NANOS;
        this.lastPressTime = System.nanoTime();
      }
    }

    void handleLongPresses() {
      while(!ended) {
        try {
          Duration sleepDuration = Duration.ofMinutes(1);

          synchronized(this) {
            if(lastPressedCode != null) {
              long now = System.nanoTime();
              long diff = now - lastPressTime;

              sleepDuration = Duration.ofMillis(10);

              if(diff >= nextRepeatNanos) {
                nextRepeatNanos += KEY_REPEAT_INTERVAL_NANOS;

                sink.accept(URI.create("hci:%s:%s".formatted(
                  lastPressedCode,
                  "held"
                )));
              }

              if(diff >= LONG_PRESS_NANOS && !longPressSent) {
                longPressSent = true;

                sink.accept(URI.create("hci:%s:%s".formatted(
                  lastPressedCode,
                  "long-pressed"
                )));
              }
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
  }

  @Override
  public void connect(Consumer<URI> sink, Map<String, Object> parameters) throws ConnectionException {
    try {
      Process process = new ProcessBuilder("hcidump", "-R").start();
      EventHandler eventHandler = new EventHandler(sink, process);

      eventHandler.start();
    }
    catch(IOException e) {
      throw new ConnectionException("IO error starting hcidump", e);
    }
  }
}
