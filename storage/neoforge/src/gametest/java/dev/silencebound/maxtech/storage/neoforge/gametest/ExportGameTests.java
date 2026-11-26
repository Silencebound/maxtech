package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlockEntity;
import dev.silencebound.maxtech.storage.display.DisplaySourceKind;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.terminal.TerminalBlockEntity;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.terminal.export.ExportOutputs;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * The inventory export's server outputs (M7C item 10): the written book (refusals, the book and quill used up, pages
 * bounded) and the labelled display (access, live count markup). A terminal at x = 0, an owned, powered controller at x
 * = 1, a test storage device at x = 2 and a display panel north of the controller.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ExportGameTests {
    private static final String[] NAMES = {"export_book_refusals", "export_book_pages_bounded",
            "export_display_labelled", "export_display_partial"};
    private static final BlockPos TERMINAL = NetworkGameTests.at(0);
    private static final BlockPos CORE = NetworkGameTests.at(1);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(NAMES[0], ExportGameTests::bookRefusals);
        register(NAMES[1], ExportGameTests::bookPagesBounded);
        register(NAMES[2], ExportGameTests::displayLabelled);
        register(NAMES[3], ExportGameTests::displayPartial);
    }

    private ExportGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(helper -> {
            ExportOutputs.resetCooldowns();
            test.accept(helper);
        }));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("export"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 200);
        }
    }

    /** Terminal, controller owned by {@code owner}, storage. */
    private static void setUp(GameTestHelper helper, ServerPlayer owner) {
        NetworkGameTests.controller(helper, 1);
        NetworkGameTests.controllerEntity(helper, CORE).setOwner(OwnerRecord.of(owner));
        NetworkGameTests.power(helper, CORE, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(TERMINAL, TerminalContent.TERMINAL.get());
    }

    private static ServerPlayer player(GameTestHelper helper, String name) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), name);
        player.setPos(Vec3.atCenterOf(helper.absolutePos(TERMINAL).above()));
        return player;
    }

    private static TerminalMenu open(GameTestHelper helper, ServerPlayer player, int containerId) {
        TerminalMenu menu = TerminalMenu.server(containerId, player.getInventory(),
                helper.getBlockEntity(TERMINAL, TerminalBlockEntity.class), update -> {
                });
        player.containerMenu = menu;
        return menu;
    }

    private static boolean ready(GameTestHelper helper) {
        NetworkState network = helper.getBlockEntity(TERMINAL, TerminalBlockEntity.class).network();
        return network != null && network.isPowered();
    }

    private static int count(ServerPlayer player, Item item) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static ItemStack written(GameTestHelper helper, ServerPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.WRITTEN_BOOK)) {
                return stack;
            }
        }
        throw helper.assertionException(Component.literal("no written book in the inventory"));
    }

    /**
     * A closed menu, a stranger without access and a player without a book and quill get no book; a stranger's book and
     * quill stays as it was. The owner's second export in the same tick waits.
     */
    private static void bookRefusals(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "export_owner");
        ServerPlayer stranger = player(helper, "export_stranger");
        setUp(helper, owner);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(ready(helper), "network online"))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, CORE);
                    NetworkGameTests.insert(network, ItemKey.of(Items.STICK), 64);
                    helper.assertValueEqual(ExportOutputs.Result.NO_MENU,
                            ExportOutputs.writeBook(owner, 3, true, List.of()).result(), "no open menu");

                    stranger.getInventory().add(new ItemStack(Items.WRITABLE_BOOK));
                    open(helper, stranger, 4);
                    helper.assertValueEqual(ExportOutputs.Result.NO_ACCESS,
                            ExportOutputs.writeBook(stranger, 4, true, List.of()).result(), "a stranger");
                    helper.assertValueEqual(1, count(stranger, Items.WRITABLE_BOOK), "the stranger's book and quill");
                    helper.assertValueEqual(0, count(stranger, Items.WRITTEN_BOOK), "no book for the stranger");

                    open(helper, owner, 5);
                    helper.assertValueEqual(ExportOutputs.Result.NO_BOOK,
                            ExportOutputs.writeBook(owner, 5, true, List.of()).result(), "no book and quill");
                    owner.getInventory().add(new ItemStack(Items.WRITABLE_BOOK));
                    owner.getInventory().add(new ItemStack(Items.WRITABLE_BOOK));
                    helper.assertValueEqual(ExportOutputs.Result.BOOK_WRITTEN,
                            ExportOutputs.writeBook(owner, 5, true, List.of()).result(), "the owner's book");
                    helper.assertValueEqual(ExportOutputs.Result.TOO_SOON,
                            ExportOutputs.writeBook(owner, 5, true, List.of()).result(), "a second book at once");
                    helper.assertValueEqual(1, count(owner, Items.WRITABLE_BOOK), "one book and quill used");
                    helper.assertValueEqual(1, count(owner, Items.WRITTEN_BOOK), "one book written");

                    // Logging out forgets the cooldown, so the maps keep only players on the server.
                    NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(owner));
                    helper.assertFalse(ExportOutputs.remembers(owner.getUUID()), "cooldown forgotten on logout");
                    helper.assertValueEqual(ExportOutputs.Result.BOOK_WRITTEN,
                            ExportOutputs.writeBook(owner, 5, true, List.of()).result(), "a book after the relog");
                }).thenSucceed();
    }

    /**
     * More kinds than a book holds: the book stops at its page bound and counts the rest; the client's key list is
     * written in its order with the network's counts, leaving out what the network does not hold.
     */
    private static void bookPagesBounded(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "export_pages_owner");
        setUp(helper, owner);
        int kinds = ExportOutputs.MAX_BOOK_ENTRIES + 20;
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(ready(helper), "network online"))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, CORE);
                    int stored = 0;
                    for (Item item : BuiltInRegistries.ITEM) {
                        if (stored >= kinds) {
                            break;
                        }
                        if (!item.equals(Items.AIR) && NetworkGameTests.insert(network, ItemKey.of(item), 1) == 1) {
                            stored++;
                        }
                    }
                    helper.assertValueEqual(kinds, stored, "kinds stored");
                    NetworkGameTests.insert(network, FluidKey.of(Fluids.WATER), 2000);
                    owner.getInventory().add(new ItemStack(Items.WRITABLE_BOOK));
                    open(helper, owner, 6);
                    ExportOutputs.Outcome outcome = ExportOutputs.writeBook(owner, 6, true, List.of());
                    helper.assertValueEqual(ExportOutputs.Result.BOOK_WRITTEN, outcome.result(), "written");
                    helper.assertValueEqual(ExportOutputs.MAX_BOOK_ENTRIES, outcome.count(), "entries written");
                    WrittenBookContent content = Objects
                            .requireNonNull(written(helper, owner).get(DataComponents.WRITTEN_BOOK_CONTENT));
                    helper.assertValueEqual(ExportOutputs.MAX_PAGES, content.pages().size(), "pages bounded");
                    String last = content.pages().getLast().raw().getString();
                    helper.assertTrue(last.contains(String.valueOf(kinds + 1 - ExportOutputs.MAX_BOOK_ENTRIES)),
                            "the last page counts the rest: " + last);
                    // Water (2,000) is the largest amount, so it comes first.
                    helper.assertTrue(content.pages().getFirst().raw().getString().startsWith("2,000"),
                            "most first: " + content.pages().getFirst().raw().getString());

                    ExportOutputs.resetCooldowns();
                    owner.getInventory().add(new ItemStack(Items.WRITABLE_BOOK));
                    owner.getInventory().removeItem(written(helper, owner));
                    List<StackKey> shown = List.of(ItemKey.of(Items.BARRIER), FluidKey.of(Fluids.WATER),
                            FluidKey.of(Fluids.WATER));
                    outcome = ExportOutputs.writeBook(owner, 6, false, shown);
                    helper.assertValueEqual(1, outcome.count(), "only the water the network holds, once");
                    content = Objects.requireNonNull(written(helper, owner).get(DataComponents.WRITTEN_BOOK_CONTENT));
                    helper.assertValueEqual(1, content.pages().size(), "one page");
                }).thenSucceed();
    }

    /**
     * The display labelled "stock" gets one icon and live count per item, items only; an unknown label and a stranger
     * change nothing.
     */
    private static void displayLabelled(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "export_display_owner");
        ServerPlayer stranger = player(helper, "export_display_stranger");
        setUp(helper, owner);
        // Beside the device, not the core: the core's two root links go to the terminal and the device.
        DisplayPanelBlockEntity panel = DisplayGameTests.panel(helper, NetworkGameTests.at(2).north(), Direction.NORTH);
        panel.setOwner(OwnerRecord.of(owner));
        panel.setLabel(DeviceLabel.of("Stock", null));
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(ready(helper), "network online");
            helper.assertTrue(NetworkGameTests.state(helper, CORE).labels().has("stock"), "panel joined");
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, CORE);
            NetworkGameTests.insert(network, ItemKey.of(Items.STICK), 64);
            NetworkGameTests.insert(network, ItemKey.of(Items.TORCH), 16);
            NetworkGameTests.insert(network, FluidKey.of(Fluids.WATER), 1000);
            String before = panel.settings().text();

            open(helper, stranger, 7);
            helper.assertValueEqual(ExportOutputs.Result.NO_ACCESS,
                    ExportOutputs.toDisplay(stranger, 7, "stock", true, List.of()).result(), "a stranger");
            open(helper, owner, 8);
            helper.assertValueEqual(ExportOutputs.Result.NO_DISPLAY,
                    ExportOutputs.toDisplay(owner, 8, "nothing", true, List.of()).result(), "an unknown label");
            helper.assertValueEqual(before, panel.settings().text(), "nothing written yet");
            helper.assertValueEqual(List.of(DeviceLabel.of("Stock", null)), ExportOutputs.displayLabels(owner, network),
                    "the picker lists the panel");

            ExportOutputs.Outcome outcome = ExportOutputs.toDisplay(owner, 8, "stock", false, new ArrayList<>(
                    List.of(ItemKey.of(Items.TORCH), FluidKey.of(Fluids.WATER), ItemKey.of(Items.STICK))));
            helper.assertValueEqual(ExportOutputs.Result.DISPLAY_WRITTEN, outcome.result(), "written");
            helper.assertValueEqual(2, outcome.count(), "two items, the fluid left out");
            helper.assertValueEqual(DisplaySourceKind.TEXT, panel.settings().source(), "text source");
            helper.assertValueEqual(
                    "[item:minecraft:torch] {count:minecraft:torch}\n[item:minecraft:stick] {count:minecraft:stick}",
                    panel.settings().text(), "markup");
        }).thenSucceed();
    }

    /**
     * Two displays labelled "stock", one the player owns and one a stranger owns: the player's is written, the
     * stranger's is left alone, and the answer names both numbers.
     */
    private static void displayPartial(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "export_partial_owner");
        ServerPlayer stranger = player(helper, "export_partial_stranger");
        setUp(helper, owner);
        DisplayPanelBlockEntity mine = DisplayGameTests.panel(helper, NetworkGameTests.at(2).north(), Direction.NORTH);
        mine.setOwner(OwnerRecord.of(owner));
        mine.setLabel(DeviceLabel.of("Stock", null));
        DisplayPanelBlockEntity theirs = DisplayGameTests.panel(helper, NetworkGameTests.at(2).south(),
                Direction.SOUTH);
        theirs.setOwner(OwnerRecord.of(stranger));
        theirs.setLabel(DeviceLabel.of("Stock", null));
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(ready(helper), "network online");
            helper.assertValueEqual(2, NetworkGameTests.state(helper, CORE).labels().devices("stock").size(),
                    "both panels joined");
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, CORE);
            NetworkGameTests.insert(network, ItemKey.of(Items.STICK), 64);
            String before = theirs.settings().text();
            open(helper, owner, 9);
            ExportOutputs.Outcome outcome = ExportOutputs.toDisplay(owner, 9, "stock", true, List.of());
            helper.assertValueEqual(ExportOutputs.Result.DISPLAY_PARTIAL, outcome.result(), "partly written");
            helper.assertValueEqual(1, outcome.written(), "displays written");
            helper.assertValueEqual(1, outcome.refused(), "displays refused");
            helper.assertValueEqual("[item:minecraft:stick] {count:minecraft:stick}", mine.settings().text(),
                    "the player's display");
            helper.assertValueEqual(before, theirs.settings().text(), "the stranger's display untouched");
        }).thenSucceed();
    }
}
