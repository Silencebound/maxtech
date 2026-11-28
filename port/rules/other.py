"""Rules of work package `other`: signatures, overrides, recipes, components and the rest (port/errors.md, "Ownership").

Only this package edits this module. Scope each rule with files= to the files the package owns (port/ownership.txt);
convert.py warns when a rule here changes a file another package owns.

Two rule kinds beyond the framework's: `_Edit` replaces one exact piece of text (silently unchanged when main's text no
longer has it, which the compile then shows), `_Call` rewrites every call of one method with a balanced argument list.
"""

from __future__ import annotations

import re

from framework import ImportMove, Regex, Rename, Transform  # noqa: F401

LIB = "lib/common/src/main/java/dev/silencebound/maxtech/lib/"
API = "lib/common/src/api/java/dev/silencebound/maxtech/lib/api/"
NEO = "lib/neoforge/src/main/java/dev/silencebound/maxtech/lib/neoforge/"
NEO_API = "lib/neoforge/src/api/java/dev/silencebound/maxtech/lib/neoforge/api/"
SHIM = "dev.silencebound.maxtech.lib.shim.misc"


def edit(*pairs: tuple[str, str], files: tuple[str, ...], name: str) -> Transform:
    """Replaces each `old` with `new` (all occurrences); a pair whose `old` is gone is skipped."""

    def run(text: str, rel: str) -> str:
        for old, new in pairs:
            text = text.replace(old, new)
        return text

    return Transform(run, name, files=files)


def call(name: str, fn, *, files: tuple[str, ...]) -> Transform:
    """Rewrites every `<name>(args)` to fn(args); args is the balanced text between the parentheses."""

    def run(text: str, rel: str) -> str:
        out, i = [], 0
        while True:
            at = text.find(name + "(", i)
            if at < 0:
                out.append(text[i:])
                return "".join(out)
            j, depth = at + len(name) + 1, 1
            while depth:
                depth += {"(": 1, ")": -1}.get(text[j], 0)
                j += 1
            out.append(text[i:at])
            out.append(fn(text[at + len(name) + 1:j - 1]))
            i = j

    return Transform(run, f"call {name}", files=files)


def drop_import(cls: str, files: tuple[str, ...]) -> Regex:
    return Regex(r"^import " + cls.replace(".", r"\.") + r";\n", "", flags=re.MULTILINE, files=files)


# --- ItemStackTemplate: 26.1's net.minecraft.world.item.ItemStackTemplate is a shim here (misc/ItemStackTemplate) -----
TEMPLATE_USERS = (
    API + "recipe/ItemOutput.java",
    API + "recipe/MachineRecipeBuilder.java",
    LIB + "facade/FacadeRecipe.java",
    NEO + "compat/kubejs/MachineRecipeComponents.java",
)

# --- Item and block properties: 1.21.1 takes the registry id from the registration, not from Properties ----------------
PROPERTIES_USERS = (
    LIB + "content/BlockBuilder.java",
    LIB + "content/ItemBuilder.java",
    LIB + "content/ConduitBuilder.java",
    LIB + "facade/FacadeContent.java",
)

# --- Recipes --------------------------------------------------------------------------------------------------------
_HOLDER = "net.minecraft.core.HolderLookup.Provider"


def _machine_recipe(text: str, rel: str) -> str:
    text = text.replace("import net.minecraft.world.item.crafting.PlacementInfo;\n", "")
    text = text.replace("import net.minecraft.world.item.crafting.RecipeBookCategory;\n", "")
    text = text.replace(
        """    @Override
    public ItemStack assemble(MachineRecipeInput input) {
        return outputs.isEmpty() ? ItemStack.EMPTY : outputs.getFirst().item().create();
    }
""",
        f"""    @Override
    public ItemStack assemble(MachineRecipeInput input, {_HOLDER} registries) {{
        return getResultItem(registries);
    }}

    @Override
    public ItemStack getResultItem({_HOLDER} registries) {{
        return outputs.isEmpty() ? ItemStack.EMPTY : outputs.getFirst().item().create();
    }}

    /** A machine recipe has no grid. */
    @Override
    public boolean canCraftInDimensions(int width, int height) {{
        return false;
    }}
""")
    text = text.replace("    public String group() {", "    public String getGroup() {")
    text = text.replace(
        """    @Override
    public PlacementInfo placementInfo() {
        return PlacementInfo.NOT_PLACEABLE;
    }

    @Override
    public RecipeBookCategory recipeBookCategory() {
        return type.bookCategory();
    }

""", "")
    return text


