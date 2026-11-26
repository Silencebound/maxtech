---
navigation:
  title: Server tools
  icon: maxtech_storage:usage_report_book
  parent: security.md
  position: 10
item_ids:
  - maxtech_storage:usage_report_book
  - maxtech_storage:quarantine_tag
---

# Server tools

The <ItemLink id="maxtech_storage:usage_report_book" /> writes a report of a network (use it on a block) or of all your
team's networks (use it elsewhere) against the server's caps.

<RecipeFor id="maxtech_storage:usage_report_book" />

The <ItemLink id="maxtech_storage:quarantine_tag" /> freezes a network until you use it again (owner and operators).

<RecipeFor id="maxtech_storage:quarantine_tag" />

## Operator commands

- `/maxtech network list|inspect|team` shows the heaviest networks.
- `/maxtech logic list|pause|resume|info|screen` manages every loaded logic rule.
- `/maxtech network snapshot save|list|restore|delete` keeps a network's settings, up to
  <Config key="snapshots.maxPerNetwork" /> per network.
