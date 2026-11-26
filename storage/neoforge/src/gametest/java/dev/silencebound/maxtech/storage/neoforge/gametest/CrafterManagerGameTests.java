package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.authlib.GameProfile;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.crafting.overview.CrafterManager;
import dev.silencebound.maxtech.storage.crafting.overview.CrafterManagerMenu;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlock;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderLogic;
import dev.silencebound.maxtech.storage.crafting.request.CraftingSession;
import dev.silencebound.maxtech.storage.crafting.request.RequestPayloads;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkService;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalSource;
import dev.silencebound.maxtech.storage.crafting.overview.PatternOverview;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import dev.silencebound.maxtech.storage.terminal.TerminalAction;
import dev.silencebound.maxtech.storage.terminal.TerminalBlockEntity;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.terminal.TerminalSource;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletContent;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletItem;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletMenu;
import dev.silencebound.maxtech.storage.tool.tablet.TabletAccess;
import dev.silencebound.maxtech.storage.tool.tablet.TabletJobs;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The crafter manager and the crafting status from wireless devices (B09 item 23): a provider's patterns edited from
 * afar, refusals by the security rules and across networks, a pattern carried from one provider to another with exactly
 * one pattern left, a provider in an unloaded chunk dropped from the list and from the menu without losing its pattern,
 * and the jobs list over the wireless terminal and the crafting tablet. A powered controller at x = 1, a test storage
 * device at x = 2 and 4, providers at x = 3 (one stick pattern) and x = 5 (empty) facing north.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class CrafterManagerGameTests {
    private static final String[] NAMES = {"crafter_manager_edit_remote", "crafter_manager_security",
            "crafter_manager_move_between", "crafter_manager_unloaded", "crafter_manager_wireless_jobs",
            "crafter_manager_tablet_jobs", "crafter_manager_closes_with_terminal", "crafter_manager_wireless_bound",
            "crafter_manager_wireless_cost"};
    private static final StackKey STICK = ItemKey.of(Items.STICK);
    private static final BlockPos CORE = new BlockPos(5, 1, 2);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(NAMES[0], StorageTestEnvironment.clearing(CrafterManagerGameTests::editRemote));
        StorageTests.function(NAMES[1], StorageTestEnvironment.clearing(CrafterManagerGameTests::security));
        StorageTests.function(NAMES[2], StorageTestEnvironment.clearing(CrafterManagerGameTests::moveBetween));
        StorageTests.function(NAMES[3], StorageTestEnvironment.clearing(CrafterManagerGameTests::unloaded));
        StorageTests.function(NAMES[4], StorageTestEnvironment.clearing(CrafterManagerGameTests::wirelessJobs));
        StorageTests.function(NAMES[5], StorageTestEnvironment.clearing(CrafterManagerGameTests::tabletJobs));
        StorageTests.function(NAMES[6], StorageTestEnvironment.clearing(CrafterManagerGameTests::closesWithTerminal));
        StorageTests.function(NAMES[7], StorageTestEnvironment.clearing(CrafterManagerGameTests::wirelessBound));
        StorageTests.function(NAMES[8], StorageTestEnvironment.clearing(CrafterManagerGameTests::wirelessCost));
    }

    private CrafterManagerGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("crafter_manager"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 200);
        }
    }

    private static CraftingPattern sticks() {
        return new CraftingPattern(CraftingPattern.Kind.CRAFTING,
                List.of(new PatternInput(new PatternIngredient.Exact(ItemKey.of(Items.OAK_PLANKS)), 2, Optional.empty(),
                        false)),
                List.of(new PatternOutput(STICK, 4, 1f)), Optional.empty(), Optional.empty());
    }

    private static PatternProviderBlockEntity provider(GameTestHelper helper, int x, boolean withPattern) {
        BlockPos pos = NetworkGameTests.at(x);
        helper.setBlock(pos.north(), Blocks.STONE);
        helper.setBlock(pos, ProviderContent.PROVIDER.get().defaultBlockState().setValue(PatternProviderBlock.FACING,
                Direction.NORTH));
        PatternProviderBlockEntity provider = helper.getBlockEntity(pos, PatternProviderBlockEntity.class);
        if (withPattern) {
            provider.logic().slots().setItem(0, PatternItem.encode(sticks()));
        }
        return provider;
    }

    private static NetworkState network(GameTestHelper helper, PatternProviderBlockEntity provider) {
        NetworkState network = provider.network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the provider is in no network"));
        }
        return network;
    }

    /** Owner and crafter on a secured network with a terminal between the two providers; both stand at it. */
    private static ServerPlayer[] secured(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "manager_owner");
        ServerPlayer crafter = NetworkGameTests.player(helper.getLevel(), "manager_crafter");
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(4), TerminalContent.TERMINAL.get());
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        ControllerClusterGameTests.expansion(helper, core.above(), ExpansionContent.SECURITY_MODULE);
        crafterLevel(helper, crafter, AccessLevel.CRAFT);
        Vec3 terminal = helper.absolutePos(NetworkGameTests.at(4)).above().getCenter();
        owner.setPos(terminal);
        crafter.setPos(terminal);
        return new ServerPlayer[]{owner, crafter};
    }

    private static void crafterLevel(GameTestHelper helper, ServerPlayer crafter, AccessLevel level) {
        NetworkGameTests.controllerEntity(helper, NetworkGameTests.at(1)).setSecurity(
                SecuritySettings.DEFAULT.withPlayer(crafter.getUUID(), crafter.getGameProfile().name(), level));
    }

    /** The terminal at x = 4, which opens the manager in these tests. */
    private static TerminalBlockEntity terminal(GameTestHelper helper) {
        return helper.getBlockEntity(NetworkGameTests.at(4), TerminalBlockEntity.class);
    }

    private static CrafterManagerMenu menu(GameTestHelper helper, ServerPlayer player, NetworkState network,
            List<CustomPacketPayload> sent, int containerId) {
        return menu(player, network, terminal(helper), sent, containerId);
    }

    private static CrafterManagerMenu menu(ServerPlayer player, NetworkState network, TerminalSource source,
            List<CustomPacketPayload> sent, int containerId) {
        CrafterManagerMenu menu = CrafterManagerMenu.server(containerId, player.getInventory(), network, source,
                sent::add);
        player.containerMenu = menu;
        return menu;
    }

    private static CrafterManager.Providers last(GameTestHelper helper, List<CustomPacketPayload> sent) {
        for (int i = sent.size() - 1; i >= 0; i--) {
            if (sent.get(i) instanceof CrafterManager.Providers providers) {
                return providers;
            }
        }
        throw helper.assertionException(Component.literal("no provider list sent"));
    }

    private static BlockPos pos(GameTestHelper helper, int x) {
        return helper.absolutePos(NetworkGameTests.at(x));
    }

    private static boolean isPattern(ItemStack stack) {
        return PatternItem.pattern(stack) != null;
    }

    /** Pattern items in {@code logic}'s slots. */
    private static int patterns(ProviderLogic logic) {
        int count = 0;
        for (int i = 0; i < ProviderLogic.MAX_SLOTS; i++) {
            count += isPattern(logic.slots().getItem(i)) ? logic.slots().getItem(i).getCount() : 0;
        }
        return count;
    }

    /** Pattern items in {@code player}'s inventory and on the cursor of {@code menu}. */
    private static int held(ServerPlayer player, CrafterManagerMenu menu) {
        int count = isPattern(menu.getCarried()) ? menu.getCarried().getCount() : 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            count += isPattern(stack) ? stack.getCount() : 0;
        }
        return count;
    }

    /** The menu slot index of the first pattern in the player's inventory part of {@code menu}, or -1. */
    private static int inventoryPattern(CrafterManagerMenu menu) {
        for (int i = ProviderLogic.MAX_SLOTS; i < menu.slots.size(); i++) {
            if (isPattern(menu.getSlot(i).getItem())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The owner at the terminal sees both providers (the empty one too) with the first's pattern; picks the first,
     * takes its pattern out, puts it into another slot, shift-clicks it into the inventory and back. The list follows
     * the changes and survives the payload codec.
     */
    private static void editRemote(GameTestHelper helper) {
        ServerPlayer[] players = secured(helper);
        PatternProviderBlockEntity first = provider(helper, 3, true);
        PatternProviderBlockEntity second = provider(helper, 5, false);
        List<CustomPacketPayload> sent = new ArrayList<>();
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(network(helper, first) == network(helper, second), "one network");
        }).thenExecute(() -> {
            ServerPlayer owner = players[0];
            NetworkState network = network(helper, first);
            helper.assertTrue(CrafterManager.mayOpen(owner, network), "owner may open");
            CrafterManagerMenu menu = menu(helper, owner, network, sent, 81);
            helper.assertTrue(menu.stillValid(owner), "valid at the terminal");
            menu.broadcastChanges();
            CrafterManager.Providers list = last(helper, sent);
            helper.assertValueEqual(list.providers().size(), 2, "both providers, the empty one too");
            helper.assertValueEqual(list.selected(), -1, "none picked");
            helper.assertTrue(list.providers().get(0).is(pos(helper, 3), Direction.NORTH), "first by position");
            helper.assertValueEqual(list.providers().get(0).outputs(), List.of(STICK), "its pattern's output");
            helper.assertTrue(list.providers().get(1).outputs().isEmpty(), "the empty one");
            helper.assertTrue(list.providers().get(0).editable(), "owner may edit");
            helper.assertTrue(menu.getSlot(0).getItem().isEmpty(), "no slots before a pick");
            menu.clicked(0, 0, ContainerInput.PICKUP, owner);
            helper.assertTrue(menu.getCarried().isEmpty(), "nothing taken before a pick");

            helper.assertTrue(menu.select(owner, pos(helper, 3), Direction.NORTH), "pick the first");
            helper.assertValueEqual(last(helper, sent).selected(), 0, "the pick is sent");
            int usable = first.logic().usableSlots();
            helper.assertValueEqual(menu.usableSlots(), usable, "its working slots");
            helper.assertTrue(isPattern(menu.getSlot(0).getItem()), "its pattern shows");
            menu.clicked(0, 0, ContainerInput.PICKUP, owner);
            helper.assertTrue(isPattern(menu.getCarried()), "taken onto the cursor");
            helper.assertTrue(first.logic().patterns().isEmpty(), "removed from the provider");
            if (usable < ProviderLogic.MAX_SLOTS) {
                menu.clicked(usable, 0, ContainerInput.PICKUP, owner);
                helper.assertTrue(isPattern(menu.getCarried()), "a locked slot takes nothing");
            }
            menu.clicked(4, 0, ContainerInput.PICKUP, owner);
            helper.assertTrue(menu.getCarried().isEmpty(), "put down");
            helper.assertValueEqual(first.logic().patterns().size(), 1, "inserted again");
            helper.assertValueEqual(first.logic().slotOf(0), 4, "in slot 4");

            menu.quickMoveStack(owner, 4);
            helper.assertTrue(first.logic().patterns().isEmpty(), "shift-clicked out");
            int index = inventoryPattern(menu);
            helper.assertTrue(index >= 0, "into the inventory");
            menu.quickMoveStack(owner, index);
            helper.assertTrue(isPattern(first.logic().slots().getItem(0)), "shift-clicked back into slot 0");
            helper.assertValueEqual(patterns(first.logic()) + held(owner, menu), 1, "one pattern all along");

            CrafterManager.Providers cut = CrafterManager.providers(81, network, owner, menu.selected(), 1);
            helper.assertValueEqual(cut.providers().size(), 1, "a cut list still has the pick");
            CrafterManager.Provider pick = cut.selectedRow();
            helper.assertTrue(pick != null && pick.is(pos(helper, 3), Direction.NORTH), "the pick is marked");
            helper.assertValueEqual(cut.hidden(), 1, "the other counted hidden");

            menu.sendListNow();
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                    helper.getLevel().registryAccess());
            CrafterManager.Providers.CODEC.encode(buffer, last(helper, sent));
            helper.assertValueEqual(CrafterManager.Providers.CODEC.decode(buffer), last(helper, sent),
                    "list after the codec");
        }).thenSucceed();
    }

    /**
     * A crafter sees the list read-only and can pick nothing, take nothing and put nothing in; a crafter granted full
     * access may pick, and loses the pick when the rule goes back to craft. A stranger may not open the manager, and
     * the owner cannot pick a provider of another network.
     */
    private static void security(GameTestHelper helper) {
        ServerPlayer[] players = secured(helper);
        PatternProviderBlockEntity first = provider(helper, 3, true);
        provider(helper, 5, false);
        NetworkGameTests.power(helper, NetworkGameTests.controller(helper, 8), 1_000_000);
        PatternProviderBlockEntity foreign = provider(helper, 9, true);
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "manager_stranger");
        List<CustomPacketPayload> toCrafter = new ArrayList<>();
        List<CustomPacketPayload> toOwner = new ArrayList<>();
        CrafterManagerMenu[] menus = new CrafterManagerMenu[1];
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(network(helper, first) != network(helper, foreign), "two networks");
        }).thenExecute(() -> {
            ServerPlayer crafter = players[1];
            NetworkState network = network(helper, first);
            helper.assertFalse(CrafterManager.mayOpen(stranger, network), "a stranger may not open");
            CrafterManagerMenu strangers = menu(helper, stranger, network, new ArrayList<>(), 90);
            helper.assertFalse(strangers.stillValid(stranger), "a stranger's menu closes");

            helper.assertTrue(CrafterManager.mayOpen(crafter, network), "a crafter may look");
            CrafterManagerMenu menu = menu(helper, crafter, network, toCrafter, 91);
            menus[0] = menu;
            menu.broadcastChanges();
            helper.assertFalse(last(helper, toCrafter).providers().get(0).editable(), "read-only for the crafter");
            helper.assertFalse(menu.select(crafter, pos(helper, 3), Direction.NORTH), "the crafter's pick");
            helper.assertTrue(menu.selected() == null, "nothing picked");
            menu.clicked(0, 0, ContainerInput.PICKUP, crafter);
            helper.assertTrue(menu.getCarried().isEmpty(), "nothing taken");
            crafter.getInventory().add(PatternItem.encode(sticks()));
            int index = inventoryPattern(menu);
            menu.quickMoveStack(crafter, index);
            helper.assertValueEqual(patterns(first.logic()), 1, "nothing put in");
            helper.assertTrue(inventoryPattern(menu) >= 0, "still in the crafter's inventory");

            crafterLevel(helper, crafter, AccessLevel.FULL);
        }).thenIdle(CrafterManagerMenu.LIST_INTERVAL).thenExecute(() -> {
            ServerPlayer owner = players[0];
            ServerPlayer crafter = players[1];
            NetworkState network = network(helper, first);
            CrafterManagerMenu menu = menus[0];
            menu.broadcastChanges();
            helper.assertTrue(last(helper, toCrafter).providers().get(0).editable(),
                    "the list follows the access change");
            helper.assertTrue(menu.select(crafter, pos(helper, 3), Direction.NORTH), "picked with full access");
            crafterLevel(helper, crafter, AccessLevel.CRAFT);
            menu.broadcastChanges();
            helper.assertTrue(menu.selected() == null, "the pick goes with the access");
            menu.clicked(0, 0, ContainerInput.PICKUP, crafter);
            helper.assertTrue(menu.getCarried().isEmpty(), "nothing taken after losing access");
            helper.assertValueEqual(patterns(first.logic()), 1, "the pattern stays");

            CrafterManagerMenu mine = menu(helper, owner, network, toOwner, 92);
            helper.assertFalse(mine.select(owner, pos(helper, 9), Direction.NORTH), "another network's provider");
            helper.assertFalse(mine.select(owner, pos(helper, 3), Direction.SOUTH), "wrong facing");
            mine.broadcastChanges();
            helper.assertValueEqual(last(helper, toOwner).providers().size(), 2, "only this network's providers");
        }).thenSucceed();
    }

    /**
     * The owner takes the first provider's pattern, picks the second and puts it into its slot 2: exactly one pattern
     * exists afterwards, in the second provider, and the network lists it there.
     */
    private static void moveBetween(GameTestHelper helper) {
        ServerPlayer[] players = secured(helper);
        PatternProviderBlockEntity first = provider(helper, 3, true);
        PatternProviderBlockEntity second = provider(helper, 5, false);
        List<CustomPacketPayload> sent = new ArrayList<>();
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(network(helper, first) == network(helper, second), "one network");
        }).thenExecute(() -> {
            ServerPlayer owner = players[0];
            CrafterManagerMenu menu = menu(helper, owner, network(helper, first), sent, 101);
            helper.assertTrue(menu.select(owner, pos(helper, 3), Direction.NORTH), "pick the first");
            menu.clicked(0, 0, ContainerInput.PICKUP, owner);
            helper.assertTrue(menu.select(owner, pos(helper, 5), Direction.NORTH), "pick the second");
            helper.assertTrue(isPattern(menu.getCarried()), "still on the cursor");
            menu.clicked(2, 0, ContainerInput.PICKUP, owner);
            helper.assertValueEqual(patterns(first.logic()), 0, "none left in the first");
            helper.assertValueEqual(patterns(second.logic()), 1, "one in the second");
            helper.assertTrue(isPattern(second.logic().slots().getItem(2)), "in slot 2");
            helper.assertValueEqual(held(owner, menu), 0, "none held");
            menu.sendListNow();
            CrafterManager.Providers list = last(helper, sent);
            helper.assertTrue(list.providers().get(0).outputs().isEmpty(), "the first lists none");
            helper.assertValueEqual(list.providers().get(1).outputs(), List.of(STICK), "the second lists it");
            helper.assertValueEqual(list.selected(), 1, "the second is picked");
            helper.assertValueEqual(network(helper, first).patterns().patternsFor(STICK).size(), 1,
                    "the network has it once");
        }).thenSucceed();
    }

    /** Saves the provider at {@code x} and takes it out of its chunk, as an unload does. */
    private static CompoundTag unload(GameTestHelper helper, PatternProviderBlockEntity provider) {
        CompoundTag tag = provider.saveWithFullMetadata(helper.getLevel().registryAccess());
        provider.onChunkUnloaded();
        BlockPos abs = provider.getBlockPos();
        helper.getLevel().getChunkAt(abs).removeBlockEntity(abs);
        return tag;
    }

    /**
     * The picked provider's chunk unloads: the menu drops the pick, its slots show empty and give nothing, the list
     * leaves it out and it cannot be picked; once loaded again it is listed and editable with its pattern.
     */
    private static void unloaded(GameTestHelper helper) {
        ServerPlayer[] players = secured(helper);
        PatternProviderBlockEntity first = provider(helper, 3, true);
        PatternProviderBlockEntity second = provider(helper, 5, false);
        List<CustomPacketPayload> sent = new ArrayList<>();
        CrafterManagerMenu[] menu = new CrafterManagerMenu[1];
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(network(helper, first) == network(helper, second), "one network");
        }).thenExecute(() -> {
            ServerPlayer owner = players[0];
            menu[0] = menu(helper, owner, network(helper, first), sent, 111);
            helper.assertTrue(menu[0].select(owner, pos(helper, 3), Direction.NORTH), "pick the first");
            CompoundTag tag = unload(helper, first);
            menu[0].broadcastChanges();
            helper.assertTrue(menu[0].selected() == null, "the pick is dropped");
            helper.assertTrue(menu[0].getSlot(0).getItem().isEmpty(), "its slots show empty");
            menu[0].clicked(0, 0, ContainerInput.PICKUP, owner);
            helper.assertTrue(menu[0].getCarried().isEmpty(), "nothing taken");
            helper.assertValueEqual(patterns(first.logic()), 1, "the pattern stays in the provider");
            helper.assertFalse(menu[0].select(owner, pos(helper, 3), Direction.NORTH), "cannot be picked");
            menu[0].sendListNow();
            helper.assertValueEqual(last(helper, sent).providers().size(), 1, "left out of the list");
            BlockEntity loaded = BlockEntity.loadStatic(first.getBlockPos(), first.getBlockState(), tag,
                    helper.getLevel().registryAccess());
            if (loaded == null) {
                throw helper.assertionException(Component.literal("the provider did not load"));
            }
            helper.getLevel().getChunkAt(first.getBlockPos()).addAndRegisterBlockEntity(loaded);
        }).thenWaitUntil(() -> {
            PatternProviderBlockEntity again = helper.getBlockEntity(NetworkGameTests.at(3),
                    PatternProviderBlockEntity.class);
            helper.assertTrue(again.network() != null, "back on the network");
        }).thenExecute(() -> {
            helper.assertTrue(menu[0].select(players[0], pos(helper, 3), Direction.NORTH), "picked again");
            helper.assertTrue(isPattern(menu[0].getSlot(0).getItem()), "with its pattern");
            menu[0].sendListNow();
            helper.assertValueEqual(last(helper, sent).providers().size(), 2, "listed again");
        }).thenSucceed();
    }

    /** A player whose team completed the Wireless research, with a connection that drops every packet. */
    private static ServerPlayer researched(GameTestHelper helper, String name) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), name));
        ResearchTeam team = Research.team(player, true);
        Research.addPoints(level.getServer(), team, 2000);
        for (String node : List.of("port_tier_2", "antennas", "wireless")) {
            helper.assertValueEqual(Research.spendPoints(level.getServer(), team, MaxtechStorage.id(node)),
                    Research.Result.DONE, node);
        }
        return player;
    }

    /** A powered controller owned by {@code owner} with a wireless access mount and a storage device. */
    private static void wirelessNetwork(GameTestHelper helper, ServerPlayer owner) {
        helper.setBlock(CORE, ControllerContent.CONTROLLER.get());
        helper.setBlock(CORE.east(), ExpansionContent.WIRELESS_ACCESS_MOUNT.get());
        NetworkGameTests.device(helper, CORE.south());
        NetworkGameTests.power(helper, CORE, 1_000_000);
        NetworkGameTests.controllerEntity(helper, CORE).setOwner(OwnerRecord.of(owner));
        Vec3 centre = helper.absolutePos(CORE).getCenter().add(5, 0, 0);
        owner.setPos(centre);
    }

    private static ItemStack bound(GameTestHelper helper, ItemStack stack) {
        stack.set(ModDataComponents.WIRELESS_LINK.get(),
                GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(CORE)));
        stack.set(ModDataComponents.WIRELESS_ENERGY.get(), 100_000);
        return stack;
    }

    /**
     * Opens a terminal menu over {@code source} the way a screen does (contents asked for, then the job list watched)
     * and checks that the job list arrives.
     */
    private static TerminalMenu watchJobs(GameTestHelper helper, ServerPlayer player, TerminalSource source,
            List<CustomPacketPayload> sent, int containerId, String what) {
        TerminalMenu menu = TerminalMenu.server(containerId, player.getInventory(), source, update -> {
        }, sent::add);
        player.containerMenu = menu;
        helper.assertTrue(menu.networked(), what + ": networked, so the jobs tab shows");
        menu.clickMenuButton(player, new TerminalAction(TerminalAction.Kind.REFRESH, -1).buttonId());
        CraftingSession session = Objects.requireNonNull(menu.crafting());
        helper.assertTrue(session.network() != null, what + ": the session sees the network");
        helper.assertTrue(
                RequestContent.handle(player,
                        RequestPayloads.Request.of(containerId, RequestPayloads.Action.WATCH_JOBS)),
                what + ": watch the jobs");
        menu.broadcastChanges();
        boolean listed = false;
        for (CustomPacketPayload payload : sent) {
            listed |= payload instanceof RequestPayloads.Jobs jobs && jobs.containerId() == containerId;
        }
        helper.assertTrue(listed, what + ": the job list arrives");
        return menu;
    }

    /** The wireless terminal's menu is a network terminal, so its jobs tab and the job list work. */
    private static void wirelessJobs(GameTestHelper helper) {
        ServerPlayer player = researched(helper, "manager_wireless");
        wirelessNetwork(helper, player);
        player.setItemInHand(InteractionHand.MAIN_HAND,
                bound(helper, new ItemStack(SignalContent.WIRELESS_TERMINAL.get())));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, CORE).services(NetworkService.WIRELESS_ACCESS), 1,
                        "mount active"))
                .thenExecute(() -> {
                    WirelessTerminalSource source = WirelessTerminalItem.source(player, InteractionHand.MAIN_HAND);
                    if (source == null) {
                        throw helper.assertionException(Component.literal("the terminal reaches no network"));
                    }
                    watchJobs(helper, player, source, new ArrayList<>(), 121, "wireless terminal");
                }).thenSucceed();
    }

    /**
     * The crafting tablet's jobs view: its source lists the jobs but shows no contents and moves nothing, and the jobs
     * button refuses a player without craft access while the owner's goes through.
     */
    private static void tabletJobs(GameTestHelper helper) {
        ServerPlayer player = researched(helper, "manager_tablet");
        wirelessNetwork(helper, player);
        player.setItemInHand(InteractionHand.MAIN_HAND,
                bound(helper, new ItemStack(CraftingTabletContent.TABLET.get())));
        ServerPlayer viewer = researched(helper, "manager_tablet_viewer");
        viewer.setPos(player.position());
        viewer.setItemInHand(InteractionHand.MAIN_HAND,
                bound(helper, new ItemStack(CraftingTabletContent.TABLET.get())));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, CORE).services(NetworkService.WIRELESS_ACCESS), 1,
                        "mount active"))
                .thenExecute(() -> {
                    TabletAccess access = CraftingTabletItem.access(player, InteractionHand.MAIN_HAND);
                    if (access == null) {
                        throw helper.assertionException(Component.literal("the tablet reaches no network"));
                    }
                    NetworkState network = Objects.requireNonNull(access.network());
                    NetworkGameTests.insert(network, STICK, 5);
                    TabletJobs.Source source = new TabletJobs.Source(access);
                    watchJobs(helper, player, source, new ArrayList<>(), 131, "crafting tablet");
                    helper.assertTrue(source.contents().isEmpty(), "no contents shown");
                    helper.assertFalse(source.canTake(player) || source.canDeposit(player), "nothing moves");

                    CraftingTabletMenu tablet = CraftingTabletMenu.server(132, player.getInventory(), access);
                    player.containerMenu = tablet;
                    helper.assertTrue(tablet.clickMenuButton(player, CraftingTabletMenu.JOBS), "the owner's jobs");

                    TabletAccess theirs = CraftingTabletItem.access(viewer, InteractionHand.MAIN_HAND);
                    helper.assertTrue(theirs != null && !theirs.canCraft(), "a stranger without craft access");
                    CraftingTabletMenu other = CraftingTabletMenu.server(133, viewer.getInventory(),
                            Objects.requireNonNull(theirs));
                    viewer.containerMenu = other;
                    helper.assertFalse(other.clickMenuButton(viewer, CraftingTabletMenu.JOBS), "refused");
                }).thenSucceed();
    }

    /**
     * The manager lives only while the terminal that opened it is valid: it closes when the owner walks away and opens
     * again on return, slot clicks are refused meanwhile, and it closes for good once the terminal is broken.
     */
    private static void closesWithTerminal(GameTestHelper helper) {
        ServerPlayer[] players = secured(helper);
        PatternProviderBlockEntity first = provider(helper, 3, true);
        PatternProviderBlockEntity second = provider(helper, 5, false);
        List<CustomPacketPayload> sent = new ArrayList<>();
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(network(helper, first) == network(helper, second), "one network");
        }).thenExecute(() -> {
            ServerPlayer owner = players[0];
            Vec3 at = owner.position();
            CrafterManagerMenu menu = menu(helper, owner, network(helper, first), sent, 141);
            helper.assertTrue(menu.select(owner, pos(helper, 3), Direction.NORTH), "pick the first");
            helper.assertTrue(menu.stillValid(owner), "valid at the terminal");
            owner.setPos(at.add(500, 0, 0));
            helper.assertFalse(menu.stillValid(owner), "closes 500 blocks away");
            menu.clicked(0, 0, ContainerInput.PICKUP, owner);
            helper.assertTrue(menu.getCarried().isEmpty(), "nothing taken from afar");
            helper.assertValueEqual(patterns(first.logic()), 1, "the pattern stays");
            owner.setPos(at);
            helper.assertTrue(menu.stillValid(owner), "valid again at the terminal");
            helper.setBlock(NetworkGameTests.at(4), Blocks.AIR);
            helper.assertFalse(menu.stillValid(owner), "closes with the terminal broken");
            menu.clicked(0, 0, ContainerInput.PICKUP, owner);
            helper.assertTrue(menu.getCarried().isEmpty(), "nothing taken without the terminal");
        }).thenSucceed();
    }

    /**
     * Opened from a wireless terminal, the manager (and a provider screen opened from the overview) stays open in range
     * only: leaving the range closes it, coming back opens it, and dropping the terminal closes it.
     */
    private static void wirelessBound(GameTestHelper helper) {
        ServerPlayer player = researched(helper, "manager_wireless_bound");
        wirelessNetwork(helper, player);
        player.setItemInHand(InteractionHand.MAIN_HAND,
                bound(helper, new ItemStack(SignalContent.WIRELESS_TERMINAL.get())));
        BlockPos providerPos = CORE.west();
        helper.setBlock(providerPos.north(), Blocks.STONE);
        helper.setBlock(providerPos, ProviderContent.PROVIDER.get().defaultBlockState()
                .setValue(PatternProviderBlock.FACING, Direction.NORTH));
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertValueEqual(
                    NetworkGameTests.controllerEntity(helper, CORE).services(NetworkService.WIRELESS_ACCESS), 1,
                    "mount active");
            helper.assertTrue(helper.getBlockEntity(providerPos, PatternProviderBlockEntity.class).network() != null,
                    "provider joined");
        }).thenExecute(() -> {
            WirelessTerminalSource source = WirelessTerminalItem.source(player, InteractionHand.MAIN_HAND);
            if (source == null) {
                throw helper.assertionException(Component.literal("the terminal reaches no network"));
            }
            NetworkState network = Objects.requireNonNull(source.network());
            CrafterManagerMenu menu = menu(player, network, source, new ArrayList<>(), 151);
            PatternProviderBlockEntity provider = helper.getBlockEntity(providerPos, PatternProviderBlockEntity.class);
            Predicate<Player> screen = PatternOverview.remoteValid(provider.logic(), network, source);
            Vec3 in = player.position();
            helper.assertTrue(menu.stillValid(player) && screen.test(player), "valid in range");
            player.setPos(in.add(70, 0, 0));
            helper.assertFalse(menu.stillValid(player), "the manager closes out of range");
            helper.assertFalse(screen.test(player), "the provider screen closes out of range");
            player.setPos(in);
            helper.assertTrue(menu.stillValid(player), "valid back in range");
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            helper.assertFalse(menu.stillValid(player), "the manager closes with the terminal dropped");
            helper.assertFalse(screen.test(player), "the provider screen closes with the terminal dropped");
        }).thenSucceed();
    }

    /**
     * Through the wireless terminal, a crafting request and opening the crafter manager each pay one operation from the
     * terminal's charge; watching the job list is free; without the charge for one operation the request is refused and
     * nothing is paid.
     */
    private static void wirelessCost(GameTestHelper helper) {
        ServerPlayer player = researched(helper, "manager_wireless_cost");
        wirelessNetwork(helper, player);
        player.setItemInHand(InteractionHand.MAIN_HAND,
                bound(helper, new ItemStack(SignalContent.WIRELESS_TERMINAL.get())));
        int cost = WirelessTerminalItem.energyPerOperation();
        TerminalMenu[] menu = new TerminalMenu[1];
        int[] start = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, CORE).services(NetworkService.WIRELESS_ACCESS), 1,
                        "mount active"))
                .thenExecute(() -> {
                    helper.assertTrue(cost > 0, "the preset charges per operation");
                    WirelessTerminalSource source = WirelessTerminalItem.source(player, InteractionHand.MAIN_HAND);
                    if (source == null) {
                        throw helper.assertionException(Component.literal("the terminal reaches no network"));
                    }
                    start[0] = WirelessTerminalItem.energy(player.getMainHandItem());
                    menu[0] = watchJobs(helper, player, source, new ArrayList<>(), 161, "wireless terminal");
                    helper.assertValueEqual(WirelessTerminalItem.energy(player.getMainHandItem()), start[0],
                            "watching the jobs is free");
                }).thenIdle(1).thenExecute(() -> {
                    ItemStack held = player.getMainHandItem();
                    helper.assertTrue(RequestContent.handle(player, plan(menu[0])), "a plan request");
                    helper.assertValueEqual(WirelessTerminalItem.energy(held), start[0] - cost, "the request paid");
                    helper.assertTrue(CrafterManager.open(player, Objects.requireNonNull(menu[0].crafting())),
                            "the manager opens");
                    helper.assertValueEqual(WirelessTerminalItem.energy(held), start[0] - 2 * cost, "opening paid");
                }).thenWaitUntil(() -> helper.assertFalse(Objects.requireNonNull(menu[0].crafting()).planning(),
                        "the plan came back"))
                .thenIdle(1).thenExecute(() -> {
                    ItemStack held = player.getMainHandItem();
                    held.set(ModDataComponents.WIRELESS_ENERGY.get(), cost - 1);
                    helper.assertFalse(RequestContent.handle(player, plan(menu[0])), "refused without the charge");
                    helper.assertValueEqual(WirelessTerminalItem.energy(held), cost - 1, "nothing paid");
                }).thenSucceed();
    }

    private static RequestPayloads.Request plan(TerminalMenu menu) {
        return new RequestPayloads.Request(menu.containerId, RequestPayloads.Action.PLAN, 1, Optional.of(STICK), 1,
                Optional.empty());
    }
}
