package dev.silencebound.maxtech.storage.controller;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cluster.ClusterPreview;
import dev.silencebound.maxtech.storage.controller.alarm.AlarmCenter;
import dev.silencebound.maxtech.storage.controller.alarm.AlarmTabPayload;
import dev.silencebound.maxtech.storage.controller.recorder.RecordTexts;
import dev.silencebound.maxtech.storage.controller.recorder.RecordsPayloads;
import dev.silencebound.maxtech.storage.controller.card.CardContent;
import dev.silencebound.maxtech.storage.controller.recovery.RecoveryTabPayload;
import dev.silencebound.maxtech.storage.controller.recovery.RecoveryTexts;
import dev.silencebound.maxtech.storage.controller.module.AnchorChunks;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.TelemetryDisplayHistory;
import dev.silencebound.maxtech.storage.display.DisplayHistory;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.research.Research;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;

/**
 * The storage controller block, of which multiblock controllers are built (M7R item 1): block, item and block entity
 * type, its info lines, and (through {@link #init()}) the expansions of {@code controller.module}. Its model file is
 * written by Storage's model provider; in the world the client draws it with the face model (M7R item 7: connected
 * textures, the glow colour by {@link ControllerBlock#STATUS}).
 */
public final class ControllerContent {
    public static final String CLUSTER_KEY = "info." + MaxtechStorage.MOD_ID + ".controller.cluster";
    public static final String OVER_CAP_KEY = "info." + MaxtechStorage.MOD_ID + ".controller.over_cap";
    public static final String MEMBER_KEY = "info." + MaxtechStorage.MOD_ID + ".controller.member";
    public static final String DARK_KEY = "info." + MaxtechStorage.MOD_ID + ".controller.dark";
    public static final String STANDBY_KEY = "info." + MaxtechStorage.MOD_ID + ".controller.standby";
    public static final String ENERGY_TOOLTIP_KEY = "tooltip." + MaxtechStorage.MOD_ID + ".controller.energy";

    public static final BlockEntry<ControllerBlock, ControllerItem> CONTROLLER = ModContent.CONTENT
            .block("storage_controller", ControllerBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL)
                    .lightLevel(ControllerBlock::light))
            .item(ControllerItem::new).lang("Storage Controller").loot(BlockLoot.SELF)
            .tag(BlockTags.MINEABLE_WITH_PICKAXE)
            // The logic and memory processors run the network; display glass shows its core.
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern("PLP").pattern("QDQ")
                    .pattern("PMP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('L', MaterialTags.component("basic_logic_processor"))
                    .define('M', MaterialTags.component("basic_memory_processor"))
                    .define('Q', MaterialTags.LUMEN_QUARTZ).define('D', MaterialTags.component("display_glass"))
                    .unlockedBy("has_memory_processor", recipes.has(MaterialTags.component("basic_memory_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<ControllerBlockEntity>> CONTROLLER_BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("storage_controller",
                    () -> new BlockEntityType<>(ControllerBlockEntity::new, Set.of(CONTROLLER.get())));

    static {
        ModContent.CONTENT.translation(CLUSTER_KEY, "Controller: %s blocks of at most %s");
        ModContent.CONTENT.translation(OVER_CAP_KEY,
                "More controller blocks than the size limit allows; the rest stay dark until research raises it");
        ModContent.CONTENT.translation(MEMBER_KEY, "Part of the controller at %s");
        ModContent.CONTENT.translation(DARK_KEY,
                "Dark: the controller already has its %s blocks; research raises the size limit");
        ModContent.CONTENT.translation(STANDBY_KEY,
                "This network already has a controller. Use a subnet controller to join two networks.");
        ModContent.CONTENT.translation(ENERGY_TOOLTIP_KEY, "Stored: %s MU");
    }

    private ControllerContent() {
    }

    /**
     * Loads the class (and the expansions, right after it in the creative tab) during mod construction so the entries
     * above are queued before registration runs, and has completed research re-check the cluster cap of the team's
     * loaded cores. Display screens get their past samples from the controllers' telemetry; the core's Network tab gets
     * its payload, and the recorder views theirs.
     */
    public static void init() {
        CardContent.init();
        ExpansionContent.init();
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, NetworkTabPayload.TYPE,
                NetworkTabPayload.STREAM_CODEC, (payload, player) -> {
                    if (player.containerMenu instanceof ControllerMenu menu
                            && menu.containerId == payload.containerId()) {
                        menu.accept(payload.overview());
                    }
                });
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, AlarmTabPayload.TYPE,
                AlarmTabPayload.STREAM_CODEC, (payload, player) -> {
                    if (player.containerMenu instanceof ControllerMenu menu
                            && menu.containerId == payload.containerId()) {
                        menu.acceptAlarms(payload.alarms());
                    }
                });
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, RecoveryTabPayload.TYPE,
                RecoveryTabPayload.STREAM_CODEC, (payload, player) -> {
                    if (player.containerMenu instanceof ControllerMenu menu
                            && menu.containerId == payload.containerId()) {
                        menu.acceptRecovery(payload);
                    }
                });
        RecoveryTexts.register();
        RecordTexts.register();
        RecordsPayloads.init();
        AlarmCenter.init();
        NetworkRenamePayload.register();
        ClusterPreview.register();
        DisplayHistory.register(TelemetryDisplayHistory.INSTANCE);
        Research.onCompleted((server, team, node) -> ControllerBlockEntity
                .redetectLoaded(core -> Research.inTeam(server, core.ownerRecord(), team)));
        // An owner in another team counts against that team's chunk cap; a config reload may change the cap.
        Research.onTeamChanged(
                player -> ControllerBlockEntity.recheckAnchors(core -> core.ownerRecord().isOwner(player)));
        AnchorChunks.onRecheck(() -> ControllerBlockEntity.recheckAnchors(core -> true));
    }
}
