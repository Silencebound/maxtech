package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.Owned;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.SecurityMenu;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.iface.InterfaceBlockEntity;
import dev.silencebound.maxtech.storage.iface.InterfaceContent;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.terminal.TerminalBlockEntity;
import dev.silencebound.maxtech.storage.display.DisplayMenu;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlockEntity;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.world.NetworkChestBlockEntity;
import dev.silencebound.maxtech.storage.world.NetworkChestMenu;
import dev.silencebound.maxtech.storage.world.WorldContent;
import dev.silencebound.maxtech.storage.world.WorldDeviceBlock;
import dev.silencebound.maxtech.storage.world.WorldDeviceBlockEntity;
import dev.silencebound.maxtech.storage.world.WorldMenu;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The security module (M6c2 area G): with one in the cluster, the core's rules decide what each player may do with the
 * network's terminal, interface and port GUIs and its parts, through one check; without it, owner security decides as
 * before. The rules live on the core, change through the security screen's row clicks (owner only) and travel with the
 * core's item.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
// Networks are compared by identity.
@SuppressWarnings("ReferenceEquality")
public final class ControllerSecurityGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String LEVELS = "controller_security_levels_per_device";
    private static final String SCREEN = "controller_security_screen_and_item";
    private static final String CHEST = "controller_security_chest_and_settings";
    private static final StackKey IRON = ItemKey.of(Items.IRON_INGOT);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(LEVELS, StorageTestEnvironment.clearing(ControllerSecurityGameTests::levelsPerDevice));
        StorageTests.function(SCREEN, StorageTestEnvironment.clearing(ControllerSecurityGameTests::screenAndItem));
        StorageTests.function(CHEST, StorageTestEnvironment.clearing(ControllerSecurityGameTests::chestAndSettings));
    }

    private ControllerSecurityGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("controller_security"),
                StorageTestEnvironment.plain());
        StorageTests.test(event, plain, LEVELS, STRUCTURE, 400);
        StorageTests.test(event, plain, SCREEN, STRUCTURE, 400);
        StorageTests.test(event, plain, CHEST, STRUCTURE, 400);
    }

    /**
     * A controller (owned), a terminal, an interface and a cable with an unowned port part and interface part. Without
     * a security module a stranger may configure only the unowned parts; with one and rules (viewer: view, depositor:
     * deposit, a scoreboard team: full, everyone else: none) the terminal lets the viewer only look and the depositor
     * also put in, every device's configuration needs full access, and the owner keeps full access.
     */
    private static void levelsPerDevice(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = NetworkGameTests.player(level, "sec_owner");
        ServerPlayer viewer = NetworkGameTests.player(level, "sec_viewer");
        ServerPlayer depositor = NetworkGameTests.player(level, "sec_depositor");
        ServerPlayer teammate = NetworkGameTests.player(level,
                "tm_" + Integer.toHexString(level.getRandom().nextInt()));
        ServerPlayer stranger = NetworkGameTests.player(level, "sec_stranger");
        String teamName = teamName(level);
        Scoreboard scoreboard = level.getScoreboard();
        PlayerTeam team = scoreboard.addPlayerTeam(teamName);
        scoreboard.addPlayerToTeam(teammate.getScoreboardName(), team);

        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        BlockPos terminalPos = NetworkGameTests.at(2);
        helper.setBlock(terminalPos, TerminalContent.TERMINAL.get());
        TerminalBlockEntity terminal = helper.getBlockEntity(terminalPos, TerminalBlockEntity.class);
        terminal.setOwner(OwnerRecord.of(owner));
        BlockPos interfacePos = NetworkGameTests.at(3);
        helper.setBlock(interfacePos, InterfaceContent.INTERFACE.get());
        InterfaceBlockEntity iface = helper.getBlockEntity(interfacePos, InterfaceBlockEntity.class);
        iface.setOwner(OwnerRecord.of(owner));
        NetworkGameTests.cables(helper, 4, 4, CableContent.STORAGE_CABLE_TIER_1.type().get());
        CableParts parts = PartGameTests.parts(helper, 4);
        helper.assertTrue(
                parts.place(Direction.UP, PortContent.PORT_PART, null, new ItemStack(PortContent.PORT_PART_ITEM.get())),
                "port part not placed");
        helper.assertTrue(parts.place(Direction.NORTH, InterfaceContent.INTERFACE_PART, null,
                new ItemStack(InterfaceContent.INTERFACE_PART_ITEM.get())), "interface part not placed");
        CablePart port = PartGameTests.part(helper, parts, Direction.UP);
        CablePart ifacePart = PartGameTests.part(helper, parts, Direction.NORTH);
        BlockPos module = core.above();
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, core);
            helper.assertTrue(terminal.network() == network && iface.network() == network, "devices joined");
            helper.assertTrue(port.deviceLink().network() == network && ifacePart.deviceLink().network() == network,
                    "parts joined");
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, core);
            helper.assertValueEqual(network.accessLevel(stranger), AccessLevel.NONE, "stranger without a module");
            helper.assertValueEqual(network.accessLevel(owner), AccessLevel.FULL, "owner without a module");
            helper.assertTrue(!NetworkAccess.canConfigure(stranger, iface), "stranger on the owned interface");
            helper.assertTrue(NetworkAccess.canConfigure(stranger, port), "anyone on an unowned part");
            helper.assertTrue(!terminal.canAccess(viewer), "viewer without a rule");
            ControllerClusterGameTests.expansion(helper, module, ExpansionContent.SECURITY_MODULE);
            ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
            controller.setSecurity(SecuritySettings.DEFAULT
                    .withPlayer(viewer.getUUID(), viewer.getGameProfile().name(), AccessLevel.VIEW)
                    .withPlayer(depositor.getUUID(), depositor.getGameProfile().name(), AccessLevel.DEPOSIT)
                    .withTeam(teamName, AccessLevel.FULL));
        }).thenWaitUntil(
                () -> helper.assertTrue(NetworkGameTests.controllerEntity(helper, core).secured(), "module active"))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, core);
                    helper.assertValueEqual(network.accessLevel(owner), AccessLevel.FULL, "owner");
                    helper.assertValueEqual(network.accessLevel(viewer), AccessLevel.VIEW, "viewer");
                    helper.assertValueEqual(network.accessLevel(depositor), AccessLevel.DEPOSIT, "depositor");
                    helper.assertValueEqual(network.accessLevel(teammate), AccessLevel.FULL, "trusted team");
                    helper.assertValueEqual(network.accessLevel(stranger), AccessLevel.NONE, "stranger");
                    // Terminal: see, deposit, take.
                    helper.assertTrue(
                            terminal.canAccess(viewer) && !terminal.canDeposit(viewer) && !terminal.canTake(viewer),
                            "viewer only looks");
                    helper.assertTrue(terminal.canDeposit(depositor) && !terminal.canTake(depositor),
                            "depositor puts in but takes nothing");
                    helper.assertTrue(terminal.canTake(teammate) && terminal.canTake(owner), "full access takes");
                    helper.assertTrue(!terminal.canAccess(stranger), "stranger sees nothing");
                    // Device screens and parts: full access only, through the network's rules.
                    List<Owned> devices = List.of(iface, port, ifacePart);
                    for (Owned owned : devices) {
                        Object device = owned;
                        helper.assertTrue(NetworkAccess.canConfigure(owner, owned), "owner configures " + device);
                        helper.assertTrue(NetworkAccess.canConfigure(teammate, owned), "team configures " + device);
                        helper.assertTrue(!NetworkAccess.canConfigure(depositor, owned),
                                "depositor configures " + device);
                        helper.assertTrue(!NetworkAccess.canConfigure(viewer, owned), "viewer configures " + device);
                        helper.assertTrue(!NetworkAccess.canConfigure(stranger, owned),
                                "stranger configures " + device);
                    }
                    helper.destroyBlock(module);
                }).thenWaitUntil(() -> helper.assertTrue(!NetworkGameTests.controllerEntity(helper, core).secured(),
                        "module gone"))
                .thenExecute(() -> {
                    helper.assertTrue(NetworkAccess.canConfigure(stranger, port), "owner security again");
                    helper.assertTrue(!terminal.canAccess(viewer), "rules only apply with a module");
                    scoreboard.removePlayerTeam(team);
                }).thenSucceed();
    }

    /**
     * The security screen's rows: clicking the "everyone" row cycles its level for the owner and does nothing for a
     * stranger, and a team row cycles from no rule through every level back to no rule. The rules go into the core's
     * item when it is broken and come back when it is placed.
     */
    private static void screenAndItem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = NetworkGameTests.player(level, "sec_screen_owner");
        ServerPlayer stranger = NetworkGameTests.player(level, "sec_stranger");
        String teamName = teamName(level);
        Scoreboard scoreboard = level.getScoreboard();
        PlayerTeam team = scoreboard.addPlayerTeam(teamName);
        BlockPos core = NetworkGameTests.controller(helper, 1);
        ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
        controller.setOwner(OwnerRecord.of(owner));
        BlockPos module = core.above();
        ControllerClusterGameTests.expansion(helper, module, ExpansionContent.SECURITY_MODULE);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(controller.secured(), "module"))
                .thenExecute(() -> {
                    List<SecurityMenu.Row> rows = SecurityMenu.rows(controller, owner);
                    SecurityMenu menu = SecurityMenu.forCore(1, controller, rows, helper.absolutePos(module),
                            Component.empty());
                    helper.assertValueEqual(rows.get(0).kind(), SecurityMenu.Kind.EVERYONE, "first row");
                    helper.assertTrue(!menu.clickMenuButton(stranger, 0), "stranger's click");
                    helper.assertValueEqual(controller.security().everyone(), AccessLevel.NONE, "unchanged");
                    helper.assertTrue(menu.clickMenuButton(owner, 0), "owner's click");
                    helper.assertValueEqual(controller.security().everyone(), AccessLevel.VIEW, "cycled");
                    helper.assertTrue(!menu.clickMenuButton(owner, rows.size()), "row out of range");
                    int teamRow = -1;
                    for (int i = 0; i < rows.size(); i++) {
                        if (rows.get(i).kind() == SecurityMenu.Kind.TEAM && rows.get(i).name().equals(teamName)) {
                            teamRow = i;
                        }
                    }
                    helper.assertTrue(teamRow > 1, "a row for the scoreboard team");
                    AccessLevel[] expected = {AccessLevel.NONE, AccessLevel.VIEW, AccessLevel.DEPOSIT,
                            AccessLevel.CRAFT, AccessLevel.LOGIC, AccessLevel.FULL, null};
                    for (AccessLevel want : expected) {
                        menu.clickMenuButton(owner, teamRow);
                        AccessLevel got = controller.security().teamLevel(teamName);
                        helper.assertTrue(want == got, "team row: " + got + " instead of " + want);
                    }
                    menu.clickMenuButton(owner, teamRow);
                    menu.clickMenuButton(owner, teamRow);
                    helper.assertTrue(controller.security().teamLevel(teamName) == AccessLevel.VIEW, "team rule");
                    owner.setPos(helper.absolutePos(module).getCenter());
                    stranger.setPos(helper.absolutePos(module).getCenter());
                    helper.assertTrue(menu.stillValid(owner), "owner keeps it open in reach");
                    helper.assertTrue(!menu.stillValid(stranger), "a stranger's menu closes");
                    List<ItemStack> drops = Block.getDrops(helper.getBlockState(core), level, helper.absolutePos(core),
                            controller);
                    ItemStack item = drops.getFirst();
                    SecuritySettings kept = item.get(ModDataComponents.NETWORK_SECURITY.get());
                    helper.assertTrue(kept != null && kept.equals(controller.security()), "rules in the item");
                    helper.setBlock(core.east(3), helper.getBlockState(core).getBlock());
                    ControllerBlockEntity placed = NetworkGameTests.controllerEntity(helper, core.east(3));
                    placed.applyComponentsFromItemStack(item);
                    helper.assertValueEqual(placed.security(), controller.security(), "rules back from the item");
                    scoreboard.removePlayerTeam(team);
                }).thenSucceed();
    }

    /**
     * The network chest's player screen and the display and world device settings follow the module's rules too: a
     * viewer neither takes nor stores at the chest, a depositor stores but takes nothing, and a member of the owner's
     * team who could change the display's and the collector's settings before loses that once the module sets the
     * owner's team to view. Layout: controller at x = 1 (module on top), cables at x = 2 to 6, and south of them a test
     * drive, the chest (unowned, so it needs no research), a collector and a display panel (both owned).
     */
    private static void chestAndSettings(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = NetworkGameTests.player(level, "tm_" + Integer.toHexString(level.getRandom().nextInt()));
        ServerPlayer member = NetworkGameTests.player(level, "tm_" + Integer.toHexString(level.getRandom().nextInt()));
        ServerPlayer viewer = NetworkGameTests.player(level, "sec_chest_viewer");
        ServerPlayer depositor = NetworkGameTests.player(level, "sec_depositor");
        String teamName = teamName(level);
        Scoreboard scoreboard = level.getScoreboard();
        PlayerTeam team = scoreboard.addPlayerTeam(teamName);
        scoreboard.addPlayerToTeam(owner.getScoreboardName(), team);
        scoreboard.addPlayerToTeam(member.getScoreboardName(), team);

        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        NetworkGameTests.power(helper, core, 10_000);
        NetworkGameTests.cables(helper, 2, 6, CableContent.STORAGE_CABLE_TIER_1.type().get());
        NetworkGameTests.device(helper, NetworkGameTests.at(3).south());
        BlockPos chestPos = NetworkGameTests.at(4).south();
        helper.setBlock(chestPos, WorldContent.NETWORK_CHEST.get());
        NetworkChestBlockEntity chest = helper.getBlockEntity(chestPos, NetworkChestBlockEntity.class);
        BlockPos collectorPos = NetworkGameTests.at(5).south();
        helper.setBlock(collectorPos,
                WorldContent.COLLECTOR.get().defaultBlockState().setValue(WorldDeviceBlock.FACING, Direction.SOUTH));
        WorldDeviceBlockEntity collector = helper.getBlockEntity(collectorPos, WorldDeviceBlockEntity.class);
        collector.setOwner(OwnerRecord.of(owner));
        DisplayPanelBlockEntity panel = DisplayGameTests.panel(helper, NetworkGameTests.at(6).south(), Direction.SOUTH);
        panel.setOwner(OwnerRecord.of(owner));
        BlockPos module = core.above();
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, core);
            helper.assertTrue(chest.deviceLink().network() == network, "chest joined");
            helper.assertTrue(collector.deviceLink().network() == network, "collector joined");
            helper.assertTrue(panel.deviceLink().network() == network, "panel joined");
        }).thenExecute(() -> {
            WorldMenu world = WorldMenu.forDevice(1, member.getInventory(), collector.worker());
            helper.assertTrue(world.clickMenuButton(member, WorldMenu.CYCLE_MODE), "member's mode before the module");
            DisplayMenu display = DisplayMenu.forDevice(2, member.getInventory(), panel);
            helper.assertTrue(display.clickMenuButton(member, DisplayMenu.CYCLE_ACCENT),
                    "member's accent before the module");
            NetworkGameTests.insert(NetworkGameTests.state(helper, core), IRON, 100);
            ControllerClusterGameTests.expansion(helper, module, ExpansionContent.SECURITY_MODULE);
            NetworkGameTests.controllerEntity(helper, core)
                    .setSecurity(SecuritySettings.DEFAULT.withOwnerTeam(AccessLevel.VIEW)
                            .withPlayer(viewer.getUUID(), viewer.getGameProfile().name(), AccessLevel.VIEW)
                            .withPlayer(depositor.getUUID(), depositor.getGameProfile().name(), AccessLevel.DEPOSIT));
        }).thenWaitUntil(
                () -> helper.assertTrue(NetworkGameTests.controllerEntity(helper, core).secured(), "module active"))
                .thenExecute(() -> {
                    helper.assertValueEqual(NetworkGameTests.state(helper, core).accessLevel(member), AccessLevel.VIEW,
                            "member demoted");
                    // World device and display settings: full access only.
                    int mode = collector.worker().mode();
                    WorldMenu world = WorldMenu.forDevice(1, member.getInventory(), collector.worker());
                    helper.assertFalse(world.clickMenuButton(member, WorldMenu.CYCLE_MODE), "member's mode");
                    world.setCarried(new ItemStack(Items.DIRT));
                    world.clicked(0, 0, ContainerInput.PICKUP, member);
                    world.setCarried(ItemStack.EMPTY);
                    helper.assertTrue(collector.worker().filter().getItem(0).isEmpty(), "member named a slot");
                    helper.assertValueEqual(collector.worker().mode(), mode, "collector mode");
                    helper.assertFalse(world.stillValid(member), "member's world settings stay open");
                    int accent = panel.settings().accent();
                    DisplayMenu display = DisplayMenu.forDevice(2, member.getInventory(), panel);
                    helper.assertFalse(display.clickMenuButton(member, DisplayMenu.CYCLE_ACCENT), "member's accent");
                    helper.assertFalse(display.clickMenuButton(depositor, DisplayMenu.CYCLE_ACCENT),
                            "depositor's accent");
                    helper.assertValueEqual(panel.settings().accent(), accent, "panel accent");
                    helper.assertTrue(display.clickMenuButton(owner, DisplayMenu.CYCLE_ACCENT), "owner's accent");
                    helper.assertTrue(world.clickMenuButton(owner, WorldMenu.CYCLE_MODE), "owner's mode");
                    // A blank filter shows nothing; the named slot fills on the next tick.
                    chest.view().filter().setItem(0, new ItemStack(Items.IRON_INGOT));
                }).thenIdle(1).thenExecute(() -> {
                    // The chest: viewers look, depositors store, only full access takes.
                    NetworkChestMenu viewing = NetworkChestMenu.forView(3, viewer.getInventory(), chest.view());
                    helper.assertValueEqual(viewing.getSlot(0).getItem().getCount(), 64, "the chest shows iron");
                    viewing.clicked(0, 0, ContainerInput.PICKUP, viewer);
                    viewing.clicked(0, 0, ContainerInput.QUICK_MOVE, viewer);
                    helper.assertTrue(viewing.getCarried().isEmpty(), "viewer took iron");
                    helper.assertValueEqual(viewer.getInventory().countItem(Items.IRON_INGOT), 0,
                            "viewer shift-took iron");
                    viewing.setCarried(new ItemStack(Items.IRON_INGOT, 5));
                    viewing.clicked(0, 0, ContainerInput.PICKUP, viewer);
                    helper.assertValueEqual(viewing.getCarried().getCount(), 5, "viewer stored iron");
                    viewing.setCarried(ItemStack.EMPTY);
                    viewer.getInventory().add(new ItemStack(Items.IRON_INGOT, 7));
                    int inventorySlot = viewing.getRowCount() * 9;
                    for (int i = 0; i < 36; i++) {
                        viewing.quickMoveStack(viewer, inventorySlot + i);
                    }
                    helper.assertValueEqual(viewer.getInventory().countItem(Items.IRON_INGOT), 7,
                            "viewer's shift-click stored iron");
                    helper.assertValueEqual(ironIn(helper, core), 100L, "iron after the viewer");
                    NetworkChestMenu depositing = NetworkChestMenu.forView(4, depositor.getInventory(), chest.view());
                    depositing.clicked(0, 0, ContainerInput.PICKUP, depositor);
                    depositing.clicked(0, 0, ContainerInput.QUICK_MOVE, depositor);
                    helper.assertTrue(depositing.getCarried().isEmpty(), "depositor took iron");
                    helper.assertValueEqual(depositor.getInventory().countItem(Items.IRON_INGOT), 0,
                            "depositor shift-took iron");
                    depositing.setCarried(new ItemStack(Items.IRON_INGOT, 5));
                    depositing.clicked(0, 0, ContainerInput.PICKUP, depositor);
                    helper.assertTrue(depositing.getCarried().isEmpty(), "depositor's stack not stored");
                    helper.assertValueEqual(ironIn(helper, core), 105L, "iron after the depositor");
                    NetworkChestMenu full = NetworkChestMenu.forView(5, owner.getInventory(), chest.view());
                    full.clicked(0, 0, ContainerInput.PICKUP, owner);
                    helper.assertValueEqual(full.getCarried().getCount(), 64, "owner takes a stack");
                    scoreboard.removePlayerTeam(team);
                }).thenSucceed();
    }

    private static long ironIn(GameTestHelper helper, BlockPos core) {
        return NetworkGameTests.state(helper, core).contents().amount(IRON);
    }

    /**
     * A scoreboard team name of its own for one run of a test: the tests of a batch, and repeated runs of one test, run
     * side by side, and each removes its team when it ends.
     */
    private static String teamName(ServerLevel level) {
        return "sec_" + Integer.toHexString(level.getRandom().nextInt());
    }
}
