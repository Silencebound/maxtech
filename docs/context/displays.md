# Context pack: displays

Orientation for work on display panels, list screens and smart labels. Paths are under
`storage/common/src/main/java/dev/silencebound/maxtech/storage/` unless noted. Details and owner checks per feature:
`milestones/M7C/state.md` (milestone notes) (list screens, items 15, 16, 18, 19) and `milestones/M7L2.md` (milestone notes) for text and chart
screens. Verified API facts: `.claude/skills/neoforge-26-1/storage-facts.md`.

## Types

| Type (in `display/`) | Role |
| --- | --- |
| `DisplayPanelBlock`, `DisplayPanelBlockEntity` | One panel. Holds `DisplaySettings` (kept in its item) and, on a wall's anchor only, the `ScreenWorker`. Never ticks. |
| `DisplayPart`, `DisplayDevice` | The part form (on a cable face) of the same screen. |
| `DisplayContent` | Registration, GUI lines, source display names and unit lang. |
| `DisplaySettings` | Saved per screen: mode, colour, source, item/tag, chart, text, `board`. Codecs; add new fields at the end with a default. |
| `DisplaySourceKind` | What a screen shows. Ids are saved and networked: never reuse or change one. `rows()` marks list sources. |
| `DisplayWalls`, `WallLayout`, `ScreenGeometry` | Wall merging; the view ray against the glass (used for row clicks). |
| `DisplayViewers` | Who sees which screen, from chunk watch events; no polling. |
| `ScreenWorker` | Server side of one screen, a task on the network scheduler. Reads its source, sends only on change and at most every `display.updateTicks`. Sleeps (`StorageTask.WAIT`) with no viewer. |
| `DisplaySync` | Sender of the payloads; GameTests swap it to count sends. |
| `RowFeed`, `RowFeeds`, `ScreenRows` | List-screen framework (below). |
| `client/DisplayScreen` | Settings GUI (menu buttons only); has a per-source hint `switch`. |
| `client/ScreenPainter` | Hologram drawing with vanilla/Blaze3D quads; lays rows out with the same numbers as `ScreenRows.rowAt`. |
| `client/TaskBoardScreen` | Task board editor opened over the display menu; the model for a source with its own editor. |
| `smartlabel/` (`LabelWorker`, `SmartLabelBlockEntity`, `LabelMode`) | Small block on any face. `LabelWorker` mirrors `ScreenWorker`: counts pushed by a per-key listener, the block behind read through `MachineReader`, sleeps unwatched. `LabelMode` ids are saved. |

Source ids in use: 0 item_count, 1 tag_count, 2 throughput, 3 power_stored, 4 power_in, 5 power_out, 6 bytes_used,
7 bytes_free, 8 logic_notices, 9 text, 10 history, 11 task_board (rows), 12 alarms (rows), 14 production (rows).
**13 is reserved for the build material list.** Gaps are fine: `byId` reads an unknown id as `ITEM_COUNT`.

## Threading and refresh

- Server thread only. Nothing ticks; a screen's task runs when woken and returns the delay until its next run.
- Pushed sources (counts, notices, rows) wake the task from a listener, and only while someone sees the screen. Sampled
  sources (rates, power, bytes) run on the cadence, only while watched. A feed's `refreshTicks()` re-reads rows on a
  timer, only while watched; the default `StorageTask.WAIT` means never. Never return a short delay unconditionally.
- `RowFeed.work` is the one thing that runs unwatched, and only after the feed called `screen.workDue()`.
- Clients get `DisplayRowsUpdate` only when the rows `equals` the last sent ones, so keep `ScreenRows` content stable.
- Client code is Blaze3D/vanilla only (no raw GL) and lives in `display/client`.

## List screens

`RowFeeds.create(source, screen)` is the one switch that maps a source with `rows() == true` to a feed. A feed:
`bind(network)` subscribes to events (`unbind` removes all of them), `rows(network, gameTime)` builds a `ScreenRows`
(title, status, optional header bar, at most 32 rows with tone, checkbox, icon, bar, history, note, time),
`click(player, network, row)` handles a right-click (return false to let the settings open; do the access check
yourself), and `work` and `refreshTicks` are optional. Existing feeds: `task/TaskFeed`, `alarm/AlarmFeed`,
`production/ProductionFeed`. Texts are `Component`s, so lang keys travel and a dedicated server needs no translations.

## PRD 8.1 traps seen here

- **Any-key move listener** (`network.listenItemMoves(null, ...)`) makes every transfer on the network record its move.
  It made `perf_storage_idle_ports` fail in #146. Start it lazily, when a screen first looks, and remove it on `unbind`.
- A feed that subscribes to a busy event (contents changes, energy) should mark and re-read on the cadence, not per event.
- No `getCapability`/`getBlockEntity` per refresh; use the network's cached stats.
- `perf_display_unseen_screens` (`DisplayPerfGameTests`) checks that unseen screens cost nothing. Keep it green.
- Per-network helper state (`ProductionStats`) is held weakly and dies on merge or split; do not store it in a long-lived field.

## How to add a list source

1. `DisplaySourceKind`: new constant with the next free id (not 13), `pushed=true`, a unit key, `rows=true`, Javadoc.
2. `ScreenWorker.value(...)` switch (near line 588): add the constant to the `-> 0` case.
3. `RowFeeds.create`: one `case` returning `XxxFeed`. Put the feed, its `XxxTexts` (lang keys and English text through
   `ModContent.CONTENT.translation`) and pure logic in `display/<name>/`.
4. `DisplayContent`: the source's display name and unit translation (see the ALARMS and PRODUCTION lines).
5. `client/DisplayScreen`: the hint `case` (`XxxTexts.HINT`). Extra settings go at the end of `DisplaySettings`
   (codec default) with a round-trip test.
6. `./gradlew :storage:neoforge:runData`; commit `storage/common/src/generated`; the generated folder must be clean.
7. Guide: a section in `storage/common/src/main/resources/assets/maxtech_storage/guides/maxtech_storage/guide/displays.md`.
8. Tests in `storage/neoforge/src/gametest/java/.../gametest/`: `AlarmPanelGameTests` is the pattern (`@EventBusSubscriber`,
   `RegisterGameTestsEvent` handler with an environment, `StorageTests.test(event, env, name, structure, ticks)` per
   name, a controller plus `TestStorage` device as set-up, `lookAtRow` for right-clicks). Cover rows after an event,
   a row click, save/load of any state, and that an unwatched screen does no work. JUnit for pure logic
   (`ScreenRowsTest`, `RateRingTest`). Break each test once.
9. Update `milestones/<M>/state.md` (milestone notes).

A non-list source (a pushed or sampled value) needs steps 1, 2, 4, 5, 6, 7, a branch in `ScreenWorker.value` and, if it
pushes, a listener started in the worker's bind path and removed in `stop`.
