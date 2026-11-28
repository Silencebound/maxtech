# MaxTech Storage changelog

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow semver (PRD §5.8).
Entries before 2026-10-06 are not recorded; 0.2.0 is the first version in this repository's history.
Api changes are marked **Api**.

## [Unreleased]

### Added
- REI: entries can be dragged onto the pattern terminal's draft slots, the request board's request slot, port filter
  slots and the view filter, as with JEI.
- Guide: 3D scenes on eleven pages (getting started, cables, drives, terminals, ports, pattern providers, crafting
  CPU, IO port, interface, logic controller, energy), with short labels on the blocks.
- Crafting toasts: the player who started a job gets a toast when it finishes, stops (items missing, a changed
  recipe), is held up by a locked provider (once per job) or someone else cancels it; inside the terminal a click
  opens the job.
  Client option `crafting.toasts`; server option `crafting.teamToasts` (default off) sends them to the team too.
  The terminal's jobs tab shows a red badge for a stopped or held job and amber for one waiting for a CPU or power;
  device side tabs show amber when the network has no power, and the generator provider's tab shows why it is stuck.
- Terminal sizes: a button next to the sort buttons picks small, tall or fill (as many rows as the window holds),
  saved per terminal kind; the screen is never taller than the window. Replaces the `terminal.tallLayout` option.
- View filter: an item set up in hand with up to nine items, fluids or tags (exact or fuzzy, show or hide); up to four
  in a terminal's filter slots limit what its grid shows. Terminal, crafting and pattern terminals (block and part)
  have the slots, which keep the filters and drop them when broken.
- View filter slots on the wireless terminal and portable cell (kept in the item) and on the storage chest (kept in
  the chest and in its item when broken). A portable cell holding filters keeps its chip in the workbench.
- Pattern provider and crafter lock modes (Mode & redstone tab): locked with or without a redstone signal, one batch
  per redstone pulse, or one batch until its result returns. A job uses another provider meanwhile or waits and names
  the lock; Jade shows a holding lock, and the mode drops with the block and copies with the blueprint card.
- Placer drop mode: the placer (block and part) can drop a stack or a single item of its filtered item in front of
  it instead of placing blocks, set in the mode tab next to pulse or continuous. It pauses while
  `world.placer.dropEntityCap` item entities (default 16) lie there.
- NeoForge's config screen (Mods, Config) for Storage's client, common and server files: all 310 options and their
  sections have names and tooltips with units, range and default, the most often changed sections come first, and
  the `[modules]` switches and the animated effects say they apply after a game restart.
- Terminal search: `$text` matches the tooltip (built only while such a word is searched, then cached). A button next
  to the search shows all entries, only items or only fluids; the choice is saved with the pins and tabs.
