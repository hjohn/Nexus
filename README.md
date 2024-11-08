[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.int4.db/parent/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.int4.nexus/parent)
[![Build Status](https://github.com/hjohn/Nexus/actions/workflows/maven.yml/badge.svg?branch=master)](https://github.com/hjohn/Nexus/actions)
[![Coverage](https://codecov.io/gh/hjohn/Nexus/branch/master/graph/badge.svg?token=QCNNRFYF98)](https://codecov.io/gh/hjohn/Nexus)
[![License](https://img.shields.io/badge/License-BSD_2--Clause-orange.svg)](https://opensource.org/licenses/BSD-2-Clause)
[![javadoc](https://javadoc.io/badge2/org.int4.nexus/parent/javadoc.svg)](https://javadoc.io/doc/org.int4.nexus/parent)

# Nexus

An activity based controller for your Home Cinema needs. The activities and devices are configured with a YAML (JSON) configuration file. It allows for various input devices, like `/dev/input/event` devices, `hcidump`, mappings of input values to commands, simple templates and various forms of output (HTTP, MQTT). The system tracks what devices are active, and when switching activities will activate and de-activate only what is needed.

## Configuration

### Actions and Schemes

The configuration file is in essence a large mapping definition, where one action leads to another. Actions are URI's distinguished with a scheme. Actions can be associated with a device, a template, an activity or a supported output type.

As actions are URI's, the always start with `<scheme>:`. The `scheme` can be well known schemes like `http` or `mqtt`, built-in schemes like `activty`, `cmd`, `device`, and `delay` or schemes from templates defined in the configuration file.

#### The `activity` scheme

The activity scheme has the following basic format:

    activity:off or activity:switch:<activity-name>

With `activity:off` all current active devices will be turned off and there will no longer be an activity selected until such time as a new activity is activated.

With `activity:switch:<activity-name>` a specific activity can be activated. Activities can be given descriptive names like `listen-music`, `watch-tv`, etc.

#### The `cmd` (command) scheme

This scheme has the following format:

    cmd:<command-name>

This scheme executes actions only in the context of the currently active activity. It will look for a mapping in the currently active activity that matches the given command name, and if found will execute the given follow up action there. For example, if `up` in the current activity is mapped to `http://192.168.3.9/doStuff` then `cmd:up` will find this mapping and execute the HTTP call that was associated with it. If the current activity has no mapping with the given name, or there is no active activity, then the command will do nothing.

#### The `device` scheme

The device scheme can be used to trigger an action for a specific device. A device defined with the id `Yamaha` which has a mapping for `VOL_UP` can be targetted like this:

    device:Yamaha:VOL_UP

The above action will look for a device with the id `Yamaha`, find the mapping named `VOL_UP` and execute the action defined for it. If there is no such mapping, the action will be ignored.

#### The `delay` scheme

Sometimes it is useful to delay an action while a device activates or becomes ready to receive more actions. The delay action allows you to introduce arbitrary delays which block all processing until the delay completes. Its format is:

    delay:<amount>:<unit>

The supported units are minutes (`m`), seconds (`s`), milliseconds (`ms`) and nanoseconds (`ns`). The amount must be a whole number and cannot be negative.

Examples:

    delay:5:s -- delay for five seconds
    delay:1:m -- delay for one minute
    delay:250:ms -- delay for 250 milliseconds
    delay:100000:ns -- delay for 1000000 nanoseconds

#### Action Propagation Example

When an action is triggered by some input device, the configuration file is checked to see how a given input action maps to a mapping in an activity, which in turn can map to a device or template, which in turn may map to an external action like a HTTP call or MQTT message. Below is an example of how an input received from an input device may get mapped into an action for a specific device:

    input-device:rc1:KEY:VOLUMEUP:pressed -> cmd:volume-up
    cmd:volume-up -> device:Yamaha:VOL_UP (based on the current active activity)
    device:Yamaha:VOL_UP -> irsend:NEC:32:5EA158A7:0 (a specific IR command for the Yamaha device using the irsend template)
    irsend:NEC:32:5EA158A7:0 -> http://192.168.3.18/cm?cmnd=IRSend(...) (a HTTP request to a Tasmota IR Blaster)

### Templates

Templates are used to reduce the amount of boilerplate that needs to be specified when defining an action URI. For example, for a HTTP call, you may need to specify its name, port, path and part of its payload every time. Putting the common parts in a template not only makes the actions shorter and easier to understand, but it also makes it easier to change all commands using the template in one place.

Templates look like this:

```yaml
templates:

  irsend:
    template: <protocol>:<bits>:<data>:<repeat>
    method: GET
    action: http://192.168.3.18/cm
    query:
      cmnd: IRSend {"Protocol":"<protocol>","Bits":<bits>,"Data":"0x<data>","Repeat":<repeat>}

  mediasystem:
    template: <key>
    method: POST
    action: http://192.168.3.17:8040/executeCommand
    payload: '<key>'
```
Each template has an id which defines the scheme the template will apply to. In the above example, we define the schemes `irsend` and `mediasystem`. Any mappings that start with these identifiers will have the template applied. Every template has a `template` section where angle bracket syntax can be used to identify parts that can be configured for the template. For the `irsend` template, creating a mapping that reads `irsend:NEC:32:12345678:0` means that the template values for `protocol`, `bits`, `data` and `repeat` will be `NEC`, `32`, `12345678` and `0` respectively. The final resulting action for the `irsend` template will be a new action of the form (excluding URL encoding for clarity):

    http://192.168.3.18/cm?cmnd=IRSend {"Protocol":"NEC","Bits":32,"Data":"0x12345678","Repeat":0}

Templates can map to any other action, including to another template.

### Defining a device

Devices are defined in the `devices` section. Each device has an arbitrary `id` with which the device can be referred to use one of its mappings. A device also has an activation and deactivation sequence. Here's an example for a Yamaha Receiver:

```yaml
devices:
  - id: Yamaha
    activation:
      - irsend:NEC:32:7E8154AB:1
    deactivation:
      - irsend:NEC:32:7E8154AB:1
    mappings:
      SET_OUTPUT_HDMI_1: irsend:NEC_LIKE:32:5EA1E21C:1
      SET_OUTPUT_HDMI_2: irsend:NEC_LIKE:32:5EA152AC:1
      SET_OUTPUT_HDMI_3: irsend:NEC_LIKE:32:5EA1B24C:1
      SET_OUTPUT_HDMI_4: irsend:NEC_LIKE:32:5EA10AF4:1
      SET_OUTPUT_HDMI_5: irsend:NEC_LIKE:32:5EA10EF0:1
      VOL_UP: irsend:NEC:32:5EA158A7:0
      VOL_DOWN: irsend:NEC:32:5EA1D827:0
      MUTE: irsend:NEC:32:5EA138C7:1
```

To map an action to a mapping for this device, you'd use `device:Yamaha:VOL_UP`.

### Defining an activity

An activity defines which devices participate in it, any initial set-up and how commands (`cmd`) should be mapped to other actions (usually devices). An example is given below:

```yaml
activities:
  - id: watch-tv
    description: Watch TV
    participants:
      - Yamaha
      - Arris
      - Epson-Projector
    setup:
      - device:Yamaha:SET_OUTPUT_HDMI_2
    mappings:
      volume-up: device:Yamaha:VOL_UP
      volume-down: device:Yamaha:VOL_DOWN
      mute: device:Yamaha:MUTE
      channel-up: device:Arris:channel-up
      channel-down: device:Arris:channel-down
```
The above activity has three participants (a HDMI receiver, a TV box and a Projector). Any devices which were not active when this activity is activated will have its activitation sequence triggered. Once all devices are active, the setup actions are triggered one by one. Once the activity is fully setup, it becomes active, and any command actions will use the mappings in this activity to map commands to activity specific actions.

### Defining Connectors

Connectors provide sources of input. Currently only Linux input devices are supported using either `/dev/input/event` style devices or even more low-level, the `hcidump` tool (for reading bluetooth devices that refuse to create a normal input device). An example is given below for a Conceptronic Remote and for any currently paired bluetooth device:

```yaml
connectors:
  org.int4.nexus.core.connector.InputDeviceConnector:
    - id: rc1
      device: /dev/input/by-id/usb-TopSeed_Technology_Corp._USB_RF_Combo_Device-event-kbd
    - id: rc2
      device: /dev/input/by-id/usb-TopSeed_Technology_Corp._USB_RF_Combo_Device-if01-event-mouse
  org.int4.nexus.core.connector.HciDumpConnector:
    - id: hci
```
The id's will be used to distinguish the generated action URI's for these inputs. The `InputDeviceConnector` creates actions of the form `input-device:<id>:` while the `HciDumpConnector` will create actions of the form `hci:`.

Most connectors will contain some kind of key code (text or hexadecimal) to which a key state is appended. The states supported are `pressed`, `released`, `held`, `short-pressed` and `long-pressed`. These can be mapped arbitrarily to follow-up actions. Some example input URI's:

    input-device:rc1:KEY:LEFT:pressed -- the input device with id "rc1" notified us that "KEY:LEFT" is in the pressed state
    input-device:rc1:KEY:LEFT:held -- the input device with id "rc1" notified us that "KEY:LEFT" is being held down (this repeats)
    input-device:rc1:KEY:LEFT:long-pressed -- the input device with id "rc1" notified us that "KEY:LEFT" has reached the long press threshold
    input-device:rc1:KEY:LEFT:released -- the input device with id "rc1" notified us that "KEY:LEFT" was released

The `HciDumpConnector` has less readable actions (and you may have to monitor its output to find the required codes). For example:

    hci:001f:44000000:pressed -- could signal that the "left" key was pressed

Read on in the next section how to make these input actions more readable.

### Input Mappings

The inputs generated by connectors can sometimes be hard to read or need to be mapped to something non-obvious. Although it is possible for a connector to generate an action that could immediately map to some device mapping, most input connectors will not. This means that any input must be mapped to another action, be it a command for the active activity, a direct action like a HTTP call or always to a specific device (without the activity indirection).

For example, to map the `LEFT` key from the `rc1` input device to a `left` command when it is pressed and repeated each time when it is held, define the following input mappings:

```yaml
input-mappings:
    input-device:rc1:KEY:LEFT:pressed: cmd:left
    input-device:rc1:KEY:LEFT:held: cmd:left
```

To control activities with long press actions, you can for example do this:

```yaml
input-mappings:
  input-device:rc1:KEY:SLEEP:long-pressed: activity:off
  input-device:rc1:KEY:RADIO:long-pressed: activity:switch:watch-chromecast
  input-device:rc1:KEY:MP3:long-pressed: activity:switch:watch-tv
```

Inputs that have meaningless codes can be mapped a bit more clearly like this:

```yaml
input-mappings:
  hci:001f:61000000:pressed: cmd:subtitles  # Text key
  hci:001f:bd010000:pressed: cmd:info  # Info key
```

To more directly have a specific input action control a device or trigger an external action, just use a different scheme for the mapping. For example:

```yaml
input-mappings:
    input-device:rc1:KEY:VOLUMEUP:pressed: device:Yamaha:VOL_UP
```

The above command will always trigger the VOL_UP mapping on the Yamaha device, regardless of selected activity or whether any activity is active at all. Note that if the device is off, it won't be turned on in this case. Only activities can turn devices on and off (although it  is possible still to directly map to an on/off action for a device if so desired).

### Handlers

Handlers offer ways to trigger an external action. They are defined in the configuration file in the `handlers` section, and may offer additional configuration parameters. A handler has its own scheme that can be chosen with its `id`. It is allowed to define the same handler multiple times with different id's to allow for different configurations. To use a specific handler in an action, make sure its scheme matches the id of the intended handler.

Currently there are three supported handlers: `MqttProtocolHandler`, `WakeOnLanHandler` and `HttpProtocolHandler`.

## Full Sample Configuration

