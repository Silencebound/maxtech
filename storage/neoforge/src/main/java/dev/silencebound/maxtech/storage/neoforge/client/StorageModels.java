package dev.silencebound.maxtech.storage.neoforge.client;

import dev.silencebound.maxtech.storage.crafting.terminal.PatternTerminalContent;
import dev.silencebound.maxtech.storage.terminal.crafting.CraftingTerminalContent;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.storage.condition.ConditionContent;
import dev.silencebound.maxtech.storage.sensor.SensorContent;
import dev.silencebound.maxtech.storage.togglebus.ToggleBusContent;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.display.DisplayContent;
import dev.silencebound.maxtech.storage.cable.StorageCableConduitType;
import dev.silencebound.maxtech.storage.drive.DriveBays;
import dev.silencebound.maxtech.storage.drive.DriveContent;
import dev.silencebound.maxtech.storage.iface.InterfaceContent;
import dev.silencebound.maxtech.storage.network.LinkLook;
import dev.silencebound.maxtech.storage.p2p.P2PContent;
import dev.silencebound.maxtech.storage.p2p.P2PKind;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortTier;
import dev.silencebound.maxtech.storage.research.ResearchContent;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.world.WorldContent;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.ControllerFace;
import dev.silencebound.maxtech.storage.controller.ControllerStatus;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuCoreBlock;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuPartBlock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import org.jspecify.annotations.Nullable;

