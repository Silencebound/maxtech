---
name: guideme
description: Writing and checking MaxTech guide pages with GuideME (the AE2 guide library, bundled in Storage) — page style and length, tags, scenes and annotations, frontmatter, links and anchors, live preview, Storage's own tags and tests, and verified GuideME facts. Use for any guide page, guide tag, scene, or GuideME build question.
---

# GuideME for MaxTech guides

Storage's guide is drawn by GuideME (bundled jar-in-jar since MGM). Read this before writing or editing a guide page,
adding a tag or scene, or touching GuideME wiring. Search it with `rg -n '<topic>' .claude/skills/guideme/` rather
than reading it whole. Add or correct facts here when you verify or disprove something (same PR).

## Where to look

1. This skill (rules, Storage specifics, verified facts below).
2. The official docs: https://guideme.appliedenergistics.org/ — read the page before guessing a tag:
   - Authoring and tags: `/authoring/`, Markdown support: `/authoring/markdown`, scenes: `/authoring/game-scenes`
   - Live preview: `/live-preview`, commands: `/commands`, hotkey: `/open-guide-hotkey`, search: `/search`
   - Data-driven guides and colours: `/data-driven-guides`; integration: `/integration/`, custom recipe types:
     `/integration/recipe-types`, symbolic colours: `/integration/symbolic-colors`, website export:
     `/integration/website-export`; translation: `/translation`; converting from Patchouli: `/converting-patchouli`
   - Javadoc and sources: linked from the docs; the sources jar per the facts below. AE2's own guide pages are the best
     real-world examples (AE2 repo, `guidebook/`).
3. Storage's page-writing notes: `storage/docs/guide.md`.

## Writing pages (owner rules)

- **Short and concise, like AE2's guide** (owner 2026-10-16/17): most pages **60-150 words**, overview pages (index,
  getting started, controller, cables) **up to ~250**. Word counts include frontmatter.
- Each page: what it is (1-2 sentences), how to use it (a few lines), the numbers that matter via `<Config>`, its
  recipe (`<RecipeFor>`), links to related pages. No history, design rationale, rare edge cases, option lists or text
  that repeats another page or a tooltip.
- Prefer a picture over prose: `<BlockImage>`, `<ItemImage>`, or a small `<GameScene>` with annotations for anything
  spatial (root links, cable channels, multiblocks).
- Every item keeps a page through `item_ids` (the `guide_covers_items` test checks it); one page per item.
- Player-facing names come from the game (`<ItemLink>`), not typed by hand. Energy is MU (MaxTech energy), never FE as
  Storage's own unit.
- Only the controller animates in the mod; don't describe pulsing or blinking states for other blocks.
- Never copy AE2's guide text or art (AE2 art is CC BY-NC-SA 3.0); write our own.

## Storage specifics

- Pages: `storage/common/src/main/resources/assets/maxtech_storage/guides/maxtech_storage/guide/` (every `.md` there
  is a page; nothing else belongs there except page assets such as structures). Start page `index.md`.
- Guide id `maxtech_storage:guide`, declared in code in the client constructor (`StorageGuide`); opened by the Storage
  manual (book + lumen quartz), by holding G over an item, or `/guideme open @s maxtech_storage:guide`.
- Own tag: `<Config key="..." unit="..." />` shows a Storage server config value (synced in a world, default outside),
  registered in `StorageGuide.tagCompilers()`. Other mods' configs are not readable.
- Live preview: `./gradlew :storage:neoforge:runClient` sets `-Dguideme.maxtech_storage.guide.sources` to the page
  folder; saved pages reload while the guide is open.
- Tests (`./gradlew :storage:neoforge:runGameTestServer -Pgametest='maxtech_storage:guide_*'`): GuideME's own parser
  over every page; fail on unknown tags, bad `<Config>` keys, bad item ids, broken links **including `#anchors`**, scene
  block/state errors, and items without a page. GuideME itself only shows such errors as red text in the client.

## Pitfalls

- Inside `<GameScene>`, put every element and an annotation's text on its own lines; a one-line
  `<DiamondAnnotation ...>text</DiamondAnnotation>` becomes a paragraph and is silently skipped.
