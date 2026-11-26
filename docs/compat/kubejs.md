# KubeJS

Storage ships a KubeJS plugin (`compat.kubejs`). KubeJS is optional: Storage loads the plugin only when KubeJS is
installed (it is found through `kubejs.plugins.txt`, which nothing else reads). Tested with KubeJS 8.0.6 on
Minecraft 26.1.2; the mods.toml range is `[26.1.2-8.0.6,)`.

## Machine recipes

The lithograph, fabricator and assembler each have a recipe type with a schema, so scripts can add, replace and remove
their recipes. The keys follow the JSON fields: `outputs`, `inputs`, `energy_per_tick`, `duration`, and the optional
`mode` and `research` (set with builder methods). The serializer validates the result like a datapack recipe.

```js
// kubejs/server_scripts/maxtech.js
ServerEvents.recipes(event => {
  event.recipes.maxtech_storage.lithograph(
    ['minecraft:diamond'],             // outputs
    ['minecraft:quartz'],                  // inputs
    40,                                    // energy_per_tick (FE)
    100                                    // duration (ticks)
  ).mode('print').id('mypack:lithograph/quartz')

  event.remove({ type: 'maxtech_storage:assembler', id: 'maxtech_storage:assembler/advanced_io_processor' })
})
```

The fuel generator has no recipe type (it burns vanilla fuel). The `component_copying` crafting recipe is a special
shapeless recipe that copies data from an input to its output; it needs no schema.

## Logic events

The plugin also registers the `MaxtechLogicEvents` group for script node types; see the guide's logic pages.

## Trying it in the dev client

`./gradlew :storage:neoforge:runClient -PwithKubeJs=true` (`-PwithKubeJS=true` works too) adds KubeJS, Rhino and Better
Advanced Tooltips to the dev runs. They are never bundled, and GameTests and datagen never load them.
