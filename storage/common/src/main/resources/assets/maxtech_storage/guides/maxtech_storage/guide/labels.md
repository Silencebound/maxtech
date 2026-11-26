---
navigation:
  title: Labels
  icon: minecraft:name_tag
  parent: logic_controller.md
  position: 30
---

# Labels

A label is a short name and a colour on a device, such as "intake" in green. [Logic](logic_controller.md) graphs pick
devices by label, so several devices with one label act together.

Open the label tab on the right edge of any device screen, type a name (up to 32 characters, case ignored) and pick a
colour. An empty name removes it. It needs full access to the device. Devices without a screen take a label from a
[blueprint card](device_tools.md).

**Copy to all** in the label tab gives every other device with the same label this device's settings (what the
blueprint card copies, plus drive priority).

Jade, the scanner and the network analyzer show the label, and the controller's Network tab lists them. Labels belong to
one network; a [subnet](subnets.md) keeps its own. A label stays with a broken device's item.
