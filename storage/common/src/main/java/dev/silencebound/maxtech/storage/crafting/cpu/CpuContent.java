package dev.silencebound.maxtech.storage.crafting.cpu;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.content.ContentRecipe;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.cpu.client.JobToasts;
import dev.silencebound.maxtech.storage.crafting.job.CraftingOutputs;
import dev.silencebound.maxtech.storage.crafting.job.JobNotice;
import dev.silencebound.maxtech.storage.crafting.job.JobToastPayload;
import dev.silencebound.maxtech.storage.crafting.provider.CraftingDeliveries;
import dev.silencebound.maxtech.storage.crafting.job.QueueBackgroundCrafting;
import dev.silencebound.maxtech.storage.crafting.stock.BackgroundCrafts;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;

/**
 * The crafting CPU (M7 C3, C4): three tiered cores, the storage, lane and cache part blocks, the core's block entity
 * type, info and toast texts, recipes and the finished-job toast payload. Placeholder textures until the owner picks
 * the look.
 */
public final class CpuContent {
    public static final TagKey<Item> CORE_1_TAG = MaterialTags.component("crafting_cpu_tier_1");
    public static final TagKey<Item> CORE_2_TAG = MaterialTags.component("crafting_cpu_tier_2");

    public static final BlockEntry<CpuCoreBlock, CpuCoreItem> CORE_1 = core(CpuTier.TIER_1, "I",
            // Tantalite tier, built from tier-2 parts.
            (recipes,
                    item) -> recipes.shaped(RecipeCategory.MISC, item).pattern("PTP").pattern("LCL").pattern("PTP")
                            .define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('T', MaterialTags.TANTALUM_INGOT)
                            .define('L', MaterialTags.component("advanced_logic_processor"))
                            .define('C', MaterialTags.component("tantalum_capacitor"))
                            .unlockedBy("has_advanced_logic_processor",
                                    recipes.has(MaterialTags.component("advanced_logic_processor")))
                            .save(recipes.output()));
    public static final BlockEntry<CpuCoreBlock, CpuCoreItem> CORE_2 = core(CpuTier.TIER_2, "II",
            (recipes, item) -> recipes.shaped(RecipeCategory.MISC, item).pattern("DED").pattern("ECE").pattern("DED")
                    .define('D', MaterialTags.DATA_CRYSTAL).define('E', MaterialTags.component("elite_logic_processor"))
                    .define('C', CORE_1_TAG).unlockedBy("has_crafting_cpu", recipes.has(CORE_1_TAG))
                    .save(recipes.output()));
    public static final BlockEntry<CpuCoreBlock, CpuCoreItem> CORE_3 = core(CpuTier.TIER_3, "III",
            (recipes, item) -> recipes.shaped(RecipeCategory.MISC, item).pattern("VLV").pattern("ECE").pattern("VLV")
                    .define('V', MaterialTags.VOID_CRYSTAL).define('L', MaterialTags.component("void_lattice"))
                    .define('E', MaterialTags.component("elite_memory_processor")).define('C', CORE_2_TAG)
                    .unlockedBy("has_crafting_cpu", recipes.has(CORE_2_TAG)).save(recipes.output()));

    public static final BlockEntry<CpuPartBlock, BlockItem> STORAGE = part(CpuPart.STORAGE, "Crafting CPU Storage",
            "advanced_memory_processor");
    public static final BlockEntry<CpuPartBlock, BlockItem> LANE = part(CpuPart.LANE, "Crafting CPU Lane",
            "advanced_logic_processor");
    public static final BlockEntry<CpuPartBlock, BlockItem> CACHE = part(CpuPart.CACHE, "Crafting CPU Cache",
            "advanced_signal_processor");

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<CpuCoreBlockEntity>> CORE_BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("crafting_cpu", () -> new BlockEntityType<>(CpuCoreBlockEntity::new,
                    Set.of(CORE_1.get(), CORE_2.get(), CORE_3.get())));

    private static final String INFO = "info." + MaxtechStorage.MOD_ID + ".crafting_cpu.";
    public static final String INFO_STATS = INFO + "stats";
    public static final String INFO_CONFLICT = INFO + "conflict";
    public static final String INFO_IDLE = INFO + "idle";
    public static final String INFO_RUNNING = INFO + "running";
    public static final String INFO_STALLED = INFO + "stalled";
    public static final String TOAST_ITEM = "toast." + MaxtechStorage.MOD_ID + ".crafting.item";
    /** A job cancelled by a named player (B09 item 21d). */
    public static final String TOAST_CANCELLED_BY = "toast." + MaxtechStorage.MOD_ID + ".crafting.cancelled_by";
    /** The second line of a team member's toast: the requester's name and the item line. */
    public static final String TOAST_TEAM = "toast." + MaxtechStorage.MOD_ID + ".crafting.team";

