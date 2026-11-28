# Writing Storage's guide

Storage's guide is drawn by GuideME (MGM). The pages are Markdown with GuideME's frontmatter and tags, in
`storage/common/src/main/resources/assets/maxtech_storage/guides/maxtech_storage/guide/`; no other file belongs in
that folder, since GuideME reads every `.md` there as a page. Pages written by datagen land in the same path under
`src/generated/resources`.

## Hot reload

`./gradlew :storage:neoforge:runClient` sets `-Dguideme.maxtech_storage.guide.sources` to the page folder above (see
the guides section of `build-logic/src/main/kotlin/maxtech.neoforge.gradle.kts`). GuideME then reads the pages from the source folder and reloads a page as soon
as its file is saved, while the guide is open; no resource reload or restart is needed. Generated pages still come
from the build output, so run `runData` after changing their sources.

## Opening the guide

- The Storage manual (a book and a lumen quartz, shapeless) opens the start page.
- Hold GuideME's key (`key.guideme.guide`, G by default) over an item's tooltip to open the page listing that item in
  its `item_ids` frontmatter.
- GuideME's command `/guideme open @s maxtech_storage:guide` opens it too (permission level 2).

GuideME reports a missing item, recipe, page or unknown tag as red text on the page itself, not in the log.

## Storage's tags

Pages use GuideME's tags plus one of MaxTech's own, from Lib (`LibGuides.tagCompilers()`):

- `<Config key="network.channels.adHoc" unit="channels" />` shows a value of Storage's server config in bold, with
  thousands grouped and the unit after a space; its tooltip names the path and the default. On a client in a world the
  value is the server's (NeoForge sends server configs to joining clients); outside a world it is the default. The key
  may carry the `maxtech_storage:` prefix; another MaxTech mod's value takes its namespace (`maxtech_lib:...`).

Tables are GitHub Markdown tables, and their cells may hold tags (`<ItemLink>`, `<Config>`). Scenes are GuideME's
`<GameScene>` with `<Block id="..." x="0" y="0" z="0" p:facing="south" />` or `<ImportStructure src="x.nbt" />` and
annotations such as `<DiamondAnnotation pos="0.5 0.5 1.5">`. Write every element inside a scene on its own lines,
an annotation's text too: Markdown wraps a one-line `<Tag>text</Tag>` in a paragraph, and GuideME's scene skips it
silently.

Structure files (`*.nbt` beside the pages) are generated, never edited by hand: each is a `GuideScenes.Scene`
(`storage/common/.../dev/GuideScenes.java`), built with the real game in a GameTest and saved with each block entity's
client update tag (cable arms, drive lights; no owners or contents). To add or change one, edit `GuideScenes` and run
`MAXTECH_GUIDE_SCENES=write ./gradlew :storage:neoforge:runGameTestServer -Pgametest='maxtech_storage:guide_scene_build_*'`,
then commit the files; without the variable the `guide_scene_build_*` tests fail when a file differs from what the game
builds now. `guide_scene_files` checks every file is a known scene, small, and holds only Storage and vanilla blocks
and no UUIDs.

The `guide_guideme_tags` GameTest parses every page with GuideME's own parser on the server and fails on a tag
neither GuideME nor Storage draws, a `<Config>` key that is not a server config value, a scene block or block state
property that does not exist, and a scene structure that does not ship.
