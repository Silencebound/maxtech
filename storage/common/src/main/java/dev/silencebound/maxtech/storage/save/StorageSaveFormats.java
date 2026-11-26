package dev.silencebound.maxtech.storage.save;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.lib.api.energy.FeRate;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.save.MigrationStep;
import dev.silencebound.maxtech.lib.save.SaveFormat;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The save formats of Storage's data. A change to what one of them saves after 1.0 adds a migration step here (with a
 * test), never a silent change of field names or meanings. Each step sees the kind of data it gets, so a step can
 * change one block entity type or saved data type and pass the others through.
 */
public final class StorageSaveFormats {
    /** The storage controller's block entity type. */
    static final Identifier CONTROLLER = Identifier.fromNamespaceAndPath("maxtech_storage", "storage_controller");
    /** Where a migrated controller's capacitor banks stood (M7R item 5), read by the controller. */
    public static final String BANK_CELLS = "bank_cells";
    /** The network devices' block entity type (M7R item 4). */
    static final Identifier SERVICE_DEVICE = Identifier.fromNamespaceAndPath("maxtech_storage", "service_device");
    /** The status lights' block entity type (M7R item 6; the blocks were removed on 2026-10-18). */
    static final Identifier STATUS_LIGHT = Identifier.fromNamespaceAndPath("maxtech_storage", "status_light");
    /** The block entity types that saved FE under {@code energy} before MLE item 4. */
    static final Set<Identifier> FE_HOLDERS = Set.of(CONTROLLER,
            Identifier.fromNamespaceAndPath("maxtech_storage", "energy_cell"),
            Identifier.fromNamespaceAndPath("maxtech_storage", "subnet_controller"),
            Identifier.fromNamespaceAndPath("maxtech_storage", "fuel_generator"),
            Identifier.fromNamespaceAndPath("maxtech_storage", "lithograph"),
            Identifier.fromNamespaceAndPath("maxtech_storage", "fabricator"),
            Identifier.fromNamespaceAndPath("maxtech_storage", "assembler"));
    /** The saved energy key of {@link #FE_HOLDERS}. */
    static final String ENERGY = "energy";
    /**
     * Block entities version 1 to 2 (M7R item 1): a controller core of the expansion cluster becomes a block of a
     * multiblock controller. Its FE, settings, name, alarms, recovery rows and modules stay; the cluster-only fields go
     * (the idle flag of a second core, the expansion cap and whether it was exceeded), and the block finds its
     * multiblock once after loading ({@code redetect}), which also lets the retired channel arrays, capacitor banks and
     * device buses go. Other block entities pass through.
     */
    public static final MigrationStep CONTROLLER_CLUSTER_TO_MULTIBLOCK = StorageSaveFormats::controllerToMultiblock;
    /**
     * Block entities version 2 to 3 (M7R item 2): a controller whose saved modules include a batch amplifier,
     * efficiency core or surge regulator (kind indices 2, 6 and 7) finds its multiblock once after loading
     * ({@code redetect}); that fill turns those blocks into upgrade cards in its slots, or drops the cards at the
     * blocks when no slot takes them. Other block entities and other controllers pass through.
     */
    public static final MigrationStep CONTROLLER_MODULES_TO_CARDS = StorageSaveFormats::controllerModulesToCards;
    /**
     * Block entities version 3 to 4 (M7R item 3): a controller whose saved modules include a telemetry recorder, alarm
     * module or ledger module (kind indices 10, 11 and 16) finds its multiblock once after loading ({@code redetect});
     * that fill moves the ledger modules' rows into the controller's ledger and each alarm module's settings into a
     * redstone alarm rule, and the blocks stay, inert. A module whose chunk loads later asks its controller for that
     * fill itself. Other block entities and other controllers pass through; the modules keep their data until a
     * controller takes it.
     */
    public static final MigrationStep CONTROLLER_MODULES_TO_RECORDERS = StorageSaveFormats::controllerModulesToRecorders;
    /**
     * Block entities version 4 to 5 (M7R item 4): the security module, network anchor, antenna mount, wireless access
     * mount and dimension bridge become standalone network devices. A controller whose saved modules include one of
     * them (kind indices 8, 9, 14, 15 and 17) finds its multiblock once after loading ({@code redetect}); that fill no
     * longer attaches them, so they leave its module list. The security rules and the anchor's chunks stay with the
     * controller and apply while such a device is on its network. A device's own data, saved under the expansion block
     * entity type and loaded as a {@code service_device} (see {@code ExpansionContent.EXPANSION_BLOCK_ENTITY}), loses
     * the position of its old core ({@code core}) and keeps the rest (owner, label, link sides, join order). An antenna
     * mount takes its pairing from its antenna when either loads. Other block entities pass through.
     */
    public static final MigrationStep MODULES_TO_DEVICES = StorageSaveFormats::modulesToDevices;
    /**
     * Block entities version 5 to 6 (M7R item 5): the capacitor bank folds into the energy cells. Its block and item
     * ids are registry aliases of the tier-1 energy cell now ({@code RetiredIds}), so a placed bank loads as a tier-1
     * cell in place. A controller whose saved modules include a capacitor bank (kind index 1) finds its multiblock once
     * after loading ({@code redetect}), which drops the bank from its module list, and lists where its banks stood
     * ({@code bank_cells}): that fill moves the FE the controller holds above its capacity into the cells there, up to
     * their room; what does not fit stays in the controller. Other block entities pass through.
     */
    public static final MigrationStep CAPACITOR_BANKS_TO_CELLS = StorageSaveFormats::capacitorBanksToCells;
    /**
     * Block entities version 6 to 7 (M7R item 6): the status crown and light strip become decorative network members
     * placed anywhere on the network, without a channel. A controller whose saved modules include one (kind indices 12
     * and 13) finds its multiblock once after loading ({@code redetect}); that fill no longer attaches them, so they
     * leave its module list. A light's own data, saved under the expansion block entity type and loaded as a
     * {@code status_light} (see {@code ExpansionContent.EXPANSION_BLOCK_ENTITY}), loses the position of its old core
     * ({@code core}) and keeps the rest (owner, label, link sides, join order). A light touching the controller or a
     * cable joins its network and shows its state as before. Other block entities pass through. The status lights were
     * removed on 2026-10-18; the step stays so the version numbers keep their meaning, and the fill it asks for now
     * drops the unknown kinds.
     */
    public static final MigrationStep STATUS_LIGHTS_TO_DISPLAYS = StorageSaveFormats::statusLightsToDisplays;
    /**
     * Block entities version 7 to 8 (MLE item 4): Storage moves from FE to MaxTech Units. The controller, energy cell,
     * subnet controller and machines saved FE under {@code energy}; it becomes {@code floor(FE * mu / fe)} MU at Lib's
     * FE rate when the step runs ({@code energy.feRateFe}/{@code feRateMu}, default 1:1, where nothing changes). The
     * remainder below one MU is kept in units of that rate ({@code fe_held} with {@code fe_held_rate_fe} and
     * {@code fe_held_rate_mu}) for the block's FE view, which adds it to the next FE that comes in, so nothing is lost.
     * The stamp makes it run once. Other block entities pass through.
     */
    public static final MigrationStep FE_TO_MU = StorageSaveFormats::feToMu;
    /** Every Storage block entity: devices, machines, chests and workbenches. Kind: the block entity type id. */
    public static final SaveFormat BLOCK_ENTITIES = SaveFormat.builder("maxtech_storage:block_entities")
            .step(CONTROLLER_CLUSTER_TO_MULTIBLOCK).step(CONTROLLER_MODULES_TO_CARDS)
            .step(CONTROLLER_MODULES_TO_RECORDERS).step(MODULES_TO_DEVICES).step(CAPACITOR_BANKS_TO_CELLS)
            .step(STATUS_LIGHTS_TO_DISPLAYS).step(FE_TO_MU).build();
    /** The parts in a storage cable (terminals, ports, switches), each saved apart. Kind: the part type id. */
    public static final SaveFormat CABLE_PARTS = SaveFormat.builder("maxtech_storage:cable_parts").build();
    /** Storage's level saved data: cells, network names and snapshots, crafting jobs and the rest. Kind: its id. */
    public static final SaveFormat SAVED_DATA = SaveFormat.builder("maxtech_storage:saved_data").build();
    /** Item data components saved as maps (a storage cell's parts and id). Kind: the component id. */
    public static final SaveFormat ITEMS = SaveFormat.builder("maxtech_storage:items").build();

