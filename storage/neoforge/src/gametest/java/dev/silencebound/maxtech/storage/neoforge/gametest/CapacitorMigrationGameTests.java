package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.serialization.DynamicOps;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.resource.KeyCounts;
import dev.silencebound.maxtech.lib.resource.KeyPalette;
import dev.silencebound.maxtech.lib.resource.KeyRegistry;
import dev.silencebound.maxtech.lib.save.SaveFormat;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.module.ModuleKind;
import dev.silencebound.maxtech.storage.energy.EnergyCellBlockEntity;
import dev.silencebound.maxtech.storage.energy.EnergyContent;
import dev.silencebound.maxtech.storage.save.RetiredIds;
import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * M7R item 5: the controller capacitor bank folded into the energy cells. An old bank loads as a tier-1 energy cell in
 * place, its controller drops it and moves the FE above its capacity into the cell exactly once (what does not fit
 * stays in the controller), old bank items load as tier-1 cells with their count, and the tier-1 cell's capacity and
 * transfer come from config (defaults 140,000 FE and 2,000 FE, the old 100,000 and 1,000 plus the bank's figures).
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class CapacitorMigrationGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final Identifier EXPANSION_TYPE = MaxtechStorage.id("controller_expansion");
    private static final String CONFIG_TEST = "energy_cell_tier_1_from_config";
    private static final List<String> NAMES = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("energy_capacitor_bank_overflow", CapacitorMigrationGameTests::bankOverflow);
        register("energy_capacitor_bank_ids", CapacitorMigrationGameTests::bankIds);
        register("energy_capacitor_banks_several", CapacitorMigrationGameTests::severalBanks);
        register("energy_capacitor_bank_gone_or_unloaded", CapacitorMigrationGameTests::bankGoneOrUnloaded);
        StorageTests.function(CONFIG_TEST, StorageTestEnvironment.clearing(CapacitorMigrationGameTests::fromConfig));
    }

    private CapacitorMigrationGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("capacitor_migration"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 400);
        }
        Holder<TestEnvironmentDefinition<?>> configured = event
                .registerEnvironment(MaxtechStorage.id("energy_cell_tier_1_config"), new StorageTestEnvironment(
                        "energy.cells.tier1.capacity;energy.cells.tier1.maxTransfer", "50000;700", false));
        StorageTests.test(event, configured, CONFIG_TEST, STRUCTURE, 200);
    }

    /**
     * A controller saved at data version 5 with 200,000 FE (one block: 10,000 capacity) and a capacitor bank, whose
     * block loads as a tier-1 cell from the bank's old block entity data (expansion type, its owner kept): the fill
     * drops the bank, fills the cell to its 140,000 and leaves 60,000 above capacity in the controller, so all 200,000
     * FE are kept. A second fill moves nothing more.
     */
    private static void bankOverflow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        OwnerRecord owner = OwnerRecord.of(NetworkGameTests.player(level, "bank_owner"));
        BlockPos core = new BlockPos(5, 1, 5);
        BlockPos bank = core.east();
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        helper.setBlock(bank, EnergyContent.TIER_1.get());
        long corePos = helper.absolutePos(core).asLong();
        long bankPos = helper.absolutePos(bank).asLong();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, core).cards().usable(), 4, "first fill done"))
                .thenExecute(() -> {
                    // The bank's block entity as an old world saved it: the expansion type, version 5, its core.
                    CompoundTag old = unload(helper, bank);
                    old.putString("id", EXPANSION_TYPE.toString());
                    old.putInt(SaveFormat.VERSION_KEY, 5);
                    old.putLong("core", corePos);
                    old.remove("energy");
                    old.store("owner", OwnerRecord.CODEC, owner);
                    BlockEntity loaded = load(helper, bank, old);
                    helper.assertTrue(loaded instanceof EnergyCellBlockEntity, "loads as an energy cell");
                    EnergyCellBlockEntity cell = (EnergyCellBlockEntity) loaded;
                    helper.assertValueEqual(cell.ownerRecord(), owner, "owner kept");
                    helper.assertValueEqual(cell.cellStore().stored(), 0L, "a bank held no FE of its own");
                    helper.assertValueEqual(cell.cellStore().capacity(), 140_000L, "tier-1 default capacity");

                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
                    CompoundTag saved = controller.saveWithoutMetadata(level.registryAccess());
                    saved.putInt(SaveFormat.VERSION_KEY, 5);
                    saved.putLong("energy", 200_000L);
                    saved.putLongArray("members", new long[]{bankPos});
                    saved.putIntArray("kinds", new int[]{ModuleKind.CAPACITOR_BANK.index()});
                    controller.loadWithComponents(
                            TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved));
                    CompoundTag migrated = controller.saveWithoutMetadata(level.registryAccess());
                    helper.assertTrue(migrated.getBooleanOr(ControllerBlockEntity.REDETECT, false), "asks for a fill");
                    helper.assertValueEqual(
                            migrated.getLongArray(StorageSaveFormats.BANK_CELLS).map(a -> a.length).orElse(0), 1,
                            "remembers the bank");
                    helper.assertValueEqual(migrated.getIntOr(SaveFormat.VERSION_KEY, 0),
                            StorageSaveFormats.BLOCK_ENTITIES.currentVersion(), "saved in the new version");
                    helper.assertValueEqual(controller.expansions(ModuleKind.CAPACITOR_BANK), 1,
                            "listed until the fill");
                    controller.requestDetect();
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, core).expansions(ModuleKind.CAPACITOR_BANK), 0,
                        "the fill dropped the bank"))
                .thenExecute(() -> checkOverflow(helper, core, bank)).thenExecute(() -> {
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
                    helper.assertFalse(controller.saveWithoutMetadata(level.registryAccess())
                            .contains(StorageSaveFormats.BANK_CELLS), "done once");
                    controller.requestDetect();
                }).thenIdle(3).thenExecute(() -> checkOverflow(helper, core, bank)).thenSucceed();
    }

    /**
     * A version-5 controller with three banks and 300,000 FE (10,000 capacity): two cells fill to 140,000 each, the
     * third gets the last 10,000, and the controller ends full; 300,000 FE in total, and a second fill moves nothing.
     */
    private static void severalBanks(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        List<BlockPos> banks = List.of(core.east(), core.west(), core.north());
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        for (BlockPos bank : banks) {
            helper.setBlock(bank, EnergyContent.TIER_1.get());
        }
        long[] members = new long[banks.size()];
        int[] kinds = new int[banks.size()];
        for (int i = 0; i < members.length; i++) {
            members[i] = helper.absolutePos(banks.get(i)).asLong();
            kinds[i] = ModuleKind.CAPACITOR_BANK.index();
        }
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, core).cards().usable(), 4, "first fill done"))
                .thenExecute(() -> loadOld(helper, core, 300_000L, members, kinds))
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, core).expansions(ModuleKind.CAPACITOR_BANK), 0,
                        "the fill dropped the banks"))
                .thenExecute(() -> checkSeveral(helper, core, banks))
                .thenExecute(() -> NetworkGameTests.controllerEntity(helper, core).requestDetect()).thenIdle(3)
                .thenExecute(() -> checkSeveral(helper, core, banks)).thenSucceed();
    }

    private static void checkSeveral(GameTestHelper helper, BlockPos core, List<BlockPos> banks) {
        long total = NetworkGameTests.controllerEntity(helper, core).buffer().stored();
        helper.assertValueEqual(total, 10_000L, "the controller full");
        long full = 0;
        for (BlockPos bank : banks) {
            long stored = helper.getBlockEntity(bank, EnergyCellBlockEntity.class).cellStore().stored();
            total += stored;
            if (stored == 140_000L) {
                full++;
            }
        }
        helper.assertValueEqual(full, 2L, "two cells full");
        helper.assertValueEqual(total, 300_000L, "no FE lost or doubled");
    }

    /**
     * A version-5 controller with 200,000 FE whose one bank was broken before the controller's first fill (air at its
     * position) and whose other bank lies in a chunk that is not loaded: no FE moves, the controller keeps all of it,
     * the broken bank's entry goes and the unloaded one stays for a later fill.
     */
    private static void bankGoneOrUnloaded(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        BlockPos broken = core.east();
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        long brokenPos = helper.absolutePos(broken).asLong();
        long unloadedPos = helper.absolutePos(core).offset(100_000, 0, 100_000).asLong();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, core).cards().usable(), 4, "first fill done"))
                .thenExecute(() -> {
                    helper.assertFalse(helper.getLevel().isLoaded(BlockPos.of(unloadedPos)), "far chunk not loaded");
                    int bankKind = ModuleKind.CAPACITOR_BANK.index();
                    loadOld(helper, core, 200_000L, new long[]{brokenPos, unloadedPos}, new int[]{bankKind, bankKind});
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, core).expansions(ModuleKind.CAPACITOR_BANK), 0,
                        "the fill dropped the banks"))
                .thenExecute(() -> {
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
                    helper.assertValueEqual(controller.buffer().stored(), 200_000L, "all FE kept in the controller");
                    long[] left = controller.saveWithoutMetadata(helper.getLevel().registryAccess())
                            .getLongArray(StorageSaveFormats.BANK_CELLS).orElse(new long[0]);
                    helper.assertValueEqual(left.length, 1, "only the unloaded bank waits");
                    helper.assertValueEqual(left[0], unloadedPos, "the unloaded bank");
                }).thenSucceed();
    }

    /** Loads {@code core}'s controller as data version 5 saved it, with these FE and modules, and asks for a fill. */
    private static void loadOld(GameTestHelper helper, BlockPos core, long energy, long[] members, int[] kinds) {
        ServerLevel level = helper.getLevel();
        ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
        CompoundTag saved = controller.saveWithoutMetadata(level.registryAccess());
        saved.putInt(SaveFormat.VERSION_KEY, 5);
        saved.putLong("energy", energy);
        saved.putLongArray("members", members);
        saved.putIntArray("kinds", kinds);
        controller.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved));
        helper.assertValueEqual(controller.expansions(ModuleKind.CAPACITOR_BANK), members.length,
                "listed until the fill");
        controller.requestDetect();
    }

    private static void checkOverflow(GameTestHelper helper, BlockPos core, BlockPos bank) {
        ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
        EnergyCellBlockEntity cell = helper.getBlockEntity(bank, EnergyCellBlockEntity.class);
        helper.assertValueEqual(cell.cellStore().stored(), 140_000L, "the cell full");
        helper.assertValueEqual(controller.buffer().stored(), 60_000L, "the rest stays in the controller");
        helper.assertValueEqual(controller.buffer().stored() + cell.cellStore().stored(), 200_000L, "no FE lost");
    }

    /**
     * The bank's ids: the block and item ids resolve to the tier-1 cell, a placed bank's block state (with its old
     * {@code load} property) decodes to the cell, a stack of bank items loads as cells with its count, and a storage
     * cell's palette holding both the old and the new id loads as one key with both counts.
     */
    private static void bankIds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Identifier old = RetiredIds.CAPACITOR_BANK;
        helper.assertTrue(BuiltInRegistries.BLOCK.getValue(old) == EnergyContent.TIER_1.get(), "block id");
        helper.assertTrue(BuiltInRegistries.ITEM.getValue(old).equals(EnergyContent.TIER_1.get().asItem()), "item id");
        DynamicOps<Tag> ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);

        CompoundTag state = new CompoundTag();
        state.putString("Name", old.toString());
        CompoundTag properties = new CompoundTag();
        properties.putString("load", "2");
        state.put("Properties", properties);
        BlockState decoded = BlockState.CODEC.parse(NbtOps.INSTANCE, state).getOrThrow();
        helper.assertTrue(decoded.equals(EnergyContent.TIER_1.get().defaultBlockState()),
                "placed bank is a tier-1 cell");

        CompoundTag stack = new CompoundTag();
        stack.putString("id", old.toString());
        stack.putInt("count", 5);
        ItemStack items = ItemStack.CODEC.parse(ops, stack).getOrThrow();
        helper.assertTrue(items.is(EnergyContent.TIER_1.get().asItem()), "bank items are cells");
        helper.assertValueEqual(items.getCount(), 5, "count kept");

        StackKey cellKey = ItemKey.of(EnergyContent.TIER_1.get().asItem());
        KeyPalette<StackKey> palette = new KeyPalette<>(List.of(cellKey, cellKey), new int[]{0, 1}, new long[]{7, 5});
        CompoundTag saved = (CompoundTag) KeyPalette.codec(StackKey.CODEC).encodeStart(ops, palette).getOrThrow();
        ListTag keys = saved.getListOrEmpty("keys");
        CompoundTag oldKey = keys.getCompoundOrEmpty(1);
        oldKey.putString("id", old.toString());
        KeyCounts<StackKey> counts = new KeyCounts<>(KeyRegistry.stackKeys());
        counts.load(KeyPalette.codec(StackKey.CODEC).parse(ops, saved).getOrThrow());
        helper.assertValueEqual(counts.kinds(), 1, "one key");
        helper.assertValueEqual(counts.count(counts.registry().intern(cellKey)), 12L, "both counts kept");
        helper.succeed();
    }

    /** With {@code energy.cells.tier1.*} set to 50,000 and 700, a tier-1 cell holds 50,000 and moves 700 at once. */
    private static void fromConfig(GameTestHelper helper) {
        BlockPos pos = new BlockPos(2, 1, 2);
        EnergyCellBlockEntity cell = EnergyCellGameTests.cell(helper, pos, EnergyContent.TIER_1.get());
        helper.startSequence().thenIdle(1).thenExecute(() -> {
            helper.assertValueEqual(cell.cellStore().capacity(), 50_000L, "capacity from config");
            helper.assertValueEqual(cell.tier().maxTransfer(), 700, "transfer from config");
            try (var transaction = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
                helper.assertValueEqual(cell.energyInput().insert(5_000, transaction), 700, "one transfer");
            }
        }).thenSucceed();
    }

    /** Takes the block entity at {@code pos} out of its chunk, as an unload does, and returns its saved data. */
    private static CompoundTag unload(GameTestHelper helper, BlockPos pos) {
        BlockPos abs = helper.absolutePos(pos);
        BlockEntity entity = helper.getLevel().getBlockEntity(abs);
        if (entity == null) {
            throw helper.assertionException(Component.literal("Nothing to unload at " + pos.toShortString()));
        }
        CompoundTag tag = entity.saveWithFullMetadata(helper.getLevel().registryAccess());
        entity.onChunkUnloaded();
        helper.getLevel().getChunkAt(abs).removeBlockEntity(abs);
        return tag;
    }

    /** Loads the block entity at {@code pos} from {@code tag} into its chunk, as a chunk load does. */
    private static BlockEntity load(GameTestHelper helper, BlockPos pos, CompoundTag tag) {
        BlockPos abs = helper.absolutePos(pos);
        BlockEntity loaded = BlockEntity.loadStatic(abs, helper.getLevel().getBlockState(abs), tag,
                helper.getLevel().registryAccess());
        if (loaded == null) {
            throw helper.assertionException(Component.literal("Nothing loaded at " + pos.toShortString()));
        }
        helper.getLevel().getChunkAt(abs).addAndRegisterBlockEntity(loaded);
        return loaded;
    }
}