def _machine_recipe_type(text: str, rel: str) -> str:
    text = text.replace("import net.minecraft.world.item.crafting.RecipeBookCategory;\n", "")
    text = text.replace(
        "    private final RegistryEntry<RecipeBookCategory, RecipeBookCategory> bookCategory;\n", "")
    text = text.replace(
        """        this.bookCategory = Registrars.create(modId, Registries.RECIPE_BOOK_CATEGORY).register(name,
                RecipeBookCategory::new);
""", "")
    text = text.replace(
        """    /** The registered recipe book category every recipe of the type reports; none shows in the recipe book. */
    public RecipeBookCategory bookCategory() {
        return bookCategory.get();
    }

""", "")
    text = text.replace("StreamCodec.composite(", f"{SHIM}.StreamCodecs.composite(")
    return text


def _facade_recipe(text: str, rel: str) -> str:
    text = text.replace(
        """    public FacadeRecipe(Ingredient binder, ItemStackTemplate result) {
""",
        """    public FacadeRecipe(Ingredient binder, ItemStackTemplate result) {
        super(net.minecraft.world.item.crafting.CraftingBookCategory.MISC);
""")
    text = text.replace(
        "    public ItemStack assemble(CraftingInput input) {",
        f"    public ItemStack assemble(CraftingInput input, {_HOLDER} registries) {{")
    text = text.replace(
        """    @Override
    public RecipeSerializer<FacadeRecipe> getSerializer() {""",
        """    /** Two ingredients: the binder and the block. */
    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<FacadeRecipe> getSerializer() {""")
    return text


RECIPE_RULES = [
    Transform(_machine_recipe, "MachineRecipe", files=(API + "recipe/MachineRecipe.java",)),
    Transform(_machine_recipe_type, "MachineRecipeType", files=(API + "recipe/MachineRecipeType.java",)),
    Transform(_facade_recipe, "FacadeRecipe", files=(LIB + "facade/FacadeRecipe.java",)),
    # RecipeSerializer is an interface on 1.21.1.
    Regex(r"new RecipeSerializer<>\(", f"{SHIM}.RecipeSerializers.of(",
          files=(API + "recipe/MachineRecipeType.java", LIB + "facade/FacadeRecipe.java")),
    # Datagen builders and ingredients take tag keys, not registry lookups.
    Regex(r"Ingredient\.of\(items\.getOrThrow\((\w+)\)\)", r"Ingredient.of(\1)",
          files=(API + "recipe/MachineRecipeBuilder.java", LIB + "content/RecipeContext.java")),
    Regex(r"output\.accept\(ResourceKey\.create\(Registries\.RECIPE, id\), build\(\), null\)",
          "output.accept(id, build(), null)", files=(API + "recipe/MachineRecipeBuilder.java",)),
    Regex(r"(Shaped|Shapeless)RecipeBuilder\.(shaped|shapeless)\(items, ", r"\1RecipeBuilder.\2(",
          files=(LIB + "content/RecipeContext.java",)),
    Regex(r"\.of\(items, (tag|item)\)", r".of(\1)", files=(LIB + "content/RecipeContext.java",)),
]

# --- Conduit block ---------------------------------------------------------------------------------------------------


def _use_item_on(text: str, rel: str) -> str:
    start = text.find("    protected InteractionResult useItemOn(")
    if start < 0:
        return text
    end = text.find("\n    }\n", start) + 7
    body = text[start:end]
    body = body.replace("protected InteractionResult useItemOn(", "protected ItemInteractionResult useItemOn(")
    body = body.replace("DyeColor dye = stack.get(DataComponents.DYE);", "DyeColor dye = DyeColor.getColor(stack);")
    body = body.replace("InteractionResult.TRY_WITH_EMPTY_HAND", "ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION")
    body = body.replace("InteractionResult.SUCCESS", "ItemInteractionResult.SUCCESS")
    text = text[:start] + body + text[end:]
    return text.replace("import net.minecraft.world.InteractionResult;\n",
                        "import net.minecraft.world.InteractionResult;\nimport net.minecraft.world.ItemInteractionResult;\n")