- Scene blocks face **north or east** (the default camera sees north, east and top); `facing=south` shows the back.
  Cables need `<ImportStructure>` with their masks in block entity data; a `<Block>` cable is a bare core. Put
  `DiamondAnnotation`s on a visible face, not a block's centre. (`guide_guideme_tags`, `guide_scene_cables` check it.)
- Structure files are generated (B09 item 15): add a `GuideScenes.Scene` in `storage/common/.../dev/GuideScenes.java`
  and run `MAXTECH_GUIDE_SCENES=write ./gradlew :storage:neoforge:runGameTestServer
  -Pgametest='maxtech_storage:guide_scene_build_*'` (Gradle passes the environment through to the GameTest server; the
  test writes into the checkout found by `SceneSites.repoRoot`). A block entity's structure `nbt` is its
  `getUpdateTag`, which `<ImportStructure>` feeds to `loadWithComponents`: right for blocks whose client data goes
  through the default `handleUpdateTag` (cables, drives); the controller overrides it, and its `loadSaved` ignores
  `glow_centre` once the block is in a level, so a multiblock controller's glow would not show in a scene. Each
  capture is byte-identical across runs (2026-10-19); a creative source under the controller, outside the box, keeps
  the scene online.
- Anchor slugs keep punctuation: "History: a, b" is `#history:-a,-b`. Rename a heading → fix its links (the test
  catches it) or add `<a name="..." />`.
- Recipe embeds only draw crafting, smelting, blasting and smithing; machine recipes need a recipe-type extension.
- A page whose `parent` isn't loaded drops out of the navigation (logged), it doesn't show at the top.
- `progress:` frontmatter was Lib's old guide; it does nothing in GuideME.

## Verified facts: GuideME 26.1.14-beta and 26.1.15-beta (verified in its sources jar, by compiling, by `runData` and by the `guide_*` GameTests with GuideME loaded; the guide screen not yet seen in a running client)

- **Official docs:** https://guideme.appliedenergistics.org/ (authoring, game scenes, supported Markdown, live preview, data-driven guides, integration incl. custom recipe types and website export, commands, search, translation). Read the docs page before guessing a tag. From the docs (2026-10-17, not all verified in our code):
  - Tags: `Row`/`Column`, `ItemLink`, `CommandLink`, `RecipeFor`/`RecipesFor`/`Recipe`, `ItemGrid`/`ItemIcon`, `CategoryIndex`, sub-page lists, `ItemImage`, `BlockImage` (`p:<name>` block state), `FloatingImage`, `Color`, `PlayerName`, `KeyBind`, MDX comments `{/* */}`.
  - Scenes: `GameScene` (`zoom`, `background`) with `ImportStructure` (`.nbt`/`.snbt`, `pos`), `Block`, `Entity`, `IsometricCamera` (`yaw`/`pitch`/`roll`), `RemoveBlocks`; annotations `BlockAnnotation`, `BoxAnnotation`, `LineAnnotation`, `DiamondAnnotation` (all take tooltip content), `BlockAnnotationTemplate` (after `ImportStructure`).
  - Live preview: `-Dguideme.<ns>.<path>.sources=<abs dir>` reloads pages on file change; `-Dguideme.showOnStartup=<ns>:<path>[!page.md#anchor]` opens the guide at launch (a dedicated `guide` run config is the documented pattern).
  - Commands: `/guideme open <target> <guide> [page#anchor]` (op), `/guidemec open <guide> [page]` (client), `/guideme give <target> <guide>`.

GuideME is back as Storage's required guide (MGM item 1, 26.1.15-beta, bundled jar-in-jar); it was removed in M7G chunk
5 and the facts below stayed valid in between.

- **Versions (checked 2026-10-16 in `maven-metadata.xml`)**: 26.1.15-beta is the newest GuideME for 26.1.2 (needs
  Minecraft `[26.1.2]`, NeoForge `[26.1.2.10-beta,26.2-)`); 26.2.x and 26.3.x are for later Minecraft. GuideME is also
  on **Maven Central** (same coordinates), so no extra repository is needed. Its Gradle module has no runtime
  dependencies. Licence LGPLv3, MIT and Apache 2.0 (POM), so bundling is allowed.
