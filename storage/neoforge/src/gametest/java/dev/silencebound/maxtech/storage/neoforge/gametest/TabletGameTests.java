package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.authlib.GameProfile;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.ModuleKind;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJobs;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkService;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem.Reach;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletContent;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletItem;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletMenu;
import dev.silencebound.maxtech.storage.tool.tablet.TabletAccess;
import dev.silencebound.maxtech.storage.tool.tablet.TabletRequests;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.jspecify.annotations.Nullable;

/**
 * The crafting tablet (M7c): reach and power by the wireless terminal's rules, the grid pulling from the network and
 * crafting without creating or losing items, and the request button's job through {@link TabletRequests}. A controller
 * with a wireless access mount at (5, 1, 2), a cable at z = 3, a test storage device at z = 4 and a pattern host at z =
 * 5.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class TabletGameTests {
    private static final String[] NAMES = {"tablet_reach_and_power", "tablet_pull_and_craft_conserves",
            "tablet_without_power_or_rights", "tablet_request_missing", "tablet_request_plans_job",
            "tablet_planned_jobs_bounded"};
    private static final BlockPos CORE = new BlockPos(5, 1, 2);
    private static final StackKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final int COST = 50;

    static {
        TestStorage.init();
        CraftingTestContent.init();
        register(NAMES[0], TabletGameTests::reachAndPower);
        register(NAMES[1], TabletGameTests::pullAndCraft);
        register(NAMES[2], TabletGameTests::withoutPowerOrRights);
        register(NAMES[3], TabletGameTests::requestMissing);
        register(NAMES[4], TabletGameTests::requestPlansJob);
        register(NAMES[5], TabletGameTests::plannedJobsBounded);
    }

    private TabletGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        // The two request tests swap the static requester, so each gets a batch of its own; a batch runs its tests
        // side by side.
        Holder<TestEnvironmentDefinition<?>> shared = event.registerEnvironment(MaxtechStorage.id("tablet"),
                StorageTestEnvironment.plain());
        Holder<TestEnvironmentDefinition<?>> standIn = event.registerEnvironment(MaxtechStorage.id("tablet_stand_in"),
                StorageTestEnvironment.plain());
        Holder<TestEnvironmentDefinition<?>> planned = event.registerEnvironment(MaxtechStorage.id("tablet_planned"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            Holder<TestEnvironmentDefinition<?>> environment = name.equals(NAMES[3])
                    ? standIn
                    : name.equals(NAMES[4]) ? planned : shared;
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 300);
        }
    }

    private static ControllerBlockEntity core(GameTestHelper helper) {
        return helper.getBlockEntity(CORE, ControllerBlockEntity.class);
    }

    /**
     * A powered network with a wireless mount, a cable, a storage device and a pattern host (patterns may be empty).
     */
    private static CraftingTestContent.Host network(GameTestHelper helper, List<CraftingPattern> patterns) {
        helper.setBlock(CORE, ControllerContent.CONTROLLER.get());
        helper.setBlock(CORE.east(), ExpansionContent.WIRELESS_ACCESS_MOUNT.get());
        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(new BlockPos(5, 1, 3)),
                CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        NetworkGameTests.device(helper, new BlockPos(5, 1, 4));
        BlockPos hostPos = new BlockPos(5, 1, 5);
        helper.setBlock(hostPos, CraftingTestContent.HOST.get());
        CraftingTestContent.Host host = helper.getBlockEntity(hostPos, CraftingTestContent.Host.class);
        host.setPatterns(patterns);
        NetworkGameTests.power(helper, CORE, 1_000_000);
        return host;
    }

    private static NetworkState joined(GameTestHelper helper, CraftingTestContent.Host host) {
        helper.assertValueEqual(core(helper).services(NetworkService.WIRELESS_ACCESS), 1, "mount active");
        NetworkState network = host.network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the pattern host is in no network"));
        }
        return network;
    }

    /**
     * A player with a connection that drops every packet: taking a crafting result awards its recipe, which a
     * connectionless {@code ServerPlayer} cannot.
     */
    private static ServerPlayer fake(ServerLevel level, String name) {
        return new FakePlayer(level, new GameProfile(UUID.randomUUID(), name));
    }

    /** A player whose team completed the Wireless research. */
    private static ServerPlayer researched(GameTestHelper helper, String name) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = fake(level, name);
        ResearchTeam team = Research.team(player, true);
        Research.addPoints(level.getServer(), team, 2000);
        for (String node : List.of("port_tier_2", "antennas", "wireless")) {
            helper.assertValueEqual(Research.spendPoints(level.getServer(), team, MaxtechStorage.id(node)),
                    Research.Result.DONE, node);
        }
        return player;
    }

    private static ItemStack tablet(GameTestHelper helper, int energy) {
        ItemStack tablet = new ItemStack(CraftingTabletContent.TABLET.get());
        tablet.set(ModDataComponents.WIRELESS_LINK.get(),
                GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(CORE)));
        tablet.set(ModDataComponents.WIRELESS_ENERGY.get(), energy);
        return tablet;
    }

    private static TabletAccess access(GameTestHelper helper, ServerPlayer player) {
        TabletAccess access = CraftingTabletItem.access(player, InteractionHand.MAIN_HAND);
        if (access == null) {
            throw helper.assertionException(Component.literal("the tablet reaches no network"));
        }
        return access;
    }

    private static List<ItemStack> planks(Item first) {
        List<ItemStack> planks = new ArrayList<>();
        planks.add(new ItemStack(first));
        BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.PLANKS).forEach(holder -> {
            if (!holder.value().equals(first)) {
                planks.add(new ItemStack(holder.value()));
            }
        });
        return planks;
    }

    /** The cells of a crafting table: planks (any kind, {@code first} listed first) in a 2x2 block. */
    private static List<List<ItemStack>> tableCells(Item first) {
        List<List<ItemStack>> cells = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            cells.add(i == 0 || i == 1 || i == 3 || i == 4 ? planks(first) : List.of());
        }
        return cells;
    }

    private static int total(ServerPlayer player, Item item) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int inGrid(CraftingTabletMenu menu, Item item) {
        int total = 0;
        for (int cell = 0; cell < 9; cell++) {
            ItemStack stack = menu.grid().getItem(cell);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * The wireless terminal's rules with the tablet's own FE: reach inside the mount's 64 blocks and not beyond, no
     * power with a charge below one operation, unbound and without the Wireless research. The menu closes out of range.
     */
    private static void reachAndPower(GameTestHelper helper) {
        CraftingTestContent.Host host = network(helper, List.of());
        ServerPlayer player = researched(helper, "tablet_reach");
        player.setItemInHand(InteractionHand.MAIN_HAND, tablet(helper, 1_000));
        Vec3 centre = helper.absolutePos(CORE).getCenter();
        helper.startSequence().thenWaitUntil(() -> joined(helper, host)).thenExecute(() -> {
            player.setPos(centre.add(10, 0, 0));
            ItemStack held = player.getMainHandItem();
            helper.assertValueEqual(CraftingTabletItem.reach(player, held), Reach.OK, "in range");
            TabletAccess access = access(helper, player);
            CraftingTabletMenu menu = CraftingTabletMenu.server(1, player.getInventory(), access);
            helper.assertTrue(access.canView() && menu.stillValid(player), "menu valid in range");
            player.setPos(centre.add(70, 0, 0));
            helper.assertValueEqual(CraftingTabletItem.reach(player, held), Reach.OUT_OF_RANGE, "out of range");
            helper.assertTrue(!menu.stillValid(player), "the menu closes out of range");
            helper.assertTrue(CraftingTabletItem.access(player, InteractionHand.MAIN_HAND) == null, "no access");
            player.setPos(centre);
            held.set(ModDataComponents.WIRELESS_ENERGY.get(), COST - 1);
            helper.assertValueEqual(CraftingTabletItem.reach(player, held), Reach.NO_POWER, "below one operation");
            held.set(ModDataComponents.WIRELESS_ENERGY.get(), COST);
            helper.assertValueEqual(CraftingTabletItem.reach(player, held), Reach.OK, "exactly one operation");
            helper.assertValueEqual(CraftingTabletItem.reach(player, new ItemStack(CraftingTabletContent.TABLET.get())),
                    Reach.UNBOUND, "unbound");
            ServerPlayer novice = fake(helper.getLevel(), "tablet_novice");
            novice.setPos(centre);
            helper.assertValueEqual(CraftingTabletItem.reach(novice, tablet(helper, 1_000)), Reach.LOCKED,
                    "without the research");
            helper.assertValueEqual(CraftingTabletItem.capacity(), 100_000, "capacity from the preset");
        }).thenSucceed();
    }

    /**
     * A recipe viewer's "+" for a crafting table (any plank, birch listed first) pulls oak planks, the only kind the
     * network holds, into the grid and pays one operation per cell; taking the table consumes the grid and refills it
     * from the network. Planks and tables are conserved all the way: 8 planks end up as 4 in the grid and a table made
     * of the other 4, and closing the menu hands the grid back.
     */
    private static void pullAndCraft(GameTestHelper helper) {
        CraftingTestContent.Host host = network(helper, List.of());
        ServerPlayer player = researched(helper, "tablet_craft");
        player.getAbilities().instabuild = false;
        player.setItemInHand(InteractionHand.MAIN_HAND, tablet(helper, 10_000));
        player.setPos(helper.absolutePos(CORE).getCenter());
        helper.startSequence().thenWaitUntil(() -> joined(helper, host)).thenExecute(() -> {
            NetworkState network = joined(helper, host);
            NetworkGameTests.insert(network, PLANKS, 8);
            CraftingTabletMenu menu = CraftingTabletMenu.server(1, player.getInventory(), access(helper, player));
            menu.receiveRecipe(player, tableCells(Items.BIRCH_PLANKS), false);
            helper.assertValueEqual(inGrid(menu, Items.OAK_PLANKS), 4, "oak planks in the grid");
            helper.assertValueEqual(network.contents().amount(PLANKS), 4L, "taken from the network");
            helper.assertValueEqual(CraftingTabletItem.energy(player.getMainHandItem()), 10_000 - 4 * COST,
                    "four pulls paid");
            helper.assertTrue(menu.result().is(Items.CRAFTING_TABLE), "the grid crafts a table");

            menu.clicked(0, 0, ContainerInput.PICKUP, player);
            helper.assertTrue(menu.getCarried().is(Items.CRAFTING_TABLE) && menu.getCarried().getCount() == 1,
                    "a table in hand");
            helper.assertValueEqual(inGrid(menu, Items.OAK_PLANKS), 4, "the grid was refilled");
            helper.assertValueEqual(network.contents().amount(PLANKS), 0L, "from the network");
            int planks = inGrid(menu, Items.OAK_PLANKS) + (int) network.contents().amount(PLANKS)
                    + 4 * menu.getCarried().getCount() + total(player, Items.OAK_PLANKS);
            helper.assertValueEqual(planks, 8, "planks conserved");
            helper.assertValueEqual(CraftingTabletItem.energy(player.getMainHandItem()), 10_000 - 8 * COST,
                    "eight pulls paid");

            menu.setCarried(ItemStack.EMPTY);
            menu.removed(player);
            helper.assertValueEqual(total(player, Items.OAK_PLANKS), 4, "the grid went back to the player");
            helper.assertValueEqual(inGrid(menu, Items.OAK_PLANKS), 0, "the grid is empty");
        }).thenSucceed();
    }

    /**
     * Without charge the grid still fills from the player's inventory and leaves the network alone; a player who may
     * deposit but not take pulls nothing, and what is returned to the network pays its operation.
     */
    private static void withoutPowerOrRights(GameTestHelper helper) {
        CraftingTestContent.Host host = network(helper, List.of());
        ServerPlayer player = researched(helper, "tablet_poor");
        player.setItemInHand(InteractionHand.MAIN_HAND, tablet(helper, COST));
        player.setPos(helper.absolutePos(CORE).getCenter());
        ServerPlayer depositor = researched(helper, "tablet_depositor");
        depositor.setItemInHand(InteractionHand.MAIN_HAND, tablet(helper, 10_000));
        depositor.setPos(helper.absolutePos(CORE).getCenter());
        BlockPos module = CORE.above();
        helper.startSequence().thenWaitUntil(() -> joined(helper, host)).thenExecute(() -> {
            NetworkState network = joined(helper, host);
            NetworkGameTests.insert(network, PLANKS, 8);
            player.getInventory().add(new ItemStack(Items.OAK_PLANKS, 4));
            TabletAccess access = access(helper, player);
            CraftingTabletMenu menu = CraftingTabletMenu.server(1, player.getInventory(), access);
            // One operation of charge: one cell comes from the network, the rest from the inventory.
            menu.receiveRecipe(player, tableCells(Items.OAK_PLANKS), false);
            helper.assertValueEqual(inGrid(menu, Items.OAK_PLANKS), 4, "grid filled");
            helper.assertValueEqual(network.contents().amount(PLANKS), 7L, "one pull from the network");
            helper.assertValueEqual(CraftingTabletItem.energy(player.getMainHandItem()), 0, "charge spent");
            helper.assertValueEqual(total(player, Items.OAK_PLANKS), 1, "the rest from the inventory");
            helper.assertTrue(access.take(new ItemStack(Items.OAK_PLANKS), 1).isEmpty(), "no charge, no pull");
            helper.assertValueEqual(network.contents().amount(PLANKS), 7L, "the network is untouched");
            menu.removed(player);

            // A module's rules need an owned network: an unowned one lets everybody in.
            core(helper).setOwner(OwnerRecord.of(fake(helper.getLevel(), "tablet_net_owner")));
            ControllerClusterGameTests.expansion(helper, module, ExpansionContent.SECURITY_MODULE);
            core(helper).setSecurity(SecuritySettings.DEFAULT.withPlayer(depositor.getUUID(),
                    depositor.getGameProfile().name(), AccessLevel.DEPOSIT));
        }).thenWaitUntil(() -> helper.assertTrue(core(helper).secured(), "module active")).thenExecute(() -> {
            NetworkState network = joined(helper, host);
            TabletAccess access = access(helper, depositor);
            helper.assertTrue(access.canView() && access.canDeposit(), "view and deposit");
            helper.assertTrue(!access.canTake() && !access.canCraft(), "no take, no craft");
            helper.assertTrue(access.take(new ItemStack(Items.OAK_PLANKS), 1).isEmpty(), "a depositor takes nothing");
            helper.assertValueEqual(network.contents().amount(PLANKS), 7L, "still seven");
            ItemStack rest = access.store(new ItemStack(Items.OAK_PLANKS, 3));
            helper.assertTrue(rest.isEmpty(), "everything stored");
            helper.assertValueEqual(network.contents().amount(PLANKS), 10L, "three more planks");
            helper.assertValueEqual(CraftingTabletItem.energy(depositor.getMainHandItem()), 10_000 - COST,
                    "one operation paid");
        }).thenSucceed();
    }

    /** The recorded outcome of a stand-in requester. */
    private record Request(StackKey key, long amount) {
    }

    /**
     * The request button orders what the grid still lacks of the last recipe, through the requester: with no planks
     * anywhere and a pattern for planks, four planks; never twice within the cooldown; nothing when the grid is
     * complete; refused without craft access.
     */
    private static void requestMissing(GameTestHelper helper) {
        CraftingPattern logsToPlanks = pattern(Items.OAK_PLANKS, 4,
                new PatternIngredient.Exact(ItemKey.of(Items.OAK_LOG)));
        CraftingTestContent.Host host = network(helper, List.of(logsToPlanks));
        ServerPlayer player = researched(helper, "tablet_request");
        ServerPlayer depositor = researched(helper, "tablet_request_deposit");
        player.setItemInHand(InteractionHand.MAIN_HAND, tablet(helper, 10_000));
        depositor.setItemInHand(InteractionHand.MAIN_HAND, tablet(helper, 10_000));
        player.setPos(helper.absolutePos(CORE).getCenter());
        depositor.setPos(helper.absolutePos(CORE).getCenter());
        List<Request> requests = new ArrayList<>();
        TabletRequests.Requester previous = TabletRequests.requester();
        BlockPos module = CORE.above();
        helper.startSequence().thenWaitUntil(() -> joined(helper, host)).thenExecute(() -> {
            TabletRequests.use((who, network, level, key, amount) -> requests.add(new Request(key, amount)));
            CraftingTabletMenu menu = CraftingTabletMenu.server(1, player.getInventory(), access(helper, player));
            helper.assertTrue(!menu.clickMenuButton(player, CraftingTabletMenu.REQUEST), "nothing asked for yet");
            helper.assertTrue(requests.isEmpty(), "no request without a recipe");
            menu.receiveRecipe(player, tableCells(Items.OAK_PLANKS), false);
            helper.assertValueEqual(menu.grid().isEmpty(), true, "the network has no planks");
            helper.assertTrue(!menu.clickMenuButton(player, CraftingTabletMenu.REQUEST), "inside the cooldown");
            helper.assertTrue(requests.isEmpty(), "the cooldown held");
        }).thenIdle(CraftingTabletMenu.REQUEST_COOLDOWN + 1).thenExecute(() -> {
            CraftingTabletMenu menu = CraftingTabletMenu.server(2, player.getInventory(), access(helper, player));
            menu.receiveRecipe(player, tableCells(Items.OAK_PLANKS), false);
            helper.assertTrue(menu.clickMenuButton(player, CraftingTabletMenu.REQUEST), "the button");
            helper.assertValueEqual(requests, List.of(new Request(PLANKS, 4)), "four planks");
            helper.assertValueEqual(CraftingTabletItem.energy(player.getMainHandItem()), 10_000 - COST,
                    "one request paid");
            menu.removed(player);
        }).thenExecute(() -> {
            // With planks stocked the grid fills and nothing is missing.
            NetworkGameTests.insert(joined(helper, host), PLANKS, 4);
            requests.clear();
            CraftingTabletMenu menu = CraftingTabletMenu.server(3, player.getInventory(), access(helper, player));
            menu.receiveRecipe(player, tableCells(Items.OAK_PLANKS), false);
            helper.assertValueEqual(inGrid(menu, Items.OAK_PLANKS), 4, "grid complete");
            helper.assertTrue(!menu.clickMenuButton(player, CraftingTabletMenu.REQUEST), "nothing missing");
            helper.assertTrue(requests.isEmpty(), "no request for a full grid");
            menu.removed(player);
            // A module's rules need an owned network: an unowned one lets everybody in.
            core(helper).setOwner(OwnerRecord.of(fake(helper.getLevel(), "tablet_net_owner")));
            ControllerClusterGameTests.expansion(helper, module, ExpansionContent.SECURITY_MODULE);
            core(helper).setSecurity(SecuritySettings.DEFAULT.withPlayer(depositor.getUUID(),
                    depositor.getGameProfile().name(), AccessLevel.DEPOSIT));
        }).thenWaitUntil(() -> helper.assertTrue(core(helper).secured(), "module active")).thenExecute(() -> {
            CraftingTabletMenu menu = CraftingTabletMenu.server(4, depositor.getInventory(), access(helper, depositor));
            menu.receiveRecipe(depositor, tableCells(Items.OAK_PLANKS), false);
            helper.assertTrue(!menu.clickMenuButton(depositor, CraftingTabletMenu.REQUEST), "needs craft access");
            helper.assertTrue(requests.isEmpty(), "refused");
            TabletRequests.use(previous);
        }).thenSucceed();
    }

    private static CraftingPattern pattern(Item output, int count, PatternIngredient input) {
        return new CraftingPattern(CraftingPattern.Kind.CRAFTING,
                List.of(new PatternInput(input, 1, Optional.empty(), false)),
                List.of(new PatternOutput(ItemKey.of(output), count, 1f)), Optional.empty(), Optional.empty());
    }

    /**
     * The default requester plans the missing planks against the network (one log is enough for four) and saves a
     * planned job for the player; a plan that cannot be completed saves none.
     */
    private static void requestPlansJob(GameTestHelper helper) {
        CraftingPattern logsToPlanks = pattern(Items.OAK_PLANKS, 4,
                new PatternIngredient.Exact(ItemKey.of(Items.OAK_LOG)));
        CraftingTestContent.Host host = network(helper, List.of(logsToPlanks));
        ServerPlayer player = researched(helper, "tablet_plans");
        player.setItemInHand(InteractionHand.MAIN_HAND, tablet(helper, 10_000));
        player.setPos(helper.absolutePos(CORE).getCenter());
        TabletRequests.use(TabletRequests::planned);
        CraftingTabletMenu[] menu = new CraftingTabletMenu[1];
        helper.startSequence().thenWaitUntil(() -> joined(helper, host)).thenExecute(() -> {
            NetworkGameTests.insert(joined(helper, host), ItemKey.of(Items.OAK_LOG), 1);
            menu[0] = CraftingTabletMenu.server(1, player.getInventory(), access(helper, player));
            menu[0].receiveRecipe(player, tableCells(Items.OAK_PLANKS), false);
            helper.assertTrue(menu[0].clickMenuButton(player, CraftingTabletMenu.REQUEST), "requested");
        }).thenWaitUntil(() -> {
            CraftingJob job = job(helper, player);
            if (job == null) {
                throw helper.assertionException(Component.literal("no planned job yet"));
            }
            helper.assertValueEqual(job.key(), PLANKS, "the job crafts planks");
            helper.assertValueEqual(job.amount(), 4L, "four planks");
            helper.assertValueEqual(job.requester(), Optional.of(player.getUUID()), "for the player");
        }).thenExecute(() -> menu[0].removed(player)).thenSucceed();
    }

    private static int plannedJobs(GameTestHelper helper, ServerPlayer player, @Nullable StackKey key) {
        int count = 0;
        for (CraftingJob job : CraftingJobs.get(helper.getLevel()).all()) {
            if (job.requester().equals(Optional.of(player.getUUID())) && (key == null || job.key().equals(key))) {
                count++;
            }
        }
        return count;
    }

    /**
     * Requests that keep arriving do not pile up planned jobs: asking again for the same item replaces its waiting job,
     * and a player has at most {@code crafting.tablet.maxPlannedJobs} (4) different ones waiting; the rest are refused.
     */
    private static void plannedJobsBounded(GameTestHelper helper) {
        Item[] outputs = {Items.OAK_PLANKS, Items.STICK, Items.OAK_BUTTON, Items.OAK_PRESSURE_PLATE, Items.OAK_SIGN,
                Items.OAK_DOOR};
        List<CraftingPattern> patterns = new ArrayList<>();
        for (Item output : outputs) {
            patterns.add(pattern(output, 1, new PatternIngredient.Exact(ItemKey.of(Items.OAK_LOG))));
        }
        CraftingTestContent.Host host = network(helper, patterns);
        ServerPlayer player = researched(helper, "tablet_bounded");
        player.setPos(helper.absolutePos(CORE).getCenter());
        helper.startSequence().thenWaitUntil(() -> joined(helper, host)).thenExecute(() -> {
            NetworkState network = joined(helper, host);
            NetworkGameTests.insert(network, ItemKey.of(Items.OAK_LOG), 100);
            for (int i = 0; i < 3; i++) {
                TabletRequests.planned(player, network, helper.getLevel(), PLANKS, 4);
            }
        }).thenWaitUntil(() -> helper.assertTrue(plannedJobs(helper, player, PLANKS) > 0, "a plank job arrived"))
                .thenIdle(10).thenExecute(() -> {
                    helper.assertValueEqual(plannedJobs(helper, player, PLANKS), 1, "three requests, one job");
                    NetworkState network = joined(helper, host);
                    for (int i = 1; i < outputs.length; i++) {
                        TabletRequests.planned(player, network, helper.getLevel(), ItemKey.of(outputs[i]), 1);
                    }
                }).thenWaitUntil(() -> helper.assertValueEqual(plannedJobs(helper, player, null), 4, "four waiting"))
                .thenIdle(10).thenExecute(() -> {
                    helper.assertValueEqual(plannedJobs(helper, player, null), 4, "the cap held");
                    helper.assertValueEqual(plannedJobs(helper, player, PLANKS), 1, "still one plank job");
                }).thenSucceed();
    }

    private static @Nullable CraftingJob job(GameTestHelper helper, ServerPlayer player) {
        for (CraftingJob job : CraftingJobs.get(helper.getLevel()).all()) {
            if (job.requester().equals(Optional.of(player.getUUID()))) {
                return job;
            }
        }
        return null;
    }
}