/**
 * Puts Storage's code-built models over the baked block state models: storage cables get {@link StorageCableModel}
 * (Lib's conduit block state files name Lib's conduit model, which the cable model keeps for facades), the drive gets
 * {@link StorageDriveModel} around its datagen model, the controller {@link ControllerFaceModel} (its datagen model
 * stays for the item and as the fallback), the crafting CPU blocks {@link CpuClusterModel}. Runs after every bake, so a
 * resource reload rebuilds them with empty part caches.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID, value = Dist.CLIENT)
final class StorageModels {
    private StorageModels() {
    }

    @SubscribeEvent
    static void wrap(ModelEvent.ModifyBakingResult event) {
        Map<BlockState, BlockStateModel> models = event.getBakingResult().blockStateModels();
        Function<Identifier, TextureAtlasSprite> atlas = event.getTextureGetter();
        StorageCableModel.Sprites cable = new StorageCableModel.Sprites(sprite(atlas, "cable/sleeve"),
                sprite(atlas, "cable/sleeve_core"), sprite(atlas, "cable/covered"), sprite(atlas, "cable/covered_core"),
                List.of(sprite(atlas, "cable/covered_core_tier_1"), sprite(atlas, "cable/covered_core_tier_2"),
                        sprite(atlas, "cable/covered_core_tier_3"), sprite(atlas, "cable/covered_core_tier_4")),
                sprite(atlas, "cable/lane"), sprite(atlas, "cable/fiber_lane"),
                List.of(sprite(atlas, "cable/lane_core_tier_1"), sprite(atlas, "cable/lane_core_tier_2"),
                        sprite(atlas, "cable/lane_core_tier_3"), sprite(atlas, "cable/lane_core_tier_4")),
                new Material.Baked(atlas.apply(MaxtechLib.id("block/conduit/flat")), false));
        Map<Identifier, PartBaker> parts = partBakers(atlas);
        Map<LinkLook, Material.Baked> lines = linkLines(atlas);
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block instanceof ConduitBlock conduit && conduit.type() instanceof StorageCableConduitType type) {
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    models.computeIfPresent(state, (s, lib) -> new StorageCableModel(lib, type, cable, parts, lines));
                }
            }
        }
        // Indexed by band: empty (none), below 75 %, from 75 %, full, inactive.
        Material.@Nullable Baked[] cells = new Material.Baked[5];
        for (int band = 0; band < cells.length; band++) {
            String texture = DriveBays.cellTexture(band);
            cells[band] = texture.isEmpty() ? null : new Material.Baked(atlas.apply(MaxtechStorage.id(texture)), false);
        }
        for (BlockState state : DriveContent.DRIVE.get().getStateDefinition().getPossibleStates()) {
            models.computeIfPresent(state, (s, base) -> new StorageDriveModel(base, s, cells));
        }
        ControllerFaceModel controller = controllerModel(atlas);
        for (BlockState state : ControllerContent.CONTROLLER.get().getStateDefinition().getPossibleStates()) {
            models.computeIfPresent(state, (s, base) -> controller);
        }
        for (Block block : BuiltInRegistries.BLOCK) {
            String name = cpuTexture(block);
            if (!name.isEmpty()) {
                CpuClusterModel cpu = cpuModel(atlas, name);
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    models.computeIfPresent(state, (s, base) -> cpu);
                }
            }
        }
        // Last, so the drive's cells stay inside: devices whose front line shows their network state.
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block.defaultBlockState().hasProperty(LinkLook.PROPERTY)) {
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    models.computeIfPresent(state, (s, base) -> LinkLineModel.wrap(base, s, lines));
                }
            }
        }
    }

    /** The front line overlays ({@link LinkLook#overlayTexture()}) of every look but online, which needs none. */
    private static Map<LinkLook, Material.Baked> linkLines(Function<Identifier, TextureAtlasSprite> atlas) {
        Map<LinkLook, Material.Baked> lines = new EnumMap<>(LinkLook.class);
        for (LinkLook look : LinkLook.values()) {
            if (look != LinkLook.ONLINE) {
                lines.put(look, new Material.Baked(atlas.apply(MaxtechStorage.id(look.overlayTexture())), false));
            }
        }
        return lines;
    }

    /**
     * The controller's face model: its 16 plates by edge mask, and per status the glow strips by phase, or the one
     * still glow plane while off.
     */
    private static ControllerFaceModel controllerModel(Function<Identifier, TextureAtlasSprite> atlas) {
        List<Material.Baked> plates = new ArrayList<>(16);
        for (int mask = 0; mask < 16; mask++) {
            plates.add(sprite(atlas, "controller/face/plate_" + mask));
        }
        Map<ControllerStatus, List<Material.Baked>> glows = new EnumMap<>(ControllerStatus.class);
        for (ControllerStatus status : ControllerStatus.values()) {
            if (status == ControllerStatus.OFF) {
                glows.put(status, List.of(sprite(atlas, "controller/face/glow_off")));
                continue;
            }
            List<Material.Baked> phases = new ArrayList<>(ControllerFace.PHASES);
            for (int phase = 0; phase < ControllerFace.PHASES; phase++) {
                phases.add(sprite(atlas, "controller/face/glow_" + status.getSerializedName() + "_" + phase));
            }
            glows.put(status, List.copyOf(phases));
        }
        return new ControllerFaceModel(List.copyOf(plates), glows);
    }

    /** The texture name under {@code block/cpu/} of a crafting CPU block, or empty for any other block. */
    private static String cpuTexture(Block block) {
        if (block instanceof CpuCoreBlock core) {
            return "core_tier_" + core.tier().level();
        }
        if (block instanceof CpuPartBlock part) {
            return part.part().partName();
        }
        return "";
    }

    /**
     * A crafting CPU block's connected model: {@code block/cpu/<name>} is the lone block (every edge framed), and
     * {@code block/cpu/<name>_<mask>} the other 15 edge masks.
     */
    private static CpuClusterModel cpuModel(Function<Identifier, TextureAtlasSprite> atlas, String name) {
        List<Material.Baked> sprites = new ArrayList<>(16);
        for (int mask = 0; mask < 16; mask++) {
            sprites.add(sprite(atlas, "cpu/" + name + (mask == ControllerFace.ALL ? "" : "_" + mask)));
        }
        return new CpuClusterModel(List.copyOf(sprites));
    }

    /** How each part type is drawn on a cable; a part type without an entry draws only its arm. */
    private static Map<Identifier, PartBaker> partBakers(Function<Identifier, TextureAtlasSprite> atlas) {
        Map<Identifier, PartBaker> bakers = new HashMap<>();
        bakers.put(TerminalContent.TERMINAL_PART.id(), PartBaker
                .panel(new Material.Baked[]{sprite(atlas, "terminal/screen")}, sprite(atlas, "terminal/bezel"), true));
        for (PortTier tier : PortTier.values()) {
            // Tier 1's steps have no tier pips and no suffix.
            String suffix = tier == PortTier.TIER_1 ? "" : "_tier_" + tier.number();
            bakers.put(PortContent.partType(tier).id(),
                    PartBaker.portSteps(sprite(atlas, "port/part_step" + suffix), sprite(atlas, "port/step_tip")));
        }
        bakers.put(ResearchContent.TERMINAL_PART.id(),
                PartBaker.panel(new Material.Baked[]{sprite(atlas, "research_terminal_front")},
                        sprite(atlas, "terminal/bezel"), false));
        bakers.put(ConditionContent.SWITCH_PART.id(),
                PartBaker.panel(
                        new Material.Baked[]{sprite(atlas, "condition_switch/front_off"),
                                sprite(atlas, "condition_switch/front_on")},
                        sprite(atlas, "condition_switch/side"), false));
        bakers.put(SensorContent.SENSOR_PART.id(), PartBaker.panel(new Material.Baked[]{sprite(atlas, "sensor/front")},
                sprite(atlas, "sensor/side"), false));
        bakers.put(DisplayContent.PANEL_PART.id(), PartBaker.panel(
                new Material.Baked[]{sprite(atlas, "display/part_screen")}, sprite(atlas, "display/edge"), false));
        bakers.put(InterfaceContent.INTERFACE_PART.id(), PartBaker
                .panel(new Material.Baked[]{sprite(atlas, "interface/face")}, sprite(atlas, "port/side"), false));
        for (P2PKind kind : P2PKind.values()) {
            bakers.put(P2PContent.part(kind).id(),
                    PartBaker.panel(new Material.Baked[]{sprite(atlas, "p2p/" + kind.getSerializedName())},
                            sprite(atlas, "p2p/side"), false));
        }
        bakers.put(ToggleBusContent.TOGGLE_BUS.id(), PartBaker
                .uniform(new Material.Baked[]{sprite(atlas, "toggle_bus/open"), sprite(atlas, "toggle_bus/linked")}));
        bakers.put(ToggleBusContent.INVERTED.id(), PartBaker.uniform(new Material.Baked[]{
                sprite(atlas, "toggle_bus/inverted_open"), sprite(atlas, "toggle_bus/inverted_linked")}));
        bakers.put(WorldContent.NETWORK_CHEST_PART.id(), PartBaker
                .panel(new Material.Baked[]{sprite(atlas, "network_chest_front")}, sprite(atlas, "port/side"), false));
        bakers.put(WorldContent.COLLECTOR_PART.id(), PartBaker
                .panel(new Material.Baked[]{sprite(atlas, "world/collector")}, sprite(atlas, "port/side"), false));
        bakers.put(WorldContent.PLACER_PART.id(), PartBaker
                .panel(new Material.Baked[]{sprite(atlas, "world/placer_front")}, sprite(atlas, "port/side"), false));
        bakers.put(WorldContent.BREAKER_PART.id(), PartBaker
                .panel(new Material.Baked[]{sprite(atlas, "world/breaker_front")}, sprite(atlas, "port/side"), false));
        bakers.put(ProviderContent.PROVIDER_PART.id(),
                PartBaker.panel(new Material.Baked[]{sprite(atlas, "pattern_provider_face")},
                        sprite(atlas, "pattern_provider_side"), false));
        bakers.put(PatternTerminalContent.TERMINAL_PART.id(), PartBaker.panel(
                new Material.Baked[]{sprite(atlas, "pattern_terminal_front")}, sprite(atlas, "terminal/bezel"), false));
        bakers.put(CraftingTerminalContent.TERMINAL_PART.id(),
                PartBaker.panel(new Material.Baked[]{sprite(atlas, "crafting_terminal_front")},
                        sprite(atlas, "terminal/bezel"), false));
        return Map.copyOf(bakers);
    }

    /** The four tiers' lane sprites named {@code prefix} plus the tier, tier 1 first. */
    private static Material.Baked sprite(Function<Identifier, TextureAtlasSprite> atlas, String name) {
        return new Material.Baked(atlas.apply(MaxtechStorage.id("block/" + name)), false);
    }
}