- **Jar-in-jar works** (MGM item 1): `implementation(libs.guideme)` plus MDG's `jarJar(libs.guideme) { version {
  strictly("[26.1.15-beta,26.2-)"); prefer(...) } }` puts `META-INF/jarjar/guideme-26.1.15-beta.jar` and a
  `metadata.json` (group, artifact, `range`, `artifactVersion`, `isObfuscated: false`) into the mod jar. A production
  NeoForge 26.1.2.109 server (installer `--installServer`) with only Lib's and Storage's jars in `mods/` lists
  `guideme (jar(mods/maxtech_storage-0.7.0.jar > guideme-26.1.15-beta.jar))` and starts. In dev runs the nested jar is
  not used; the `implementation` jar is the one mod, and with `-PwithAe2=true` AE2's POM asks for 26.1.14-beta, which
  Gradle resolves to 26.1.15-beta, so one GuideME loads.
- **Own guide item instead of `guideme:guide`** (AE2's pattern, `appeng.items.tools.GuideItem`): an ordinary item whose
  use calls `GuidesCommon.openGuide(player, guideId)` on the client. On a client the proxy opens the screen only for
  `Minecraft.getInstance().player`; on a server it sends `OpenGuideRequest` to a `ServerPlayer`. GuideME's javadoc on
  `GuideBuilder.itemSettings` says code-route guides should register their own item. Storage's common module cannot
  import GuideME, so `StorageGuide` opens the manual from `PlayerInteractEvent.RightClickItem` on the client side:
  `MultiPlayerGameMode.useItem` fires it (`CommonHooks.onItemRightClick`) before `itemStack.use` (patched sources).
- **Code route at client construction**: `Guide.builder(id).build()` in the `Dist.CLIENT` mod constructor, as AE2 does
  in `AppEngClient`'s constructor. The default folder is `guides/<ns>/<path>`, the start page `<ns>:index.md`; the
  builder adds the item and category indices and the open hotkey unless disabled.
- **Hot reload**: `GuideBuilder` reads `-Dguideme.<ns>.<path>.sources=<folder>` (pages directly in that folder, page id
  = relative path) and `.sourcesNamespace`; with it set, `MutableGuide` watches the folder and applies changed pages
  over the jar's pages each client tick.

- Artifact `org.appliedenergistics:guideme:26.1.14-beta` on the NeoForged maven (catalog `libs.guideme`). The Gradle cache holds only the jar; fetch the sources with `curl -O https://maven.neoforged.net/releases/org/appliedenergistics/guideme/26.1.14-beta/guideme-26.1.14-beta-sources.jar` into a scratch folder. The shaded Markdown libraries (`guideme.libs.mdast`, `.micromark`, `.unist`) have no sources there; read them with `javap -cp <jar>`. Mod id `guideme`, side BOTH, needs NeoForge `[26.1.2.10-beta,26.2-)`.
- **Declaring a guide without code**: `assets/<ns>/guideme_guides/<path>.json` declares the guide `<ns>:<path>` (`GuideReloadListener`, `DataDrivenGuide`): optional `item_settings` (`display_name` and `tooltip_lines` as text components, so `{"translate": ...}` works, and `model`), `default_language` (`en_us`) and `custom_colors`. It is read on the **client's** resource reload only; a server never knows the guide. The code route is `Guide.builder(id)...build()` (registers it unless `.register(false)`); both routes read the same system properties.
- **Pages**: every `.md` under `assets/*/guides/<guide ns>/<guide path>/` in **any** namespace belongs to the guide, and its page id is `<resource namespace>:<path below that folder>`. The start page is `<guide ns>:index.md`. Files under `_<language>/` are translations. Other files there are assets: a page's `src="structures/x.snbt"` resolves against the page id (`IdUtils.resolveLink`), then loads `assets/<ns>/guides/<guide ns>/<guide path>/structures/x.snbt`.
- **Frontmatter**: `navigation` with `title` (required), `parent` (a page id; no namespace means the page's own), `position` (int) and `icon` (item id); no parent makes a top-level entry. `item_ids` (list) feeds the item index: holding the open-guide key (`key.guideme.guide`, G by default, only in GUIs) over an item's tooltip opens its page. One page per item (`UniqueIndex`). `categories` feeds `<CategoryIndex>`.
- **Default tags** (`DefaultExtensions`): `ItemLink`, `ItemImage`, `BlockImage`, `RecipeFor`/`RecipesFor`/`Recipe`, `ItemGrid`, `CategoryIndex`, `SubPages` (`id=""` lists the top-level entries, no `id` the page's children), `Row`/`Column`, `GameScene` (children `ImportStructure`, `Block`, `IsometricCamera`, `Entity`, annotations), `KeyBind`, `a`, `div`, `br`, `Color`, `FloatingImage`, `CommandLink`, `PlayerName`. Recipe embeds draw only crafting, smelting, blasting and smithing recipes, and GuideME sends only those types to clients (`OnDatapackSyncEvent.sendRecipes`); a machine recipe needs a `RecipeTypeMappingSupplier` extension. `ImportStructure` reads `.snbt` (`NbtUtils.snbtToStructure`) or compressed `.nbt`.
- **Tag extensions** (26.1.15-beta sources, MGM item 2, compiled and run in `guide_guideme_tags`): a tag is a
  `guideme.compiler.TagCompiler` (`getTagNames()`, `compileFlowContext`/`compileBlockContext`); for inline text extend
  `guideme.compiler.tags.FlowTagCompiler` and implement `compile(PageCompiler, LytFlowParent, MdxJsxElementFields)`
  (block use is wrapped in a paragraph). Register with `GuideBuilder.extension(TagCompiler.EXTENSION_POINT, tag)`;
  the builder refuses the same instance twice. `el.getAttributeString(name, fallback)`; `parent.appendError(compiler,
  text, el)` draws red text; `LytTooltipSpan` (`modifyStyle(b -> b.bold(true))`, `appendComponent`, `setTooltip(new
  TextTooltip(List<Component>))`) is a hoverable span. `MdxJsxElementFields.name()` is `@Nullable` (fragments).
- **Tables**: `PageCompiler.parse` enables GFM tables (`GfmTableSyntax`); `compileTable` makes a `LytTable` with a
  bold first row and column alignment, and compiles each cell in block context, so tags such as `<ItemLink>` and
  custom flow tags work inside cells.
- **Scenes**: `<GameScene zoom="3" interactive>` (a bare attribute is `true`; `{true}` also works) holds scene elements
  only: `SceneTagCompiler` iterates its children and **silently skips** anything that is not an MDX element, and a
  one-line `<DiamondAnnotation ...>text</DiamondAnnotation>` is parsed as a paragraph, so it vanishes; write each
  element and an annotation's text on their own lines. `<Block id x y z p:<property>="value" />` (id without namespace
  = the page's), `<ImportStructure src="x.nbt" pos="..." />` (vanilla structure NBT through `StructureTemplate.load`),
  annotations `<BlockAnnotation x y z color>` (outline box), `<BoxAnnotation>`, `<LineAnnotation>`,
  `<DiamondAnnotation pos="x y z" color="#AARRGGBB">` (a marker; the element's content is its hover tooltip),
  `<BlockAnnotationTemplate>`, `<RemoveBlocks>`, `<Entity>`, `<IsometricCamera>`.
- **Scene level, camera and block entities** (26.1.15-beta sources, 2026-10-17; `milestones/MGM/scene-fixes.md` (milestone notes)):
  - Camera: `CameraSettings` defaults to `ISOMETRIC_NORTH_EAST` = `setIsometricYawPitchRoll(225, 30, 0)` (view matrix
    `rotateZ(roll)·rotateX(pitch)·rotateY(yaw)`, ortho); it shows the **north, east and top** faces.
    `ISOMETRIC_NORTH_WEST` is yaw 135.
  - `GuidebookLevel` is a client-side `Level` (`isClientSide` true) that is never ticked, so NeoForge's
    `BlockEntity#onLoad` (run from the level's fresh block entity list) never runs and no network logic runs. Its
    `getModelData(pos)` calls the block entity's `getModelData()` directly (no refresh needed);
    `sendBlockUpdated` does nothing; it has its own `ModelDataManager`, so `requestModelDataUpdate()` is safe.
  - `<ImportStructure>` places through `StructureTemplate.placeInWorld(FakeForwardingServerLevel, ..., flags 0)`;
    a block with `nbt` gets `loadWithComponents` on the new block entity (no `id` needed in the `nbt`). `<Block>` only
    calls `setBlockAndUpdate` with the state: no block entity data. So anything whose look comes from block entity
    data (Lib conduit arms, drive cell lights) must come from a structure with that data saved.
  - `DiamondAnnotation` is an `OverlayAnnotation`: drawn on top at `worldToScreen(pos)`, never hidden by blocks.
- **Server-side page check**: `PageCompiler.parse(sourcePack, language, id, text)` (the three-argument form is
  deprecated for removal) and `DefaultExtensions.addAll(ExtensionCollection.builder(), Set.of())` (internal package)
  both run on a dedicated GameTest server; constructing the default tag compilers touches no client class. Walk
  `ParsedGuidePage.getAstRoot()` through `MdAstParent.children()` and `MdxJsxElementFields.children()`.
- **Parsing pages on a server** (26.1.15-beta, MGM item 3, the `guide_*` GameTests on the dedicated GameTest server):
  `guideme.compiler.PageCompiler.parse(String sourcePack, String language, Identifier id, String text)` needs no
  client and returns a `ParsedGuidePage`: `getFrontmatter()` (`navigationEntry()` is a nullable
  `FrontmatterNavigation(title, parent, position, iconItemId, iconComponents)` with ids already resolved in the page's
  namespace; `additionalProperties()` holds the rest, `item_ids` as a `List`) and `getAstRoot()`. Walk it with
  `MdAstNode.visit(MdAstVisitor)` (`beforeNode` returns `MdAstVisitor.Result.CONTINUE`); tags are
  `MdxJsxElementFields` (`name()`, `getAttributeString(name, fallback)`), links `MdAstLink.url`. A parse error does not
  throw: the page becomes a heading `PARSING ERROR` and a paragraph. Storage's gametest source set needs
  `"gametestCompileOnly"(libs.guideme)`, since `maxtech.neoforge` extends only `compileOnly` into it.
- **ItemLink needs a page**: an `<ItemLink>` resolves through the item index (`item_ids`); with no page it draws red "No page found" (and the website export drops the item). `guide_pages` checks this.
- **Link anchors** (26.1.15-beta sources, `AnchorIndexer`, `LinkParser`): `page.md#frag` matches a heading whose text,
  lower-cased, trimmed and with whitespace runs turned into `-`, equals `frag` (punctuation stays: "History: a, b"
  is `history:-a,-b`), or an `<a name="frag" />` anchor. `#frag` alone links within the page.
- **Errors**: a missing item, recipe, page or structure is drawn as red text on the page (`appendError`), not logged; only Markdown and frontmatter parse errors reach the log. GuideME's own validation is client-only (`-Dguideme.validateAtStartup=<guide ids>`, on the title screen). Hot reload in dev: `-Dguideme.<ns>.<path>.sources=<folder>`.
- **Opening**: GuideME's generic item `guideme:guide` with the data component `guideme:guide_id` (`Guides.createGuideItem(id)`); no creative tab lists it, and `/guideme give|open` need permission level 2. `Guides.createGuideItem` fails in datagen ("Components not bound yet": no `ItemStack` can be built there); build the recipe result as `new ItemStackTemplate(GuideME.GUIDE_ITEM.get(), DataComponentPatch.builder().set(GuideME.GUIDE_ID_COMPONENT, id).build())` (both public on the internal `guideme.internal.GuideME`).
- A recipe saved through `output.withConditions(new ModLoadedCondition("guideme"))` gets the `neoforge:conditions` block on the recipe **and** on its generated unlock advancement (verified in the `runData` output), so neither fails to load without the mod.
- **Merging pages from several mods** (26.1.14-beta sources, `GuideReloadListener`, `NavigationTree`, `DefaultExtensions`): GuideME has no include, fragment or "if mod loaded" tag, so one page cannot pull text from another mod's resources. Pages are gathered with the resource manager's `listResources`, so the same id in two packs is an override, not a merge; the same path in two namespaces (`maxtech_lib:cables.md`, `maxtech_storage:cables.md`) is two pages. A page whose `parent` is not loaded is **left out of the navigation** (logged as "had unknown navigation parent"), not shown at the top; a parent cycle is logged and those pages are left out too. `<SubPages />` without `id` lists the page's children from every loaded mod (an empty list when there are none), which is how a lower mod's page shows a higher addon's sub-page without linking to it; a plain Markdown link to an absent mod's page renders as an error. So an addon extends a concept page by shipping its own page with `parent: <lowermod>:<page>.md`.
- **Website export in 26.1.15-beta** (sources jar, the published jar through `javap`, and a full export and site build
  on Windows, 2026-10-17; how-to and gaps in `milestones/MGM/web-export.md` (milestone notes)). The website docs describe a newer
  GuideME. Export: system properties `guideme.exportOnStartupAndExit=<guide id>`,
  `guideme.exportDestination.<ns>.<path>=<dir>` (required) and optional `guideme.exportModVersion.<ns>.<path>`
  (`SiteExportOnStartup`, `SiteExporter`); it runs after the first client resource load and stops the game; the window
  stays black meanwhile. Generator: `guideme.internal.web.StaticSiteGenerator` (package-private, public `main`; runs
  from `JavaExec` on the main runtime classpath) with only `--data`, `--output`, `--web-assets <dir>` (files there
  override the default assets; `templates/layout.html` overrides the page template, placeholders `{{PAGE_TITLE}}`
  (HTML), `{{PAGE_TITLE_TEXT}}`, `{{PAGE_CONTENT}}`, `{{RELATIVE_PATH_TO_ROOT}}`, `{{GUIDE_TITLE}}`, `{{GUIDE_NAVBAR}}`,
  `{{FOOTER}}`) and `--change-version-url`. Not in 26.1.15-beta: `guideme.web.WebSiteGenerator`, `WebSiteServer`,
  `CustomElementWebRenderer`, `PageExportProcessor`, `--title/--logo/--favicon/--clean/--page-subdirectories`.
  `ExportedGuide.getGuideTitle()` returns "Applied Energistics 2" and the text title says "AE2 Players Guide for ...".
  - The web side draws only `BlockImage`, `CategoryIndex`, `SubPages`, `Column`, `ItemLink`, `ItemImage`, `ItemIcon`,
    `ItemGrid`, `Recipe`, `RecipeFor`, `RecipesFor`, `Row`, `GameScene` (`WebPageCompiler.compileCustomElement`);
    lower-case tags are written as HTML (a flow-level one loses its attributes); anything else is an "Unhandled custom
    element" error. The one hook for other tags is `guideme.siteexport.ExportPostProcessor`
    (`postProcess(JsonObject root)`, `GuideBuilder.extension(ExportPostProcessor.EXTENSION_POINT, ...)`), which edits
    the export JSON in the game. MDX nodes there are `{"type":"mdxJsxTextElement"|"mdxJsxFlowElement","name",
    "attributes":[{"type":"mdxJsxAttribute","name","value"}],"children"}`; `strong`, `text` and `paragraph` nodes read
    back through `MdAstNode.fromJson`.
  - Bugs (worked around in Storage's `GuideWebExport`): a bare attribute (`<GameScene interactive>`) is exported
    without `value` and `MdxJsxAttribute.readJson` then throws; the published jar's local records for the
    `DiamondAnnotation`, `BoxAnnotation`, `LineAnnotation` and `SubPages` attributes have **no constructors**, so the
    generator fails on any page using them; same-page `#anchor` links are "Page does not exist" on the site.
  - The export writes WebP through FFmpeg (`org.bytedeco:ffmpeg`, GuideME builds against `6.0-1.5.9`), which GuideME
    does not ship: without it every icon fails with `NoClassDefFoundError: org/bytedeco/javacpp/Pointer`, the event
    bus swallows it and the game still exits 0. Add `ffmpeg`, `ffmpeg:<platform>` and `javacpp:1.5.9:<platform>`.
  - Items reach the export only when a compiled page references them. An `<ItemLink>` to an own-mod item that no page
    lists in `item_ids` is an in-game error ("No page found for item", unless `optional`) and is missing on the site.
    A scene `<Block>` constructs its block entity at the title screen, where no server config is loaded.

