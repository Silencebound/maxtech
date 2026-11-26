package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.CellFilter;
import dev.silencebound.maxtech.storage.cell.CellStorage;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.ControllerMenu;
import dev.silencebound.maxtech.storage.controller.recovery.RecoveryBin;
import dev.silencebound.maxtech.storage.controller.recovery.RecoveryTabPayload;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkCommand;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
// PLATFORM-LEAK: tests drive the network through NeoForge transfer transactions.
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The recovery bin (M7C item 20): void overflow lands in the controller's bin (a rolled-back insert does not);
 * recovering puts back only what fits, for the owner or an admin only; rows expire, merge within a quarter of the keep
 * time and are capped; the bin survives a save; a destroyed cell item's contents land in its network's bin; the admin
 * command lists it.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class RecoveryGameTests {
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    /** A small item cell with one memory module I holds this much of one kind: (1 KB - 8 bytes) * 8 items. */
    private static final long SMALL_CELL_ITEMS = (1024 - 8) * 8;
    private static final String[] NAMES = {"recovery_void_to_bin", "recovery_recover_fits", "recovery_expiry",
            "recovery_cap", "recovery_saved", "recovery_cell_destroyed", "recovery_command", "recovery_marks_saved",
            "recovery_home_saved"};

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function("recovery_void_to_bin", StorageTestEnvironment.clearing(RecoveryGameTests::voidToBin));
        StorageTests.function("recovery_recover_fits", StorageTestEnvironment.clearing(RecoveryGameTests::recoverFits));
        StorageTests.function("recovery_expiry", StorageTestEnvironment.clearing(RecoveryGameTests::expiry));
        StorageTests.function("recovery_cap", StorageTestEnvironment.clearing(RecoveryGameTests::cap));
        StorageTests.function("recovery_saved", StorageTestEnvironment.clearing(RecoveryGameTests::saved));
        StorageTests.function("recovery_cell_destroyed",
                StorageTestEnvironment.clearing(RecoveryGameTests::cellDestroyed));
        StorageTests.function("recovery_command", StorageTestEnvironment.clearing(RecoveryGameTests::command));
        StorageTests.function("recovery_marks_saved", StorageTestEnvironment.clearing(RecoveryGameTests::marksSaved));
        StorageTests.function("recovery_home_saved", StorageTestEnvironment.clearing(RecoveryGameTests::homeSaved));
    }

    private RecoveryGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("recovery"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 200);
        }
    }

    // ---- set-up

    /** A powered controller at x 1 placed by {@code owner} and a drive at x 2 holding {@code cell}. */
    private static ControllerBlockEntity setUp(GameTestHelper helper, ServerPlayer owner, ItemStack cell) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        BlockState state = helper.getBlockState(core);
        state.getBlock().setPlacedBy(helper.getLevel(), helper.absolutePos(core), state, owner,
                new ItemStack(ControllerContent.CONTROLLER.item().get()));
        NetworkGameTests.power(helper, core, 10_000_000);
        DriveBlockEntity drive = CellGameTests.drive(helper, NetworkGameTests.at(2));
        drive.insertCell(cell);
        return NetworkGameTests.controllerEntity(helper, core);
    }

    private static ItemStack plainCell() {
        return CellPartsGameTests.cell(CellContent.SMALL_STORAGE_CELL.get(), List.of(CellContent.MEMORY_MODULE_1.get()),
                List.of(), CellFilter.EMPTY);
    }

    private static ItemStack voidCell() {
        return CellPartsGameTests.cell(CellContent.SMALL_STORAGE_CELL.get(), List.of(CellContent.MEMORY_MODULE_1.get()),
                List.of(CellContent.VOID_OVERFLOW_ADDON.get()),
                new CellFilter(List.of(CellFilter.Entry.of(COBBLE)), false));
    }

    private static DriveBlockEntity drive(GameTestHelper helper) {
        return helper.getBlockEntity(NetworkGameTests.at(2), DriveBlockEntity.class);
    }

    /** The core's network, owned and with the drive's cell mounted; fails (to be retried) until then. */
    private static NetworkState ready(GameTestHelper helper, ControllerBlockEntity core, ServerPlayer owner) {
        NetworkState network = core.network();
        if (network == null || !network.owner().isOwner(owner.getUUID())) {
            throw helper.assertionException(Component.literal("the network is not owned yet"));
        }
        CellGameTests.mounted(helper, drive(helper), 0);
        helper.assertTrue(network.nativeAmount(COBBLE) >= 0 && drive(helper).network() == network, "drive not joined");
        return network;
    }

    private static long binned(ControllerBlockEntity core, StackKey key, long now) {
        long sum = 0;
        for (RecoveryBin.Row row : core.recovery().rows(now)) {
            if (row.key().equals(key)) {
                sum += row.amount();
            }
        }
        return sum;
    }

    private static ServerPlayer op(ServerLevel level, String name) {
        return new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), name),
                ClientInformation.createDefault()) {
            @Override
            public PermissionSet permissions() {
                return LevelBasedPermissionSet.GAMEMASTER;
            }
        };
    }

    // ---- tests

    /**
     * Cobblestone inserted into a network whose only cell voids its overflow: the part that did not fit is in the
     * controller's bin; a rolled-back insert of more adds nothing.
     */
    private static void voidToBin(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "recovery_voider");
        ControllerBlockEntity core = setUp(helper, owner, voidCell());
        helper.startSequence().thenWaitUntil(() -> ready(helper, core, owner)).thenExecute(() -> {
            NetworkState network = ready(helper, core, owner);
            helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 9_000), 9_000L, "taken whole");
            long stored = network.nativeAmount(COBBLE);
            long now = helper.getLevel().getGameTime();
            helper.assertValueEqual(binned(core, COBBLE, now), 9_000 - stored, "the overflow is in the bin");
            try (Transaction transaction = Transaction.openRoot()) {
                network.insert(COBBLE, 500, transaction);
            }
            helper.assertValueEqual(binned(core, COBBLE, now), 9_000 - stored, "a rollback adds nothing");
        }).thenSucceed();
    }

    /**
     * 9,000 cobblestone in the bin and room for 8,128: a stranger may not recover; an admin recovers what fits and the
     * rest stays; the owner recovers nothing more while storage is full, then exactly the 500 of room made.
     */
    private static void recoverFits(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "recovery_owner");
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "recovery_stranger");
        ServerPlayer admin = op(helper.getLevel(), "recovery_admin");
        ControllerBlockEntity core = setUp(helper, owner, plainCell());
        helper.startSequence().thenWaitUntil(() -> ready(helper, core, owner)).thenExecute(() -> {
            NetworkState network = ready(helper, core, owner);
            core.voided(COBBLE, 9_000);
            List<RecoveryTabPayload> sent = new ArrayList<>();
            ControllerMenu menu = ControllerMenu.server(4, core, owner, payload -> {
            }, payload -> {
            }, sent::add);
            menu.broadcastChanges();
            helper.assertTrue(!sent.isEmpty() && sent.getLast().mayRecover(), "the owner may recover");
            helper.assertValueEqual(sent.getLast().rows().getFirst().amount(), 9_000L, "the row sent");
            int rowId = sent.getLast().rows().getFirst().id();
            long now = helper.getLevel().getGameTime();
            helper.assertTrue(!menu.clickMenuButton(stranger, ControllerMenu.RECOVER_BUTTON + rowId),
                    "a stranger may not");
            helper.assertValueEqual(binned(core, COBBLE, now), 9_000L, "nothing taken for the stranger");
            core.buffer().receive(1_000_000);
            helper.assertTrue(menu.clickMenuButton(admin, ControllerMenu.RECOVER_BUTTON + rowId), "an admin may");
            helper.assertValueEqual(network.nativeAmount(COBBLE), SMALL_CELL_ITEMS, "what fits went in");
            helper.assertValueEqual(binned(core, COBBLE, now), 9_000 - SMALL_CELL_ITEMS, "the rest stays");
            menu.broadcastChanges();
            helper.assertTrue(!menu.clickMenuButton(owner, ControllerMenu.RECOVER_BUTTON + rowId + 99),
                    "an unknown row is a no-op");
            helper.assertTrue(!menu.clickMenuButton(owner, ControllerMenu.RECOVER_BUTTON + rowId), "storage is full");
            helper.assertValueEqual(binned(core, COBBLE, now), 9_000 - SMALL_CELL_ITEMS, "still there");
            try (Transaction transaction = Transaction.openRoot()) {
                network.extract(COBBLE, 500, transaction);
                transaction.commit();
            }
            core.buffer().receive(1_000_000);
            helper.assertTrue(menu.clickMenuButton(owner, ControllerMenu.RECOVER_BUTTON + rowId), "the owner may");
            helper.assertValueEqual(network.nativeAmount(COBBLE), SMALL_CELL_ITEMS, "full again");
            helper.assertValueEqual(binned(core, COBBLE, now), 9_000 - SMALL_CELL_ITEMS - 500,
                    "the 500 that fit taken");
        }).thenSucceed();
    }

    /**
     * A row lives {@code recovery.keepTicks} after its first loss; a loss within a quarter of that joins it, a later
     * one makes a new row.
     */
    private static void expiry(GameTestHelper helper) {
        int keep = StorageServerConfig.RECOVERY_KEEP_TICKS.getAsInt();
        helper.assertTrue(keep >= 8, "the keep time is long enough to test");
        RecoveryBin bin = new RecoveryBin();
        bin.add(COBBLE, 5, 1000);
        bin.add(COBBLE, 7, 1000 + keep / 4 - 1);
        helper.assertValueEqual(bin.rows(1000).size(), 1, "joined within a quarter");
        helper.assertValueEqual(bin.rows(1000).getFirst().amount(), 12L, "amounts added");
        bin.add(COBBLE, 3, 1000 + keep / 4);
        helper.assertValueEqual(bin.rows(1000 + keep / 4).size(), 2, "a new row after a quarter");
        helper.assertValueEqual(bin.rows(1000 + keep - 1).size(), 2, "both still in time");
        List<RecoveryBin.Row> left = bin.rows(1000 + keep);
        helper.assertValueEqual(left.size(), 1, "the first expired");
        helper.assertValueEqual(left.getFirst().amount(), 3L, "the later row stays");
        helper.assertTrue(bin.rows(1000 + keep / 4 + keep).isEmpty(), "all expired");
        helper.succeed();
    }

    /** More rows than {@code recovery.maxEntries}: the oldest are dropped. */
    private static void cap(GameTestHelper helper) {
        int cap = StorageServerConfig.RECOVERY_MAX_ENTRIES.getAsInt();
        List<StackKey> keys = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (!item.equals(Items.AIR) && keys.size() < cap + 5) {
                keys.add(ItemKey.of(item));
            }
        }
        RecoveryBin bin = new RecoveryBin();
        for (int i = 0; i < keys.size(); i++) {
            bin.add(keys.get(i), 1, 100 + i);
        }
        List<RecoveryBin.Row> rows = bin.rows(100 + keys.size());
        helper.assertValueEqual(rows.size(), cap, "capped");
        helper.assertValueEqual(rows.getFirst().key(), keys.getLast(), "the newest first");
        helper.assertValueEqual(rows.getLast().key(), keys.get(5), "the five oldest dropped");
        helper.succeed();
    }

    /** The bin is saved with the controller and loads back with its rows and times. */
    private static void saved(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "recovery_saver");
        ControllerBlockEntity core = setUp(helper, owner, plainCell());
        helper.startSequence().thenWaitUntil(() -> ready(helper, core, owner)).thenExecute(() -> {
            core.voided(COBBLE, 42);
            long now = helper.getLevel().getGameTime();
            CompoundTag saved = core.saveWithoutMetadata(helper.getLevel().registryAccess());
            ControllerBlockEntity copy = new ControllerBlockEntity(core.getBlockPos(), core.getBlockState());
            copy.loadWithComponents(
                    TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
            List<RecoveryBin.Row> rows = copy.recovery().rows(now);
            helper.assertValueEqual(rows.size(), 1, "one row after a reload");
            helper.assertValueEqual(rows.getFirst().amount(), 42L, "amount");
            helper.assertValueEqual(rows.getFirst().created(), core.recovery().rows(now).getFirst().created(), "time");
        }).thenSucceed();
    }

    /**
     * A cell holding cobblestone, taken out of the drive and burnt in lava as an item: its contents are in its
     * network's bin and gone from the cell save data.
     */
    private static void cellDestroyed(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "recovery_burner");
        ControllerBlockEntity core = setUp(helper, owner, plainCell());
        helper.startSequence().thenWaitUntil(() -> ready(helper, core, owner)).thenExecute(() -> {
            NetworkState network = ready(helper, core, owner);
            helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 300), 300L, "stored");
            ItemStack cell = drive(helper).removeCell();
            long id = CellGameTests.data(helper, cell).id();
            CellStorage storage = CellGameTests.storage(helper);
            helper.assertTrue(storage.contains(id), "the cell keeps its contents out of the drive");
            BlockPos at = helper.absolutePos(NetworkGameTests.at(5));
            ItemEntity entity = new ItemEntity(helper.getLevel(), at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5,
                    cell);
            helper.getLevel().addFreshEntity(entity);
            entity.hurtServer(helper.getLevel(), helper.getLevel().damageSources().lava(), 100);
            helper.assertTrue(entity.isRemoved(), "the item burnt");
            helper.assertValueEqual(binned(core, COBBLE, helper.getLevel().getGameTime()), 300L, "in the bin");
            helper.assertTrue(!storage.contains(id), "gone from the save data");
        }).thenSucceed();
    }

    /** {@code /maxtech network recovery <pos>} lists the bin's rows for an admin. */
    private static void command(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "recovery_listed");
        ControllerBlockEntity core = setUp(helper, owner, plainCell());
        helper.startSequence().thenWaitUntil(() -> ready(helper, core, owner)).thenExecute(() -> {
            core.voided(COBBLE, 7);
            core.voided(ItemKey.of(Items.DIRT), 9);
            BlockPos pos = helper.absolutePos(NetworkGameTests.at(1));
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.register(Commands.literal("maxtech").then(NetworkCommand.build()));
            CommandSourceStack source = op(helper.getLevel(), "recovery_cmd").createCommandSourceStack()
                    .withSuppressedOutput();
            try {
                int listed = dispatcher.execute(
                        "maxtech network recovery " + pos.getX() + " " + pos.getY() + " " + pos.getZ(), source);
                helper.assertValueEqual(listed, 2, "two rows listed");
            } catch (CommandSyntaxException e) {
                throw helper.assertionException(Component.literal(String.valueOf(e.getMessage())));
            }
        }).thenSucceed();
    }

    /**
     * After a row expired (read or pruned), a later loss still asks the controller to save: the expiry must not use up
     * the once-per-save mark.
     */
    private static void marksSaved(GameTestHelper helper) {
        int keep = StorageServerConfig.RECOVERY_KEEP_TICKS.getAsInt();
        RecoveryBin bin = new RecoveryBin();
        helper.assertTrue(bin.add(COBBLE, 5, 1000), "the first loss asks for a save");
        helper.assertTrue(!bin.add(COBBLE, 1, 1001), "a second loss before the save does not");
        bin.saved();
        bin.prune(1000 + keep);
        helper.assertTrue(bin.isEmpty(), "the row expired");
        helper.assertTrue(bin.add(COBBLE, 5, 1000 + keep), "a loss after an expiry asks for a save");
        helper.succeed();
    }

    /** The home controller of a cell in the cells save data survives a save and load. */
    private static void homeSaved(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "recovery_homer");
        ControllerBlockEntity core = setUp(helper, owner, plainCell());
        helper.startSequence().thenWaitUntil(() -> ready(helper, core, owner)).thenExecute(() -> {
            NetworkState network = ready(helper, core, owner);
            helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 10), 10L, "stored");
            ItemStack cell = drive(helper).removeCell();
            long id = CellGameTests.data(helper, cell).id();
            CellStorage storage = CellGameTests.storage(helper);
            String dimension = helper.getLevel().dimension().identifier().toString();
            storage.setHome(id, dimension, 123_456L);
            CellStorage copy = CellStorage.CODEC.encodeStart(NbtOps.INSTANCE, storage)
                    .flatMap(tag -> CellStorage.CODEC.parse(NbtOps.INSTANCE, tag)).getOrThrow();
            helper.assertValueEqual(copy.homeDimension(id), dimension, "dimension");
            helper.assertValueEqual(copy.homePos(id), 123_456L, "position");
        }).thenSucceed();
    }
}
