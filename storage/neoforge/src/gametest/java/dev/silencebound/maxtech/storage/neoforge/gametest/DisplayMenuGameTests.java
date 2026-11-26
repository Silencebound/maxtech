package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.condition.ConditionMenu;
import dev.silencebound.maxtech.storage.display.DisplayContent;
import dev.silencebound.maxtech.storage.display.DisplayMenu;
import dev.silencebound.maxtech.storage.display.DisplayMode;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlockEntity;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.display.DisplaySourceKind;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The display settings GUI's server side: every button changes the whole wall, the ghost slot sets the counted item and
 * its first tag, strangers are refused, and the graph modes need the {@code holo_graphs} research (skipped while the
 * player's team lacks it; offered with research off).
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class DisplayMenuGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String SETTINGS = "display_menu_settings";
    private static final String GRAPHS = "display_menu_graphs_without_research";

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        FUNCTIONS.register(SETTINGS, () -> StorageTestEnvironment.clearing(DisplayMenuGameTests::settings));
        FUNCTIONS.register(GRAPHS, () -> StorageTestEnvironment.clearing(DisplayMenuGameTests::graphs));
    }

    private DisplayMenuGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        test(event, SETTINGS, event.registerEnvironment(MaxtechStorage.id(SETTINGS), StorageTestEnvironment.plain()));
        test(event, GRAPHS, event.registerEnvironment(MaxtechStorage.id(GRAPHS),
                new StorageTestEnvironment("research.preset", "off", false)));
    }

    private static void test(RegisterGameTestsEvent event, String name,
            net.minecraft.core.Holder<net.minecraft.gametest.framework.TestEnvironmentDefinition<?>> environment) {
        event.registerTest(MaxtechStorage.id(name),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                        new TestData<>(environment, STRUCTURE, 200, 0, true)));
    }

    /** Two panels side by side (one wall), owned by {@code owner}; returns the first. */
    private static DisplayPanelBlockEntity wall(GameTestHelper helper, ServerPlayer owner) {
        DisplayPanelBlockEntity first = DisplayGameTests.panel(helper, new BlockPos(2, 1, 1), Direction.NORTH);
        DisplayPanelBlockEntity second = DisplayGameTests.panel(helper, new BlockPos(3, 1, 1), Direction.NORTH);
        first.setOwner(OwnerRecord.of(owner));
        second.setOwner(OwnerRecord.of(owner));
        return first;
    }

    private static DisplayPanelBlockEntity second(GameTestHelper helper) {
        return helper.getBlockEntity(new BlockPos(3, 1, 1), DisplayPanelBlockEntity.class);
    }

    private static void settings(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "display_owner");
        DisplayPanelBlockEntity panel = wall(helper, owner);
        helper.startSequence()
                // A north-facing wall is anchored at its east end; the GUI is opened on the other panel.
                .thenWaitUntil(() -> helper.assertValueEqual(panel.anchor(), second(helper).getBlockPos(),
                        "the wall's anchor"))
                .thenExecute(() -> {
                    helper.assertFalse(ResearchUnlocks.isUnlocked(owner, DisplayContent.HOLO_GRAPHS),
                            "a new team has the graphs");
                    DisplayMenu menu = DisplayMenu.forDevice(1, owner.getInventory(), panel);
                    helper.assertTrue(menu.clickMenuButton(owner, DisplayMenu.CYCLE_MODE), "mode click");
                    helper.assertValueEqual(panel.settings().mode(), DisplayMode.VALUE, "mode without research");
                    menu.clickMenuButton(owner, DisplayMenu.CYCLE_SOURCE);
                    menu.clickMenuButton(owner, DisplayMenu.CYCLE_WINDOW);
                    menu.clickMenuButton(owner, DisplayMenu.CYCLE_ACCENT);
                    DisplaySettings expected = DisplaySettings.DEFAULT.withSource(DisplaySourceKind.POWER_STORED.next())
                            .withWindow(120).withAccent(1);
                    helper.assertValueEqual(panel.settings(), expected, "settings after the buttons");
                    helper.assertValueEqual(second(helper).settings(), expected, "the wall's anchor's settings");
                    menu.setCarried(new ItemStack(Items.OAK_LOG));
                    menu.clicked(0, 0, ContainerInput.PICKUP, owner);
                    menu.setCarried(ItemStack.EMPTY);
                    helper.assertTrue(panel.settings().item().isPresent(), "no item from the ghost slot");
                    helper.assertValueEqual(panel.settings().tag(),
                            Optional.of(ConditionMenu.tags(Items.OAK_LOG).get(0)), "first tag");
                    menu.clickMenuButton(owner, DisplayMenu.NEXT_TAG);
                    helper.assertValueEqual(panel.settings().tag(),
                            Optional.of(ConditionMenu.tags(Items.OAK_LOG).get(1)), "next tag");
                    menu.broadcastChanges();
                    helper.assertTrue(menu.graphsLocked(), "the menu shows the graphs as locked");
                    helper.assertTrue(menu.countedItem().is(Items.OAK_LOG), "the ghost slot shows the item");
                    ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "display_stranger");
                    DisplaySettings before = panel.settings();
                    helper.assertFalse(DisplayMenu.forDevice(2, stranger.getInventory(), panel)
                            .clickMenuButton(stranger, DisplayMenu.CYCLE_SOURCE), "a stranger's click");
                    helper.assertValueEqual(panel.settings(), before, "settings after a stranger's click");
                }).thenSucceed();
    }

    private static void graphs(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "display_owner");
        DisplayPanelBlockEntity panel = wall(helper, owner);
        DisplayMenu menu = DisplayMenu.forDevice(1, owner.getInventory(), panel);
        menu.clickMenuButton(owner, DisplayMenu.CYCLE_MODE);
        helper.assertValueEqual(panel.settings().mode(), DisplayMode.LINE, "first graph mode");
        menu.clickMenuButton(owner, DisplayMenu.CYCLE_MODE);
        helper.assertValueEqual(panel.settings().mode(), DisplayMode.BAR, "second graph mode");
        menu.clickMenuButton(owner, DisplayMenu.CYCLE_MODE);
        helper.assertValueEqual(panel.settings().mode(), DisplayMode.VALUE, "back to the value");
        menu.broadcastChanges();
        helper.assertFalse(menu.graphsLocked(), "graphs locked with research off");
        helper.succeed();
    }
}
