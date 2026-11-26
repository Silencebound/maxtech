---
navigation:
  title: Request board, mail and ledger
  icon: maxtech_storage:mail_port
  position: 97
item_ids:
  - maxtech_storage:request_board
  - maxtech_storage:mail_port
---

# Request board, mail and ledger

Services for shared networks. Players need [access](security.md).

## Mail port

The <ItemLink id="maxtech_storage:mail_port" /> sends up to nine stacks to a player's inbox. Type the name and press Send;
each stack costs <Config key="services.mail.muPerStack" unit="MU" />. Collect at any mail port.

<RecipeFor id="maxtech_storage:mail_port" />

## Request board

The <ItemLink id="maxtech_storage:request_board" /> lists item requests for a team. Hold an item, or click one in the
board's network grid (or drag one from JEI), and press an amount to ask; each request shows how many the network holds.
Fulfil mails the items from the board's network to the poster.

<RecipeFor id="maxtech_storage:request_board" />

## Ledger

The [controller](controller.md#history-telemetry-ledger-and-redstone-alarms) records what each player put in and took
out, per day, for <Config key="controller.recorders.ledgerDays" unit="days" />. See it on the History tab.
