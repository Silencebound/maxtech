---
navigation:
  title: Logic Remote
  icon: maxtech_storage:logic_remote
  parent: logic_controller.md
  position: 30
item_ids:
  - maxtech_storage:logic_remote
---

# Logic Remote

The <ItemLink id="maxtech_storage:logic_remote" /> shows a network's logic notices and pauses or resumes its rules
from anywhere, in any dimension. Sneak-use it on a device or cable of the network to bind it. The network's controller
must be loaded, and you need logic [access](security.md).

Use it to open its screen: **NOTICES** lists the newest notices, **RULES** lists the rules of the network's
[Logic Controllers](logic_controller.md) with **Pause** and **Resume**. A bound remote also pings new notices as a toast
(turn off with `logic.remotePings` in the client config).

It runs on its own charge from any charger: it holds <Config key="logic.remote.energyCapacity" unit="MU" /> and each
pause or resume costs <Config key="logic.remote.energyPerOperation" unit="MU" />. Without charge it still shows the
last notices.

<RecipeFor id="maxtech_storage:logic_remote" />