CONDUIT_BLOCK = LIB + "conduit/ConduitBlock.java"

CONDUIT_RULES = [
    drop_import("net.minecraft.world.level.redstone.Orientation", (CONDUIT_BLOCK,)),
    call("player.sendOverlayMessage", lambda args: f"player.displayClientMessage({args}, true)",
         files=(CONDUIT_BLOCK,)),
    # 26.1 passes the tool stack to onDestroyedByPlayer; 1.21.1's hook has no such parameter and the code ignores it.
    edit(("BlockPos pos, Player player, ItemStack toolStack,\n            boolean willHarvest, FluidState fluid) {",
          "BlockPos pos, Player player,\n            boolean willHarvest, FluidState fluid) {"),
         ("super.onDestroyedByPlayer(state, level, pos, player, toolStack, willHarvest, fluid)",
          "super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid)"),
         ("@Nullable Orientation orientation, boolean movedByPiston", "BlockPos fromPos, boolean movedByPiston"),
         ("ResourceKey<LootTable> key = getLootTable().orElse(null);", "ResourceKey<LootTable> key = getLootTable();"),
         ("protected int getLightDampening(BlockState state) {\n        return state.getValue(FACADE) ? 15 : super.getLightDampening(state);",
          "protected int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {\n"
          "        return state.getValue(FACADE) ? 15 : super.getLightBlock(state, level, pos);"),
         ("protected boolean propagatesSkylightDown(BlockState state) {\n        return !state.getValue(FACADE) && super.propagatesSkylightDown(state);",
          "protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {\n"
          "        return !state.getValue(FACADE) && super.propagatesSkylightDown(state, level, pos);"),
         files=(CONDUIT_BLOCK,), name="ConduitBlock signatures"),
    Transform(_use_item_on, "ConduitBlock.useItemOn", files=(CONDUIT_BLOCK,)),
]

# --- Datagen --------------------------------------------------------------------------------------------------------
# 1.21.1's item tag provider takes the block tag lookup and an ExistingFileHelper (nullable); Lib reads no block tags.
_NO_BLOCK_TAGS = "CompletableFuture.completedFuture(net.minecraft.data.tags.TagsProvider.TagLookup.empty())"
DATAGEN_RULES = [
    ImportMove("net.neoforged.neoforge.common.data.ItemTagsProvider", "net.minecraft.data.tags.ItemTagsProvider",
               files=(NEO + "datagen/LibItemTagsProvider.java", NEO + "datagen/ContentItemTagsProvider.java")),
    edit(("super(output, registries, MaxtechLib.MOD_ID);",
          f"super(output, registries, {_NO_BLOCK_TAGS}, MaxtechLib.MOD_ID, null);"),
         files=(NEO + "datagen/LibItemTagsProvider.java",), name="LibItemTagsProvider"),
    edit(("super(output, registries, content.modId());",
          f"super(output, registries, {_NO_BLOCK_TAGS}, content.modId(), null);"),
         files=(NEO + "datagen/ContentItemTagsProvider.java",), name="ContentItemTagsProvider"),
    # The recipe provider is the DataProvider itself (no Runner around it).
    Regex(r"RecipeProvider\.Runner", "RecipeProvider", files=(NEO + "datagen/ContentIndexProvider.java",)),
]

# --- GuideME 21.1 has no website export hook (guideme.siteexport.ExportPostProcessor is 26.1 only) -----------------
GUIDE_RULES = [
    drop_import("guideme.siteexport.ExportPostProcessor", (NEO + "guide/LibGuides.java",)),
    edit(("""        builder.extension(ExportPostProcessor.EXTENSION_POINT,
                new GuideWebExport(id.getNamespace(), LibGuides::defaultKeyName));
""", ""), files=(NEO + "guide/LibGuides.java",), name="LibGuides export hook"),
]


