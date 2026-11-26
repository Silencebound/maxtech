package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.InfoStack;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.info.EnergyInfo;
import dev.silencebound.maxtech.lib.info.InfoEntries;
import dev.silencebound.maxtech.lib.info.InfoEntry;
import dev.silencebound.maxtech.lib.neoforge.compat.jade.BlockInfoLines;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.CellInventory;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.energy.EnergyContent;
import dev.silencebound.maxtech.storage.network.Glance;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.jspecify.annotations.Nullable;

/**
 * The compact overlay (B09 item 7, owner 2026-10-17): what Jade gets for a drive, a controller, a cable, an energy
 * cell, a port and a terminal part. Compact is at most three entries, the status first, then the block's own number as
 * a bar, pips or an icon; details (sneaking) add the text lines, which compact never shows.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class InfoGlanceGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final String GLANCE = "info_glance_compact";
    private static final String DRIVE_CACHE = "info_glance_drive_cache";
    private static final BlockPos CORE = new BlockPos(1, 1, 5);
    private static final BlockPos DRIVE = new BlockPos(3, 1, 6);
    private static final BlockPos CELL = new BlockPos(3, 1, 4);
    private static final BlockPos PORT = new BlockPos(4, 1, 4);
    private static final BlockPos PART_CABLE = new BlockPos(2, 1, 5);
    private static final BlockPos END_CABLE = new BlockPos(5, 1, 5);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(GLANCE, InfoGlanceGameTests::compact);
        register(DRIVE_CACHE, InfoGlanceGameTests::driveCache);
    }

    private InfoGlanceGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("info_glance"),
                StorageTestEnvironment.plain());
        StorageTests.test(event, plain, GLANCE, STRUCTURE, 400);
        StorageTests.test(event, plain, DRIVE_CACHE, STRUCTURE, 400);
    }

    private static void compact(GameTestHelper helper) {
        helper.setBlock(CORE, ControllerContent.CONTROLLER.get());
        for (int x = 2; x <= 5; x++) {
            ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(new BlockPos(x, 1, 5)),
                    CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        }
        DriveBlockEntity drive = CellGameTests.drive(helper, DRIVE);
        drive.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        EnergyCellGameTests.cell(helper, CELL, EnergyContent.TIER_1.get());
        helper.setBlock(PORT, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.NORTH));
        if (!(helper.getBlockEntity(PART_CABLE, ConduitBlockEntity.class).conduit()
                .state() instanceof CableParts parts)) {
            throw helper.assertionException(Component.literal("Storage cable without parts state"));
        }
        helper.assertTrue(parts.place(Direction.UP, TerminalContent.TERMINAL_PART, null,
                new ItemStack(TerminalContent.TERMINAL_PART_ITEM.get())), "terminal part not placed");
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, CORE);
            helper.assertTrue(drive.network() == network, "the drive joined");
            CellGameTests.insert(CellGameTests.mounted(helper, drive, 0), ItemKey.of(Items.COBBLESTONE), 100);
        }).thenExecute(() -> NetworkGameTests.power(helper, CORE, 100_000)).thenWaitUntil(() -> {
            // Drive: status, fill bar, stored items.
            check(helper, DRIVE, null, List.of(InfoEntry.Kind.STATUS, InfoEntry.Kind.PROGRESS, InfoEntry.Kind.STACKS),
                    NetworkInfo.BYTES_KEY);
            helper.assertValueEqual(key(compact(helper, DRIVE, null).getFirst()), Glance.ONLINE.key(), "online");
            // Controller: status, channel bar, energy bar.
            check(helper, CORE, null, List.of(InfoEntry.Kind.STATUS, InfoEntry.Kind.PROGRESS, InfoEntry.Kind.PROGRESS),
                    ControllerContent.CLUSTER_KEY);
            helper.assertValueEqual(key(compact(helper, CORE, null).get(1)), NetworkInfo.CHANNELS_KEY, "channel bar");
            helper.assertValueEqual(compact(helper, CORE, null).get(2).sprite(), Optional.of(EnergyInfo.SPRITE),
                    "the energy bar's bolt");
            // Cable: status and channel bar; the pips with the details.
            check(helper, END_CABLE, null, List.of(InfoEntry.Kind.STATUS, InfoEntry.Kind.PROGRESS),
                    NetworkInfo.CABLE_GLANCE_KEY);
            // Energy cell: status and Lib's stored-energy bar.
            check(helper, CELL, null, List.of(InfoEntry.Kind.STATUS, InfoEntry.Kind.PROGRESS), EnergyInfo.STORED_KEY);
            helper.assertValueEqual(key(compact(helper, CELL, null).get(1)), EnergyInfo.BAR_KEY, "energy bar");
            // Port: status and mode icon (its filter is empty, so no item icons).
            check(helper, PORT, null, List.of(InfoEntry.Kind.STATUS, InfoEntry.Kind.ICON), NetworkInfo.PORT_DETAIL_KEY);
            // Terminal part: status and its name, nothing else.
            BlockPos abs = helper.absolutePos(PART_CABLE);
            check(helper, PART_CABLE, abs.getCenter().add(0, 0.5, 0),
                    List.of(InfoEntry.Kind.STATUS, InfoEntry.Kind.TEXT), NetworkInfo.NETWORK_KEY);
        }).thenSucceed();
    }

    /**
     * A drive's largest kinds are summed over its cells (stone in two cells counts once, with both amounts, even where
     * one cell alone would not rank it) and cached: asking again and again counts nothing new until a cell changes, and
     * a change is counted at most once per {@link DriveBlockEntity#GLANCE_REFRESH_TICKS}.
     */
    private static void driveCache(GameTestHelper helper) {
        helper.setBlock(CORE, ControllerContent.CONTROLLER.get());
        DriveBlockEntity drive = CellGameTests.drive(helper, DRIVE.north());
        drive.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        drive.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        BlockInfoRequest request = new BlockInfoRequest(helper.getLevel(), helper.absolutePos(DRIVE.north()), null,
                null, BlockInfoRequest.Detail.BRIEF);
        int[] before = new int[1];
        helper.startSequence().thenWaitUntil(() -> CellGameTests.mounted(helper, drive, 1)).thenExecute(() -> {
            CellInventory first = CellGameTests.mounted(helper, drive, 0);
            CellInventory second = CellGameTests.mounted(helper, drive, 1);
            CellGameTests.insert(first, ItemKey.of(Items.STONE), 10);
            for (Item item : List.of(Items.COBBLESTONE, Items.DIRT, Items.SAND, Items.GRAVEL, Items.OAK_LOG)) {
                CellGameTests.insert(first, ItemKey.of(item), 20);
            }
            CellGameTests.insert(second, ItemKey.of(Items.STONE), 100);
            List<InfoStack> top = drive.glanceTop();
            helper.assertValueEqual(top.getFirst(), new InfoStack(ItemKey.of(Items.STONE), 110), "stone summed");
            helper.assertValueEqual(top.size(), DriveBlockEntity.GLANCE_STACKS, "at most five kinds");
            before[0] = drive.glanceRecounts();
            for (int i = 0; i < 50; i++) {
                drive.addInfo(request, new InfoEntries());
            }
            helper.assertValueEqual(drive.glanceRecounts(), before[0], "recounts without a change");
            CellGameTests.insert(first, ItemKey.of(Items.STONE), 5);
            for (int i = 0; i < 50; i++) {
                drive.addInfo(request, new InfoEntries());
            }
            helper.assertValueEqual(drive.glanceRecounts(), before[0], "a recount within the refresh time");
        }).thenIdle(DriveBlockEntity.GLANCE_REFRESH_TICKS + 1).thenExecute(() -> {
            for (int i = 0; i < 50; i++) {
                drive.addInfo(request, new InfoEntries());
            }
            helper.assertValueEqual(drive.glanceRecounts(), before[0] + 1, "one recount for the change");
            helper.assertValueEqual(drive.glanceTop().getFirst().amount(), 115L, "the change counted");
        }).thenSucceed();
    }

    /**
     * Compact at {@code pos} has exactly {@code kinds} (so at most three entries, the status first) and no
     * {@code detailKey}; with details the status comes first, the compact entries stay and {@code detailKey} is there.
     */
    private static void check(GameTestHelper helper, BlockPos pos, @Nullable Vec3 hit, List<InfoEntry.Kind> kinds,
            String detailKey) {
        List<InfoEntry> compact = compact(helper, pos, hit);
        List<InfoEntry.Kind> got = new ArrayList<>();
        for (InfoEntry entry : compact) {
            got.add(entry.kind());
        }
        helper.assertValueEqual(got, kinds, "compact entries at " + pos.toShortString() + ": " + texts(compact));
        helper.assertTrue(compact.size() <= 3, "more than three compact entries at " + pos.toShortString());
        helper.assertFalse(keys(compact).contains(detailKey), "a detail in compact at " + pos.toShortString());
        List<InfoEntry> full = collect(helper, pos, hit, true);
        // The block's own glance comes first; Lib's energy entries follow the block's details.
        helper.assertValueEqual(full.getFirst(), compact.getFirst(), "details start with the status");
        helper.assertTrue(full.containsAll(compact), "details keep the compact entries at " + pos.toShortString());
        helper.assertTrue(keys(full).contains(detailKey),
                "no " + detailKey + " in the details at " + pos.toShortString() + ": " + texts(full));
    }

    private static List<InfoEntry> compact(GameTestHelper helper, BlockPos pos, @Nullable Vec3 hit) {
        return collect(helper, pos, hit, false);
    }

    private static List<InfoEntry> collect(GameTestHelper helper, BlockPos pos, @Nullable Vec3 hit, boolean details) {
        return BlockInfoLines.collect(helper.getLevel(), helper.absolutePos(pos), hit == null ? null : Direction.UP,
                hit, details);
    }

    private static List<String> keys(List<InfoEntry> entries) {
        List<String> keys = new ArrayList<>();
        for (InfoEntry entry : entries) {
            keys.add(key(entry));
        }
        return keys;
    }

    private static String key(InfoEntry entry) {
        return entry.text().getContents() instanceof TranslatableContents translatable
                ? translatable.getKey()
                : entry.text().getString();
    }

    private static String texts(List<InfoEntry> entries) {
        List<String> texts = new ArrayList<>();
        for (InfoEntry entry : entries) {
            texts.add(entry.kind() + " " + Component.empty().append(entry.text()).getString());
        }
        return String.join(" | ", texts);
    }
}