- Pattern terminal: x2 and ÷2 buttons (shift: 8) scale every amount of a processing pattern; ÷ is greyed out with
  the reason while an amount would not stay whole. Stonecutting and smithing tabs (and JEI's + for those recipes)
  write patterns the crafting assembler runs like crafting patterns.
- Crafting terminal (block and cable part): the terminal screen with a 3 x 3 crafting grid fed from the network.
  Taking the result refills the grid from the network, shift-click crafts up to 64 times, leftovers such as empty
  buckets go to the network, and JEI's or REI's "+" fills the grid, with a request button for missing ingredients.
  The grid keeps its items and drops them when the terminal is broken.
- Crafter: holds nine crafting, stonecutting or smithing patterns and crafts them itself when a job needs them, no
  pattern provider or assembler needed (`crafting.crafterEnergyPerCraft` MU per craft). It drops with its patterns
  and any results still inside.
- The crafter and the crafting assembler show the item they craft, turning behind their glass while they work (client
  options `visuals.craftingDisplay` and `visuals.craftingDisplayDistance`).
- Pattern terminal and request board show the network's items. The pattern terminal is now the terminal screen with
  the pattern panel under a shorter item grid (it fits a 240 high window without scrolling): select a draft slot and
  click an item, or drag one onto a slot from the grid or JEI, to set it without taking anything. The request board
  posts a request for an item clicked in its grid or dragged from JEI, and shows how many the network holds of each
  request. Placed terminals and boards keep working.
- Ports: right-click a filter slot to make it fuzzy (any damage or components), a tag of its item or its whole mod,
  for import, export and the storage bus; JEI items and fluids can be dragged onto the filter. Export ports keep a set
  amount in the target per slot (regulator) and can take their filter's kinds in turn (round robin). New option
  `ports.filter.exportKeysPerOperation`.

### Removed
- The status crown and light strip (owner decision 2026-10-18). A placed one turns into air when its chunk loads, and
  a controller that still lists one as a module drops it.

### Changed
- Needs MaxTech Lib 0.15 (the Accent widgets moved to Lib's api); screens look and work as before.
- GuideME is no longer bundled in Storage's jar: Lib 0.15 bundles it and draws Storage's guide, with the same pages,
  tags, scenes and website. A `<Config>` key may now name another MaxTech mod's value (`maxtech_lib:energy.feRate`).
- The logic controller, logic remote and logic admin screens use the Accent Frame look like every other Storage
  screen; the controller's label, status and power priority moved to the device side tabs.
- With the same pattern in several providers or crafters, the highest pattern priority gets its batches first and
  what it does not take goes to the next lower priority; equal priorities share as before. Before, priority only chose
  between different patterns.
- The crafting assembler has glass windows now. It and the crafter no longer occlude: light passes through them
  (dimmed by one level, like leaves), and neighbouring blocks draw the faces that touch them. They still conduct
  redstone and still suffocate. A provider or crafter keeps pulling results left in an assembler's or crafter's
  buffer (the network had no room) until it is empty, instead of waiting for the next batch; while nothing moves it
  retries less often, up to every 100 ticks.
- Screens use Lib's shared Accent widgets: one button set for every button, one scroll bar, tooltips through
  vanilla widget tooltips. Buttons that were drawn by hand in the generator, provider, sensor, security, board,
  controller (tabs and craft limits) and puzzle screens are now real widgets, so they take keyboard focus and
  narration. The world device's mode button and the pattern terminal's encode arrow are icon buttons.
- Jade shows storage blocks in two rows: the name with a status dot in the front line's colours, then bars and icons
  (a drive's fill bar and largest items, the controller's channel and energy bars, a cable's or hub's channel bar, a
  port's mode arrow and rate with its filter items). Sneak for the full details; the scanner and analyzer print the
  same in words.

### Fixed
- Guide scenes no longer crash when no server config is loaded (the guide's web export, a client outside a world):
  server config values read as their defaults until the file loads.
- An item or fluid of a removed mod no longer makes a whole storage cell unreadable. The unknown entry is kept as
  saved, still takes its bytes and kind, and comes back when the mod does; everything else in the cell stays usable.
  The cell's tooltip and the cell workbench show how many unknown entries it keeps. Bulk drives, bridge held stores,
  recovery rows, crafting jobs and runs, pattern provider batches, mail inboxes and cell filters keep such entries the
  same way (a kept filter entry matches nothing, so an allow list stays restrictive); stock keeper rules drop just the
  unknown key, and a cell whose memory module was removed loses only that module.
- Encoded patterns, bulk drive items and interface items naming an item or fluid of a removed mod are no longer lost
  from inventories and containers: the unknown part is kept as saved and comes back with the mod. Such a pattern is
  shown as unusable and never crafts; a placed drive or interface keeps the part and takes nothing in its place.
- A placed interface whose stock holds an item or fluid of a removed mod no longer loses all its stock slots or tanks:
  only that slot or tank is kept as saved (empty, taking nothing in), goes onto the item when the interface is broken
  and comes back with the mod.

## [0.9.0] - unreleased (beta)

Public beta. The mod is feature-complete and waits for feedback from servers, modpacks and other mods. Report bugs and
requests on the issue tracker. Worlds from the beta will load in 1.0: any change to the save format ships with a
migration. Keep backups anyway.

### Added
- Sensors read MaxTech energy network values (`mu.supply`, `mu.demand`, `mu.received`, `mu.supplied`, `mu.stored`,
  `mu.capacity`, `mu.ticks_to_full`, `mu.ticks_to_empty`, `mu.pass_nanos`) from blocks that show a MaxTech energy
  endpoint; the signal probe and the analyzer show the energy network's readout, also on a fuel generator that feeds
  a network.
- Network bridge block with AE2 and Refined Storage adapters, so a MaxTech network can share items and fluids with
  those networks.
- Showcase stations for channels, bridges and priority, and an energy station: a network run by a fuel generator
  alone, with energy cells of every tier and a chest holding a signal probe and a network analyzer.
- Device mode is a side button on device screens.
- Glass storage cables with one glowing lane.
- Block entities, cable parts, saved data and storage cells save a data version, so later updates can migrate old
  worlds.
- Controller upgrade cards: batch amplifier, efficiency core and surge regulator cards go in the controller's new Cards
  tab, 4 slots plus 1 per further controller block, at most 12 (`controller.cards.*`), behind the new Upgrade Cards
  research node. The Network tab lists the installed cards.
- Every controller records its telemetry history, keeps a ledger and runs up to 4 redstone alarm rules
  (`controller.recorders.*`); its blocks give a redstone signal while a rule holds. The History tab and a network
  terminal's new Network history tab show graphs, ledger and rules, each behind its own research node (Network
  Telemetry and the new Network Ledger; Network Tuning for the rules).

### Changed
- Energy cells, fuel generators, the creative energy source, display panels, network anchors, security modules,
  research terminals, sensors, condition switches (blocks and parts) and crafting CPUs use no channel and count towards
  no device limit: they join whenever cables connect them, also on a network whose channels are full, so other devices
  may get the channels they held. Their fronts show only online or offline.
- The `pack` preset now lists every energy key (`controller.inputPerTick`, `network.subnet.inputPerTick`,
  `network.energy.fluidMbPerUnit`, `cells.portable.energyCapacity`, `cells.portable.maxInput`), with the same values
  as before.
- Needs MaxTech Lib 0.13 (Lib's guide is retired). The guide's screen previews, live network values, comparison-table
  columns and `<LogicGraph>` element, which only Lib's guide drew, are removed.
- GuideME (26.1.15-beta or a later 26.1 version) draws Storage's guide and is a required dependency, bundled inside
  Storage's jar. The Storage manual and GuideME's hold key (G over an item's tooltip) open it; Lib's guide no longer
  shows Storage's pages.
- Storage runs on MaxTech energy (MU) instead of FE (MLE item 4). Each storage network is a MaxTech energy network:
  fuel generators touching it feed it, its controller's buffer charges first and energy cells take what is left, and
  operations still pay per operation from them. Other mods' FE blocks next to an energy cell join it through edges;
  fuel generators are network devices with one channel each; P2P energy links carry MU; sensors' `energy.*` values are
  MU; every Storage block and carried item takes FE at Lib's rate (`energy.feRateFe`/`feRateMu`, 1:1 by default) and gives
  FE out only under Lib's export settings. Saved FE becomes MU once (block entity data version 8; the fraction of one
  MU an uneven rate leaves is kept); carried items convert when first read. Config keys `network.adHoc.bufferFe`,
  `controller.bufferFe`, `network.subnet.bufferFe`, `services.mail.fePerStack` and `crafting.cpu.tierN.fePerLane` are
  now `...Mu`/`mu...`; the old keys are read for one more version and log a warning. **Api**: `StorageNetwork`'s
  `storedEnergy()` and `energyCapacity()` are in MU (no signature change).
- Fuel generators already placed now take a channel each and join as the newest device when they load, so on a
  network already at its channel limit they stay offline (and feed nothing) until a channel frees up or a hub adds
  capacity. Machine and subnet controller items from before show their energy as MU.
- Requires Lib 0.12 (`[0.12,0.13)`).
- The storage controller is a multiblock: controller blocks placed face to face in any shape form one controller.
  Every face touching a cable or device is a root link, each block adds 10,000 FE buffer and 1,000 FE/t input, and
  research raises the size from 8 blocks to 16, 32 and 64 (`controller.size.*`). Touching controllers merge;
  breaking the block that holds the state hands it to another block.
- No device limit any more: channels limit networks (`controller.maxDevices` removed).
- Batch amplifier, efficiency core and surge regulator blocks no longer join the controller and have no recipe; a
  controller's fill turns them into cards (block entity data version 3), and broken they drop their card.
- Telemetry recorder, ledger module and alarm module blocks do nothing any more and have no recipe; a controller's
  fill moves an old ledger module's rows into its ledger and an old alarm module's settings into a rule, once (block
  entity data version 4). Network snapshots keep the controller's alarm rules (snapshot format 4).
- Security module, network anchor, antenna mount, wireless access mount and dimension bridge are network devices
  placed anywhere on the network, each using one channel. Several security modules all turn on the controller's rules;
  wireless range counts from each wireless access mount and antenna; an anchor keeps the chunks up to itself; a paired
  antenna mount links the antenna's site into the network. Old worlds keep the blocks in place as devices with their
  settings (block entity data version 5).
- The controller capacitor bank is gone: its +40,000 FE and +1,000 FE/t went into the tier-1 energy cell (now
  140,000 FE and 2,000 FE per transfer; `controller.modules.capacitorBank.*` removed). A placed bank in an old world
  becomes a tier-1 energy cell in place, bank items become energy cells, and the controller moves the FE above its
  capacity into those cells once (block entity data version 6); nothing it does not fit is lost. An existing server
  config keeps its old tier-1 values (100,000 FE, 1,000 FE): config files are not rewritten, so set
  `energy.cells.tier1.capacity` and `maxTransfer` by hand to get the new defaults.
- Status crowns and light strips are decoration placed anywhere on the network (on a cable or touching a device) and
  use no channel; they show the state of the network's controller and change only when it does. Lights attached to a
  controller in an old world keep working in place (block entity data version 7). English names drop "Controller".
- Channel arrays, capacitor banks and device buses no longer join the controller. Old controller saves migrate (block
  entity data version 2) and keep their FE, name, settings, alarms and recovery rows.
- Research for the multiblock controller: the three Controller Cluster nodes are Controller Size I to III (tiers 2, 3
  and 4, asking for a busier network each), Upgrade Cards moved to tier 2, Network Telemetry needs only the logic mask,
  Network Tuning is called Redstone Alarms and follows Network Telemetry, Efficient Power follows Upgrade Cards, and
  Network Oversight no longer needs a larger controller. Teams that completed a Controller Cluster node have the
  Controller Size node of the same step; the old ids stay in saved research. A team with Efficient Power but not
  Upgrade Cards gets Efficient Power back as a skip token.
- Server config keys moved: `controller.modules.batchAmplifier.*`, `efficiencyCore.*` and `surgeRegulator.*` to
  `controller.cards.*`; `controller.modules.telemetryRecorder.*` and `services.ledger.*` to
  `controller.recorders.telemetrySampleTicks`, `telemetrySamples`, `telemetryMaxKeys`, `ledgerDays` and
  `ledgerMaxRows`; `controller.modules.networkAnchor.*` and `controller.modules.dimensionBridge.*` to `devices.*`. The
  old keys are read for one more version: a value other than -1 still applies and logs a warning naming the new key;
  values left at the preset default move by themselves.
- Channel array and device bus have no recipe or creative tab entry any more; Jade calls every retired expansion block
  retired and says what to do with it.
- Jade shows a controller network's root links in use, a wireless access mount's range, an anchor's chunks, a security
  module's rules and what a status light shows.
- Holding a controller block shows where it would join the nearest controller (the cluster preview no longer waits for
  an expansion block).
- Guide: the controller page covers the multiblock, cards, recorders and older worlds; the network devices and status
  lights have a page of their own.
- The core showcase station builds a two-block controller with upgrade cards.
- **Api** Breaking, Storage 0.5.0: `StorageContents.latestSnapshot()` returns `Optional`; `StorageNetwork.Status`
  lost the unreported `CONTROLLER_CONFLICT` and `TOO_MANY_DEVICES`; `storage.api.crafting` is experimental. Storage
  now requires exactly Lib 0.9.x.
- **Api** Javadoc on every public api type and member, checked by the build. No signature changed. Inventory and
  open questions for 1.0 in `docs/api/storage-1.0.md`.
- Priority uses the full integer range.
- A broken bridge keeps refused fluid in its item.
- Faster bridge pending totals.

### Fixed
- Showcase redstone examples that never fired.
- Showcase P2P is fed through its front.
- Refined Storage export list stays bounded; bridges work across merged networks.

### Removed
- The retired priority add-on, router and buffer.

## [0.4.0] - 2026-10-15

### Added
- Channel hubs in three tiers.
- A second controller goes on standby.
- Channel wording in the probe, alarms, analyzer and logic.

### Changed
- **Api:** `StorageDevice.bandwidthDemand()` is renamed `operationsPerSecond()`.

## [0.3.0] - 2026-10-14

### Added
- Channels replace bandwidth as the network limit.
- Crafting: bandwidth reserve for crafting pulls, energy per operation, job inputs in runs, 16,000 mB fluid batches.
- Crafting: craft limits, delivery of finished crafts to the requester or a label, provider shown in the job view,
  order lists (`mtlist1`), pattern overview tab with per-pattern priorities, generator providers that craft by
  switching a machine on, missing-item failure with remote toasts and reach.
- Logic module: rule core with compiler and incremental evaluator, controller, node editor, cards with in-game
  copy/paste and datapack templates, text export/import, timer, day/night, math, statistics, hook, for-each, step
  sequence, machine pool, parameter and cross-network nodes, action budgets, simulate mode, pinned values, change
  history, logic remote, KubeJS script nodes and events, FTB Teams budgets and notices, admin overview.
- Sensor block and part that read a neighbouring block; fluid, energy, free space, craft status, tag and redstone
  strength reads; pack readers.
- Device labels with a network index and shared label tab; copy a device's settings to all devices with its label;
  smart labels showing a count, fill or machine state.
- Displays: rich text, history charts, sparklines, task board, production stats, alarm panel, build material list with
  take-all, store and take items by clicking, framed display walls.
- Terminal: locate an entry's drives and cells, export the inventory list, scroll the pattern terminal on short
  windows, own side tab icons.
- Empty a cell into the network or fill it from the drive; recovery bin for voided and lost items.
- Setup blueprints with ghost paste and auto-build; ghost preview for clusters.
- Colour-blind palettes and status glyphs; wrench shows cable connection modes; advancement tree matching the guide.
- Network settings snapshots for admins; network profiler command; REI support next to JEI.
- Wireless terminal picks a block from the network; one key opens carried gadgets; logic remote reaches its network
  from anywhere.
- Bundled redstone channels; pause low-priority devices when network power runs low; push mode for the creative
  energy source; FTB Teams party network caps; land claim check for devices using a faced block.
- Storage manual in Lib's guide (GuideME dropped); drive screen and shared priority tab; AE2-style crafting request,
  status and pattern terminal screens.

### Changed
- Cables have equal width with tier bands and tier lanes; no cable animation; the connector ring is gone from cable
  ends.
- Light grey textures and facing looks for devices.
- Every screen's text and panels fit the smallest GUI.

### Fixed
- Catalysts are returned to jobs and late byproducts stored.
- Condition switches wake only when research can change them.
- Logic controller tabs and list rows are clickable; the editor's top bar fits any window size.
- Showcase export port, subnet stub and crafted-output theft.

## [0.2.0] - 2026-10-06

Baseline: Storage imported into this repository (cells, drives, terminals, cables, crafting, controller, wireless
terminal, config and presets).