# --- Tooltips: 1.21.1's appendHoverText takes a List, not a TooltipDisplay and a Consumer ---------------------------
TOOLTIP_USERS = (LIB + "facade/FacadeItem.java", LIB + "info/ScannerItem.java")
TOOLTIP_RULES = [
    edit(("TooltipContext context, TooltipDisplay display,\n            Consumer<Component> builder, TooltipFlag flag)",
          "TooltipContext context,\n            java.util.List<Component> builder, TooltipFlag flag)"),
         files=TOOLTIP_USERS, name="appendHoverText"),
    Regex(r"builder\.accept\(", "builder.add(", files=TOOLTIP_USERS),
    drop_import("net.minecraft.world.item.component.TooltipDisplay", TOOLTIP_USERS),
    # Cooldowns are per item on 1.21.1; 26.1 keys them by the stack's cooldown group, which this scanner leaves unset.
    Regex(r"getCooldowns\(\)\.(isOnCooldown|addCooldown)\(stack\b", r"getCooldowns().\1(stack.getItem()",
          files=(LIB + "info/ScannerItem.java",)),
]

RULES = [
    # --- misc: types 1.21.1 lacks ------------------------------------------------------------------------------------
    *[ImportMove("net.minecraft.world.item.ItemStackTemplate", f"{SHIM}.ItemStackTemplate", files=(f,))
      for f in TEMPLATE_USERS],
    Regex(r"ExtraCodecs\.STRING_RGB_COLOR", f"{SHIM}.RgbCodecs.STRING_RGB_COLOR",
          files=(API + "conduit/ConduitLook.java",)),
    # --- properties ----------------------------------------------------------------------------------------------------
    Regex(r"\.setId\(ResourceKey\.create\(Registries\.(?:ITEM|BLOCK), id\)\)", "", files=PROPERTIES_USERS),
    Regex(r"\.useBlockDescriptionPrefix\(\)", "", files=PROPERTIES_USERS),
    # --- registries ------------------------------------------------------------------------------------------------------
    Regex(r"BuiltInRegistries\.REGISTRY\.getValue\(", "BuiltInRegistries.REGISTRY.get(",
          files=(API + "conduit/ConduitTypes.java",)),
    Regex(r"holder\.id\(\)\.location\(\)", "holder.id()", files=(LIB + "content/ContentIndex.java",)),
    Regex(r"registry\.get\(id\)", "registry.getHolder(id)", files=(LIB + "content/ContentIndex.java",)),
    edit(("return new BlockEntityType<>(ConduitBlockEntity::new, conduits);",
          "return BlockEntityType.Builder.of(ConduitBlockEntity::new, conduits.toArray(new Block[0])).build(null);"),
         files=(LIB + "registry/ModBlockEntities.java",), name="ModBlockEntities"),
    # --- small API differences -------------------------------------------------------------------------------------------
    Regex(r"ChunkPos\.pack\(", "ChunkPos.asLong(", files=(LIB + "conduit/ConduitAttachments.java",)),
    Regex(r"\.getSelectedSlot\(\)", ".selected", files=(API + "carried/CarriedItems.java",)),
    Regex(r"\.getGameProfile\(\)\.name\(\)", ".getGameProfile().getName()", files=(API + "security/OwnerRecord.java",)),
    Regex(r"player\.permissions\(\)\.hasPermission\(Permissions\.COMMANDS_GAMEMASTER\)", "player.hasPermissions(2)",
          files=(API + "security/OwnerSecurity.java",)),
    drop_import("net.minecraft.server.permissions.Permissions", (API + "security/OwnerSecurity.java",)),
    Regex(r"state\.isSolidRender\(\)",
          "state.isSolidRender(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO)",
          files=(LIB + "facade/Facades.java",)),
    Regex(r"ItemStack\.OPTIONAL_UNTRUSTED_STREAM_CODEC", "ItemStack.OPTIONAL_STREAM_CODEC",
          files=(LIB + "gui/transfer/CraftingGridMenus.java",)),
    drop_import("net.neoforged.neoforge.client.network.ClientPacketDistributor",
                (NEO + "platform/NeoForgeNetworkHelper.java",)),
    Regex(r"ClientPacketDistributor\.", "PacketDistributor.", files=(NEO + "platform/NeoForgeNetworkHelper.java",)),
    # --- recipes, conduit block ----------------------------------------------------------------------------------------
    *RECIPE_RULES,
    *CONDUIT_RULES,
    *DATAGEN_RULES,
    *GUIDE_RULES,
    *TOOLTIP_RULES,
]
