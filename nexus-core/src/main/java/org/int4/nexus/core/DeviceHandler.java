package org.int4.nexus.core;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.int4.nexus.core.api.Action;
import org.int4.nexus.core.api.ActionHandler;

class DeviceHandler implements ActionHandler {
  private static final String IDENTIFIER_PART = "[_A-Za-z0-9]+";  // allows identifiers that start with a number
  private static final String IDENTIFIER = IDENTIFIER_PART + "(?:-" + IDENTIFIER_PART + ")*";
  private static final Pattern PATTERN = Pattern.compile("(" + IDENTIFIER + "):(" + IDENTIFIER + ")");

  private final List<Device> devices;

  public DeviceHandler(List<Device> devices) {
    this.devices = devices;
  }

  @Override
  public Optional<Action> handle(Action action) {
    Matcher matcher = PATTERN.matcher(action.uri().getSchemeSpecificPart());

    if(matcher.matches()) {
      String deviceId = matcher.group(1);
      String mappingName = matcher.group(2);

      for(Device device : devices) {
        if(device.id().equals(deviceId)) {
          return Optional.ofNullable(device.mappings().get(mappingName));
        }
      }
    }

    return Optional.empty();
  }
}