    static {
        ModContent.CONTENT.translation(INFO_STATS, "CPU: %s steps, %s lanes, %s crafts per dispatch");
        ModContent.CONTENT.translation(INFO_CONFLICT, "Another CPU core touches this cluster: neither runs");
        ModContent.CONTENT.translation(INFO_IDLE, "Idle");
        ModContent.CONTENT.translation(INFO_RUNNING, "Crafting %s %s");
        ModContent.CONTENT.translation(INFO_STALLED, "Stalled without power: %s %s");
        ModContent.CONTENT.translation(TOAST_ITEM, "%s x %s");
        ModContent.CONTENT.translation(TOAST_CANCELLED_BY, "Crafting cancelled by %s");
        ModContent.CONTENT.translation(TOAST_TEAM, "%s's job: %s");
        ModContent.CONTENT.translation(JobNotice.DONE.toastKey(), "Crafting done");
        ModContent.CONTENT.translation(JobNotice.MISSING.toastKey(), "Crafting stopped: %s x %s missing");
        ModContent.CONTENT.translation(JobNotice.PROVIDER_LOCKED.toastKey(), "Crafting held up: a provider is locked");
        ModContent.CONTENT.translation(JobNotice.RECIPE_CHANGED.toastKey(), "Crafting stopped: a recipe changed");
        ModContent.CONTENT.translation(JobNotice.STOPPED.toastKey(), "Crafting stopped");
        ModContent.CONTENT.translation(JobNotice.CANCELLED.toastKey(), "Crafting cancelled");
        ModContent.CONTENT.translation(JobNotice.WAITING_CPU.reasonKey(), "A job of yours waits for a free CPU");
        ModContent.CONTENT.translation(JobNotice.WAITING_POWER.reasonKey(), "A job of yours waits for power");
        ModContent.CONTENT.translation(JobNotice.MISSING.reasonKey(), "A job of yours stopped: items missing");
        ModContent.CONTENT.translation(JobNotice.PROVIDER_LOCKED.reasonKey(),
                "A job of yours is held up by a locked provider");
        ModContent.CONTENT.translation(JobNotice.RECIPE_CHANGED.reasonKey(),
                "A job of yours stopped: a recipe changed");
        ModContent.CONTENT.translation(JobNotice.STOPPED.reasonKey(), "A job of yours stopped");
    }

    private CpuContent() {
    }

    private static BlockEntry<CpuCoreBlock, CpuCoreItem> core(CpuTier tier, String roman,
            ContentRecipe<? super CpuCoreItem> recipe) {
        String name = "crafting_cpu_tier_" + tier.level();
        return ModContent.CONTENT.block(name, p -> new CpuCoreBlock(tier, p))
                .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL))
                .item((block, p) -> new CpuCoreItem(block, p))
                .model(BlockModel.cubeAll(MaxtechStorage.id("block/cpu/core_tier_" + tier.level())))
                .lang("Crafting CPU " + roman).loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE)
                .itemTag(MaterialTags.component(name)).recipe(recipe).creativeTab().register();
    }

    private static BlockEntry<CpuPartBlock, BlockItem> part(CpuPart part, String lang, String processor) {
        TagKey<Item> processorTag = MaterialTags.component(processor);
        return ModContent.CONTENT.block("crafting_cpu_" + part.partName(), p -> new CpuPartBlock(part, p))
                .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL))
                .simpleItem().model(BlockModel.cubeAll(MaxtechStorage.id("block/cpu/" + part.partName()))).lang(lang)
                .loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE)
                .recipe((recipes, item) -> recipes.shaped(RecipeCategory.MISC, item).pattern("PXP").pattern("XTX")
                        .pattern("PXP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('X', processorTag)
                        .define('T', MaterialTags.TANTALUM_INGOT)
                        .unlockedBy("has_" + processor, recipes.has(processorTag)).save(recipes.output()))
                .creativeTab().register();
    }

    /**
     * Loads the class during mod construction, installs the job queue for background crafts and registers the toast.
     */
    public static void init() {
        BackgroundCrafts.install(QueueBackgroundCrafting.INSTANCE);
        CraftingDeliveries.listen(CraftingOutputs.INSTANCE);
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, JobToastPayload.TYPE,
                JobToastPayload.STREAM_CODEC, (payload, player) -> JobToasts.show(payload));
    }
}