    private StorageSaveFormats() {
    }

    private static CompoundTag controllerToMultiblock(Identifier kind, CompoundTag tag) {
        if (!kind.equals(CONTROLLER)) {
            return tag;
        }
        tag.remove("idle");
        tag.remove("over");
        tag.remove("cap");
        tag.putBoolean("redetect", true);
        return tag;
    }

    private static CompoundTag controllerModulesToCards(Identifier kind, CompoundTag tag) {
        if (!kind.equals(CONTROLLER)) {
            return tag;
        }
        for (int index : tag.getIntArray("kinds").orElse(new int[0])) {
            if (index == 2 || index == 6 || index == 7) {
                tag.putBoolean("redetect", true);
                break;
            }
        }
        return tag;
    }

    private static CompoundTag controllerModulesToRecorders(Identifier kind, CompoundTag tag) {
        if (!kind.equals(CONTROLLER)) {
            return tag;
        }
        for (int index : tag.getIntArray("kinds").orElse(new int[0])) {
            if (index == 10 || index == 11 || index == 16) {
                tag.putBoolean("redetect", true);
                break;
            }
        }
        return tag;
    }

    private static CompoundTag modulesToDevices(Identifier kind, CompoundTag tag) {
        if (kind.equals(SERVICE_DEVICE)) {
            tag.remove("core");
            return tag;
        }
        if (!kind.equals(CONTROLLER)) {
            return tag;
        }
        for (int index : tag.getIntArray("kinds").orElse(new int[0])) {
            if (index == 8 || index == 9 || index == 14 || index == 15 || index == 17) {
                tag.putBoolean("redetect", true);
                break;
            }
        }
        return tag;
    }

