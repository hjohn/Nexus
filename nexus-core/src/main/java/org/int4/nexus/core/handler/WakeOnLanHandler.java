package org.int4.nexus.core.handler;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.int4.nexus.core.api.Action;
import org.int4.nexus.core.api.ActionHandler;

public class WakeOnLanHandler implements ActionHandler {
  private static final Pattern PATTERN = Pattern.compile("([0-9a-fA-F]{12}):([0-9\\.]+)");
  private static final Logger LOGGER = System.getLogger(WakeOnLanHandler.class.getName());

  @Override
  public Optional<Action> handle(Action action) {
    Matcher matcher = PATTERN.matcher(action.uri().getSchemeSpecificPart());

    if(!matcher.matches()) {
      throw new IllegalArgumentException("Bad wake on lan format: " + action);
    }

    String mac = matcher.group(1);
    String broadcastIp = matcher.group(2);

    try {
      wake(mac, broadcastIp);
    }
    catch(IOException e) {
      throw new IllegalStateException("Unable to send wake-on-lan: " + action, e);
    }

    return Optional.empty();
  }

  private static void wake(String mac, String broadcastIp) throws IOException {
    byte[] macAddress = HexFormat.of().parseHex(mac);
    byte[] bytes = new byte[6 + 16 * macAddress.length];

    // First 6 bytes must be 0xFF
    for(int i = 0; i < 6; i++) {
      bytes[i] = (byte)0xFF;
    }

    // Then repeat MAC address 16 times
    for(int i = 6; i < bytes.length; i += macAddress.length) {
      System.arraycopy(macAddress, 0, bytes, i, macAddress.length);
    }

    InetAddress address = InetAddress.getByName(broadcastIp);
    DatagramPacket packet = new DatagramPacket(bytes, bytes.length, address, 9);

    try(DatagramSocket socket = new DatagramSocket()) {
      socket.send(packet);
    }

    LOGGER.log(Level.INFO, "Waking mac " + mac + " by broadcasting on " + address);
  }
}
