package dev.silencebound.maxtech.storage.config;

import dev.silencebound.maxtech.lib.content.ContentToggle;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.machine.ModuleContent;
import dev.silencebound.maxtech.storage.material.DataCrystalContent;
import dev.silencebound.maxtech.storage.material.VoidCrystalContent;
import dev.silencebound.maxtech.storage.p2p.P2PContent;
import dev.silencebound.maxtech.storage.p2p.P2PKind;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import dev.silencebound.maxtech.storage.tool.belt.BeltContent;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletContent;
import dev.silencebound.maxtech.storage.tool.uplink.UplinkContent;
import dev.silencebound.maxtech.storage.world.WorldContent;
import dev.silencebound.maxtech.storage.world.WorldKind;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Storage's module switches ({@link StorageCommonConfig}, {@code [modules]}) as Lib {@link ContentToggle}s. A module
 * that is off drops its recipes (datagen gives each the {@code maxtech_lib:content_enabled} condition through
 * {@link #gate}), hides its items from creative tabs and JEI, and leaves placed blocks and parts inert: they stay in
 * the world and keep their settings but do no work, so turning the module back on resumes them. Toggle ids are the
 * config keys in snake case ({@code modules.placerBreaker} is {@code maxtech_storage:placer_breaker}).
 */
public final class StorageModules {
    /** One switch, which tests can force on or off. */
    public static final class Module {
        private final BooleanSupplier config;
        private final ContentToggle toggle;
        private volatile @Nullable Boolean override;

        private Module(String id, BooleanSupplier config) {
            this.config = config;
            this.toggle = ContentToggle.register(MaxtechStorage.id(id), this::read);
        }

        private boolean read() {
            Boolean forced = override;
            return forced != null ? forced : config.getAsBoolean();
        }

        /** Whether the module is on; on before the common config loads. Any thread, no allocation. */
        public boolean isEnabled() {
            return toggle.isEnabled();
        }

        public ContentToggle toggle() {
            return toggle;
        }

        public Identifier id() {
            return toggle.id();
        }

        /**
         * Makes the module read as on or off regardless of the config, or follow the config again with {@code null}.
         * For GameTests, which restore it before they return.
         */
        public void overrideEnabled(@Nullable Boolean enabled) {
            override = enabled;
        }
    }

    public static final Module PLACER_BREAKER = new Module("placer_breaker", StorageCommonConfig.PLACER_BREAKER);
    public static final Module COLLECTOR = new Module("collector", StorageCommonConfig.COLLECTOR);
    public static final Module NETWORK_ANCHOR = new Module("network_anchor", StorageCommonConfig.NETWORK_ANCHOR);
    public static final Module WIRELESS = new Module("wireless", StorageCommonConfig.WIRELESS);
    public static final Module DATA_GEODES = new Module("data_geodes", StorageCommonConfig.DATA_GEODES);
    public static final Module VOID_GEODES = new Module("void_geodes", StorageCommonConfig.VOID_GEODES);
    private static final Map<P2PKind, Module> P2P = new EnumMap<>(P2PKind.class);

    static {
        P2P.put(P2PKind.ITEM, new Module("p2p_item", StorageCommonConfig.P2P_ITEM));
        P2P.put(P2PKind.FLUID, new Module("p2p_fluid", StorageCommonConfig.P2P_FLUID));
        P2P.put(P2PKind.ENERGY, new Module("p2p_energy", StorageCommonConfig.P2P_ENERGY));
        P2P.put(P2PKind.REDSTONE, new Module("p2p_redstone", StorageCommonConfig.P2P_REDSTONE));
    }

    /** Identity until Storage's datagen installs the loader's condition writer. */
    private static volatile BiFunction<RecipeOutput, Identifier, RecipeOutput> gate = (output, toggle) -> output;

    private StorageModules() {
    }

    /** The P2P module of {@code kind}. */
    public static Module p2p(P2PKind kind) {
        return Objects.requireNonNull(P2P.get(kind), kind.name());
    }

    /** The module of a world device; null for the network chest, which no switch covers. */
    public static @Nullable Module of(WorldKind kind) {
        return switch (kind) {
            case NETWORK_CHEST -> null;
            case COLLECTOR -> COLLECTOR;
            case PLACER, BREAKER -> PLACER_BREAKER;
        };
    }

    /** Whether the world device {@code kind} works; always for the network chest. */
    public static boolean enabled(WorldKind kind) {
        Module module = of(kind);
        return module == null || module.isEnabled();
    }

    /**
     * {@code output} with recipes saved through it loading only while {@code module} is on. Datagen only; outside
     * Storage's datagen (where the condition writer is installed) it returns {@code output}.
     */
    public static RecipeOutput gate(RecipeOutput output, Module module) {
        return gate.apply(output, module.id());
    }

    /**
     * Sets how {@link #gate} attaches the toggle condition, since recipe conditions are loader types common code cannot
     * name. Called by the loader's datagen before its providers run.
     */
    public static void installGate(BiFunction<RecipeOutput, Identifier, RecipeOutput> writer) {
        gate = writer;
    }

    /** Registers the toggles and the items each hides; once, during mod construction after the content. */
    public static void init() {
        PLACER_BREAKER.toggle().hides(WorldContent.PLACER).hides(WorldContent.BREAKER)
                .hides(WorldContent.PLACER_PART_ITEM).hides(WorldContent.BREAKER_PART_ITEM);
        COLLECTOR.toggle().hides(WorldContent.COLLECTOR).hides(WorldContent.COLLECTOR_PART_ITEM);
        NETWORK_ANCHOR.toggle().hides(ExpansionContent.NETWORK_ANCHOR);
        WIRELESS.toggle().hides(SignalContent.WIRELESS_TERMINAL).hides(ExpansionContent.WIRELESS_ACCESS_MOUNT);
        DATA_GEODES.toggle().hides(DataCrystalContent.BUDDING_DATA_CRYSTAL);
        // The whole void tier: crystal, its blocks, the lattice, memory V and VI, the void and bridge modules. Bulk
        // drives stay.
        VOID_GEODES.toggle().hides(VoidCrystalContent.BUDDING_VOID_CRYSTAL).hides(VoidCrystalContent.VOID_CRYSTAL)
                .hides(VoidCrystalContent.VOID_CRYSTAL_BLOCK).hides(VoidCrystalContent.SMALL_VOID_CRYSTAL_BUD)
                .hides(VoidCrystalContent.MEDIUM_VOID_CRYSTAL_BUD).hides(VoidCrystalContent.LARGE_VOID_CRYSTAL_BUD)
                .hides(VoidCrystalContent.VOID_CRYSTAL_CLUSTER).hides(VoidCrystalContent.VOID_LATTICE)
                .hides(CellContent.MEMORY_MODULE_5).hides(CellContent.MEMORY_MODULE_6).hides(ModuleContent.VOID_MODULE)
                .hides(ExpansionContent.DIMENSION_BRIDGE);
        WIRELESS.toggle().hides(UplinkContent.POCKET_UPLINK).hides(BeltContent.RESTOCK_BELT)
                .hides(CraftingTabletContent.TABLET);
        for (P2PKind kind : P2PKind.values()) {
            p2p(kind).toggle().hides(P2PContent.block(kind)).hides(P2PContent.partItem(kind));
        }
    }
}