    private static CompoundTag capacitorBanksToCells(Identifier kind, CompoundTag tag) {
        if (!kind.equals(CONTROLLER)) {
            return tag;
        }
        int[] kinds = tag.getIntArray("kinds").orElse(new int[0]);
        long[] members = tag.getLongArray("members").orElse(new long[0]);
        int banks = 0;
        for (int i = 0; i < kinds.length && i < members.length; i++) {
            if (kinds[i] == 1) {
                banks++;
            }
        }
        if (banks == 0) {
            return tag;
        }
        long[] cells = new long[banks];
        int next = 0;
        for (int i = 0; i < kinds.length && i < members.length; i++) {
            if (kinds[i] == 1) {
                cells[next++] = members[i];
            }
        }
        tag.putLongArray(BANK_CELLS, cells);
        tag.putBoolean("redetect", true);
        return tag;
    }

    private static CompoundTag statusLightsToDisplays(Identifier kind, CompoundTag tag) {
        if (kind.equals(STATUS_LIGHT)) {
            tag.remove("core");
            return tag;
        }
        if (!kind.equals(CONTROLLER)) {
            return tag;
        }
        for (int index : tag.getIntArray("kinds").orElse(new int[0])) {
            if (index == 12 || index == 13) {
                tag.putBoolean("redetect", true);
                break;
            }
        }
        return tag;
    }

    /** Where {@link #FE_TO_MU} reads the rate: Lib's server config, asked only when a tag holds FE. */
    private static Supplier<FeRate> rate = StorageSaveFormats::configuredRate;

    private static FeRate configuredRate() {
        return ServerConfig.feRate();
    }

    /** Has {@link #FE_TO_MU} use {@code source} for the rate; for JUnit tests, which have no Lib config. */
    static void useRate(Supplier<FeRate> source) {
        rate = source;
    }

    private static CompoundTag feToMu(Identifier kind, CompoundTag tag) {
        if (!FE_HOLDERS.contains(kind) || tag.getLongOr(ENERGY, 0L) <= 0) {
            return tag;
        }
        return feToMu(kind, tag, rate.get());
    }

    /** The block entity data version from which {@code energy} holds MU ({@link #FE_TO_MU} ran). */
    public static final int MU_VERSION = 8;

    /**
     * The MU in block entity data {@code tag} as saved (a machine's or subnet controller's item): {@code energy} as is
     * from {@link #MU_VERSION} on, else the FE converted the way {@link #FE_TO_MU} will on placing, rounded down. Reads
     * only, for tooltips; Lib's rate as configured now.
     */
    public static long savedMu(CompoundTag tag) {
        long energy = tag.getLongOr(ENERGY, 0L);
        if (energy <= 0 || tag.getIntOr(SaveFormat.VERSION_KEY, 1) >= MU_VERSION) {
            return Math.max(0, energy);
        }
        return feToMu(energy, rate.get())[0];
    }

    /** {@link #FE_TO_MU} at {@code rate}; see there. */
    public static CompoundTag feToMu(Identifier kind, CompoundTag tag, FeRate rate) {
        if (!FE_HOLDERS.contains(kind)) {
            return tag;
        }
        long fe = tag.getLongOr(ENERGY, 0L);
        if (fe <= 0) {
            return tag;
        }
        long[] converted = feToMu(fe, rate);
        tag.putLong(ENERGY, converted[0]);
        if (converted[1] > 0) {
            tag.putLong("fe_held", converted[1]);
            tag.putInt("fe_held_rate_fe", rate.fe());
            tag.putInt("fe_held_rate_mu", rate.mu());
        }
        return tag;
    }

    /**
     * {@code fe} FE as {@code {MU, remainder}} at {@code rate}: the whole MU, rounded down, and what is left in units
     * of the rate ({@link FeRate#unitsOfFe}, below one MU). The two together are worth exactly {@code fe}.
     */
    public static long[] feToMu(long fe, FeRate rate) {
        long units = rate.unitsOfFe(fe);
        long mu = rate.wholeMu(units);
        return new long[]{mu, units - rate.unitsOfMu(mu)};
    }

    /** A saved data type whose saves carry the {@link #SAVED_DATA} version and are migrated on load. */
    public static <T extends SavedData> SavedDataType<T> savedData(Identifier id, Supplier<T> constructor,
            Codec<T> codec) {
        return new SavedDataType<>(id, constructor, SAVED_DATA.codec(id, codec));
    }
}
