# `lib.api.recipe`

Part of the [Lib api inventory](../lib-1.0.md); labels as defined there.

| Package / type | Purpose | Label |
|---|---|---|
| **`lib.api.recipe`** | Machine recipe types, recipes, lookup and datagen; item inputs and outputs shared with the recipe viewer layout and KubeJS. | stable |
| `ItemInput` | Ingredient, count, consumed or catalyst; codecs. | stable |
| `ItemOutput` | Output stack and chance in hundredths of a percent; codecs; `roll` rolls each copy on its own. | stable |
| `MachineRecipeType` | `define(modId, name, shape)` registers a machine's `RecipeType`, serializer (one JSON codec, one stream codec) and recipe book category through the mod's registrars; the codec refuses recipes that do not fit the shape (ADR-0019, Lib 0.15.0). | experimental |
| `MachineRecipeShape` | Input, catalyst and output slots, input and output tanks, and mode ids of a machine; `check` says why a recipe does not fit. | experimental |
| `MachineRecipe` | Item inputs, tank inputs of any kind, item outputs with chance, tank outputs, `energy_per_tick`, `duration`, optional `mode`; `matches` fills a `MachineRecipePlan`. Unknown items, fluids or kinds fail decoding, so the game logs and skips the recipe. | experimental |
| `KindInput`, `KindOutput` | A tank input by exact resource (components included) or by kind and tag, and a tank output by exact resource; amounts in the kind's unit. | experimental |
| `MachineRecipeInput`, `MachineRecipePlan` | A machine's live view of its input and catalyst slots, input tanks and mode; where a matched run takes each input from. | experimental |
| `MachineRecipeLookup` | One per machine: finds the matching recipe, checking the last one first without allocating; a datapack reload forgets it. | experimental |
| `MachineRecipeBuilder` | Datagen: `c:` tag and item inputs, catalysts, fluid tag or exact tank inputs, outputs with chance, tank outputs; `build` checks the shape. | experimental |
