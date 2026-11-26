package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.ModuleKind;
import dev.silencebound.maxtech.storage.network.NetworkService;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.port.PortPart;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import dev.silencebound.maxtech.storage.tool.blueprint.Blueprints;
import dev.silencebound.maxtech.storage.tool.setup.SetupBlueprint;
import dev.silencebound.maxtech.storage.tool.setup.SetupBlueprintItem;
import dev.silencebound.maxtech.storage.tool.setup.SetupBuild;
import dev.silencebound.maxtech.storage.tool.setup.SetupContent;
import dev.silencebound.maxtech.storage.tool.setup.SetupCopy;
import dev.silencebound.maxtech.storage.tool.setup.SetupGhosts;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.jspecify.annotations.Nullable;

/**
 * Auto-build of setup blueprint ghosts (M7C item 21 part B): the ghost's blocks and parts are placed with items from
 * the network of the carried wireless terminal, turned with the ghost, and take their settings; only what was placed
 * leaves storage; missing items and refused positions stay as ghost cells; no take-out right builds nothing; the
 * per-tick bound holds; the terminal is looked up once, not per tick; a running job resumes after a save and reload; a
 * dyed cable is built in its colour. The network: a controller with a wireless mount, a cable and a test drive, as the
 * network pick tests build it.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class SetupBuildGameTests {
    private static final String[] NAMES = {"setup_build_rotated", "setup_build_missing", "setup_build_refused",
            "setup_build_denied", "setup_build_colour", "setup_build_dye_refused"};
    private static final String RATE = "setup_build_rate";
    private static final String LOCATE = "setup_build_locate";
    private static final String RESUME = "setup_build_resume";
    /** Where the terminal sits in the builder's inventory. */
    private static final int TERMINAL_SLOT = 20;
    private static final BlockPos CORE = new BlockPos(5, 1, 2);
    private static final BlockPos ORIGIN = new BlockPos(3, 1, 8);
    /** Absolute positions where a placement event is cancelled, as a protection mod would. */
    private static final Set<BlockPos> PROTECTED = new HashSet<>();
    /** Absolute positions where a right-click is cancelled, as a claim mod refuses a use. */
    private static final Set<BlockPos> NO_USE = new HashSet<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(NAMES[0], SetupBuildGameTests::rotated);
        register(NAMES[1], SetupBuildGameTests::missing);
        register(NAMES[2], SetupBuildGameTests::refused);
        register(NAMES[3], SetupBuildGameTests::denied);
        register(NAMES[4], SetupBuildGameTests::colour);
        register(NAMES[5], SetupBuildGameTests::dyeRefused);
        register(RATE, SetupBuildGameTests::rate);
        register(LOCATE, SetupBuildGameTests::locate);
        register(RESUME, SetupBuildGameTests::resume);
    }

    private SetupBuildGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("setup_build"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 200);
        }
        Holder<TestEnvironmentDefinition<?>> slow = event.registerEnvironment(MaxtechStorage.id("setup_build_slow"),
                new StorageTestEnvironment("setup.buildPerTick", "2", false));
        StorageTests.test(event, slow, RATE, MaxtechStorage.id("empty_16x12x16"), 200);
        // Its own batch: the per-tick budget is shared by all jobs, so a second build next to setup_build_rate could
        // take that test's first tick.
        Holder<TestEnvironmentDefinition<?>> locate = event.registerEnvironment(MaxtechStorage.id("setup_build_locate"),
                new StorageTestEnvironment("setup.buildPerTick", "2", false));
        StorageTests.test(event, locate, LOCATE, MaxtechStorage.id("empty_16x12x16"), 200);
        // Its own batch: the test drops every running job, as a server stop does, and reloads the saved data.
        Holder<TestEnvironmentDefinition<?>> restart = event.registerEnvironment(
                MaxtechStorage.id("setup_build_restart"), new StorageTestEnvironment("setup.buildPerTick", "2", false));
        StorageTests.test(event, restart, RESUME, MaxtechStorage.id("empty_16x12x16"), 200);
    }

    @SubscribeEvent
    static void placed(BlockEvent.EntityPlaceEvent event) {
        if (PROTECTED.contains(event.getPos())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void used(PlayerInteractEvent.RightClickBlock event) {
        if (NO_USE.contains(event.getPos())) {
            event.setCanceled(true);
        }
    }

    private static TestStorage.TestDevice network(GameTestHelper helper) {
        helper.setBlock(CORE, ControllerContent.CONTROLLER.get());
        helper.setBlock(CORE.east(), ExpansionContent.WIRELESS_ACCESS_MOUNT.get());
        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(CORE.south()),
                CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        return NetworkGameTests.device(helper, CORE.south(2));
    }

    private static void ready(GameTestHelper helper, TestStorage.TestDevice drive) {
        ControllerBlockEntity core = NetworkGameTests.controllerEntity(helper, CORE);
        helper.assertValueEqual(core.services(NetworkService.WIRELESS_ACCESS), 1, "mount active");
        helper.assertTrue(drive.network() != null, "drive joined");
        NetworkGameTests.power(helper, CORE, 100_000);
    }

    private static NetworkState state(GameTestHelper helper) {
        return NetworkGameTests.state(helper, CORE);
    }

    private static long stored(GameTestHelper helper, Item item) {
        return state(helper).contents().amount(ItemKey.of(item));
    }

    private static void seed(GameTestHelper helper, Item item, long amount) {
        helper.assertValueEqual(NetworkGameTests.insert(state(helper), ItemKey.of(item), amount), amount,
                "seeded " + item);
    }

    /** A player with the Wireless research and a bound, charged wireless terminal, standing at the controller. */
    private static ServerPlayer builder(GameTestHelper helper, String name, boolean terminal) {
        return builder(helper, NetworkGameTests.player(helper.getLevel(), name), terminal);
    }

    private static ServerPlayer builder(GameTestHelper helper, ServerPlayer player, boolean terminal) {
        ServerLevel level = helper.getLevel();
        ResearchTeam team = Research.team(player, true);
        Research.addPoints(level.getServer(), team, 2000);
        for (String node : List.of("port_tier_2", "antennas", "wireless")) {
            helper.assertValueEqual(Research.spendPoints(level.getServer(), team, MaxtechStorage.id(node)),
                    Research.Result.DONE, node);
        }
        player.getAbilities().instabuild = false;
        player.setPos(Vec3.atCenterOf(helper.absolutePos(CORE)));
        if (terminal) {
            ItemStack stack = new ItemStack(SignalContent.WIRELESS_TERMINAL.get());
            stack.set(ModDataComponents.WIRELESS_LINK.get(),
                    GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(CORE)));
            stack.set(ModDataComponents.WIRELESS_ENERGY.get(), 100_000);
            player.getInventory().setItem(TERMINAL_SLOT, stack);
        }
        return player;
    }

    private static Item port() {
        return PortContent.PORT.get().asItem();
    }

    /** A port facing {@code facing} with no settings, as copied. */
    private static SetupBlueprint.Entry portEntry(BlockPos offset, Direction facing) {
        BlockState state = PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, facing)
                .setValue(PortBlock.MODE, PortMode.IMPORT);
        return new SetupBlueprint.Entry(offset, state, Optional.empty(), List.of());
    }

    private static SetupBlueprint row(int ports) {
        List<SetupBlueprint.Entry> entries = new ArrayList<>();
        for (int i = 0; i < ports; i++) {
            entries.add(portEntry(new BlockPos(i, 0, 0), Direction.SOUTH));
        }
        return new SetupBlueprint(new Vec3i(ports, 1, 1), entries);
    }

    private static SetupGhosts.Ghost paste(GameTestHelper helper, Player player, Rotation rotation,
            SetupBlueprint blueprint) {
        return SetupGhosts.get(helper.getLevel().getServer()).paste(player, helper.getLevel(),
                helper.absolutePos(ORIGIN), rotation, blueprint);
    }

    private static SetupBuild.Job job(GameTestHelper helper, ServerPlayer player) {
        SetupBuild.Job job = SetupBuild.job(player.getUUID());
        if (job == null) {
            throw helper.assertionException(Component.literal("No build job"));
        }
        return job;
    }

    /**
     * A ghost of a south-facing port with export settings next to a cable with an exporting port part on its east face,
     * pasted turned 90 degrees and built by using the blueprint on the ghost: the port faces west, the part sits on the
     * south face, both take the export mode, and one port, one cable and one part left storage. The finished ghost goes
     * away.
     */
    private static void rotated(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ServerPlayer player = builder(helper, "build_rotated", true);
        Player templater = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockPos templatePort = new BlockPos(13, 1, 13);
        helper.setBlock(templatePort,
                PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.SOUTH));
        PortBlockEntity source = helper.getBlockEntity(templatePort, PortBlockEntity.class);
        source.setMode(PortMode.EXPORT);
        source.filter().setItem(0, new ItemStack(Items.COBBLESTONE));
        NetworkGameTests.cables(helper, 12, 12, CableContent.STORAGE_CABLE_TIER_1.type().get());
        PartGameTests.usePart(helper, templater, 12, Direction.EAST, new ItemStack(PortContent.PORT_PART_ITEM.get()));
        PortPart sourcePart = (PortPart) PartGameTests.part(helper, PartGameTests.parts(helper, 12), Direction.EAST);
        sourcePart.setMode(PortMode.EXPORT);
        BlockState cableState = helper.getBlockState(NetworkGameTests.at(12));
        SetupBlueprint blueprint = new SetupBlueprint(new Vec3i(1, 1, 2),
                List.of(new SetupBlueprint.Entry(BlockPos.ZERO, source.getBlockState(),
                        Optional.of(Blueprints.copy(source)), List.of()),
                        new SetupBlueprint.Entry(new BlockPos(0, 0, 1), cableState, Optional.empty(),
                                List.of(new SetupBlueprint.Part(Direction.EAST, sourcePart.type().id(),
                                        Optional.of(Blueprints.copy(sourcePart)))))));
        Item cable = cableState.getBlock().asItem();
        Item part = PortContent.PORT_PART_ITEM.get();
        SetupGhosts ghosts = SetupGhosts.get(helper.getLevel().getServer());
        SetupGhosts.Ghost[] pasted = new SetupGhosts.Ghost[1];
        helper.startSequence().thenWaitUntil(() -> ready(helper, drive)).thenExecute(() -> {
            seed(helper, port(), 5);
            seed(helper, cable, 5);
            seed(helper, part, 5);
            SetupGhosts.Ghost ghost = paste(helper, player, Rotation.CLOCKWISE_90, blueprint);
            pasted[0] = ghost;
            BlockPos portAt = ghost.posOf(blueprint.entries().get(0));
            // A use on the floor below the ghost's port cell starts the build.
            ItemStack item = new ItemStack(SetupContent.ITEM.get());
            SetupBlueprintItem.use(helper.getLevel(), portAt.below(), Direction.UP, player, item);
            helper.assertTrue(SetupBuild.job(player.getUUID()) != null, "a build job started");
        }).thenWaitUntil(() -> helper.assertTrue(ghosts.ghosts(player.getUUID()).isEmpty(), "the finished ghost"))
                .thenWaitUntil(() -> {
                    BlockPos portAt = pasted[0].posOf(blueprint.entries().get(0));
                    if (!(helper.getLevel().getBlockEntity(portAt) instanceof PortBlockEntity built)) {
                        throw helper.assertionException(Component.literal("No port at " + portAt.toShortString()));
                    }
                    helper.assertValueEqual(built.facing(), Direction.WEST, "the turned port's facing");
                    helper.assertValueEqual(built.mode(), PortMode.EXPORT, "the built port's mode");
                    helper.assertTrue(built.filter().getItem(0).is(Items.COBBLESTONE), "the built port's filter");
                    BlockPos cableAt = pasted[0].posOf(blueprint.entries().get(1));
                    CableParts parts = StorageCableBlock.parts(helper.getLevel(), cableAt);
                    helper.assertTrue(parts != null, "the built cable");
                    CablePart builtPart = parts == null ? null : parts.part(Direction.SOUTH);
                    helper.assertTrue(builtPart instanceof PortPart port && port.mode() == PortMode.EXPORT,
                            "the turned part's mode");
                }).thenExecute(() -> {
                    helper.assertValueEqual(stored(helper, port()), 4L, "ports left");
                    helper.assertValueEqual(stored(helper, cable), 4L, "cables left");
                    helper.assertValueEqual(stored(helper, part), 4L, "parts left");
                    // Per player: other tests of this batch run their own builds at the same time.
                }).thenWaitUntil(() -> helper.assertTrue(SetupBuild.job(player.getUUID()) == null, "the job ended"))
                .thenSucceed();
    }

    /**
     * A ghost of three ports with one port in storage: the first is built, the other two stay as ghost cells, the job
     * names two missing ports, and storage holds none.
     */
    private static void missing(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ServerPlayer player = builder(helper, "build_missing", true);
        SetupGhosts ghosts = SetupGhosts.get(helper.getLevel().getServer());
        SetupBuild.Job[] job = new SetupBuild.Job[1];
        helper.startSequence().thenWaitUntil(() -> ready(helper, drive)).thenExecute(() -> {
            seed(helper, port(), 1);
            SetupGhosts.Ghost ghost = paste(helper, player, Rotation.NONE, row(3));
            helper.assertValueEqual(SetupBuild.start(player, ghost), SetupBuild.Start.STARTED, "started");
            job[0] = job(helper, player);
        }).thenWaitUntil(() -> helper.assertTrue(job[0].ended(), "the job ended")).thenExecute(() -> {
            helper.assertValueEqual(job[0].placed(), 1, "placed");
            helper.assertValueEqual(job[0].missing().getOrDefault(port(), 0), 2, "missing ports");
            helper.assertValueEqual(stored(helper, port()), 0L, "ports left");
            helper.assertBlockPresent(PortContent.PORT.get(), ORIGIN);
            helper.assertBlockNotPresent(PortContent.PORT.get(), ORIGIN.east());
            helper.assertBlockNotPresent(PortContent.PORT.get(), ORIGIN.east(2));
            helper.assertValueEqual(ghosts.ghosts(player.getUUID()).size(), 1, "the ghost stays");
            helper.assertValueEqual(player.getInventory().countItem(port()), 0, "ports given to the player");
        }).thenSucceed();
    }

    /**
     * A ghost of two ports whose first cell holds stone and whose second a placement listener refuses, as a claim
     * would: neither is built, both count as refused, and no port leaves storage or reaches the player.
     */
    @SuppressWarnings("removal") // NeoForge answers a refused placement with a packet: the player needs a connection
    private static void refused(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ServerPlayer player = builder(helper, helper.makeMockServerPlayerInLevel(), true);
        player.setGameMode(GameType.SURVIVAL);
        helper.setBlock(ORIGIN, Blocks.STONE);
        BlockPos claimed = helper.absolutePos(ORIGIN.east()).immutable();
        PROTECTED.add(claimed);
        SetupBuild.Job[] job = new SetupBuild.Job[1];
        helper.startSequence().thenWaitUntil(() -> ready(helper, drive)).thenExecute(() -> {
            seed(helper, port(), 5);
            SetupGhosts.Ghost ghost = paste(helper, player, Rotation.NONE, row(2));
            helper.assertValueEqual(SetupBuild.start(player, ghost), SetupBuild.Start.STARTED, "started");
            job[0] = job(helper, player);
        }).thenWaitUntil(() -> helper.assertTrue(job[0].ended(), "the job ended")).thenExecute(() -> {
            PROTECTED.remove(claimed);
            helper.assertValueEqual(job[0].placed(), 0, "placed");
            helper.assertValueEqual(job[0].blocked(), 2, "refused");
            helper.assertBlockPresent(Blocks.STONE, ORIGIN);
            helper.assertBlockNotPresent(PortContent.PORT.get(), ORIGIN.east());
            helper.assertValueEqual(stored(helper, port()), 5L, "ports left");
            helper.assertValueEqual(player.getInventory().countItem(port()), 0, "ports given to the player");
        }).thenSucceed();
    }

    /**
     * On a network owned by someone else, a stranger with a bound terminal is refused before anything happens; a player
     * without a terminal too. Nothing is built or taken.
     */
    private static void denied(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ServerPlayer owner = builder(helper, "build_owner", true);
        ServerPlayer stranger = builder(helper, "build_stranger", true);
        ServerPlayer bare = builder(helper, "build_bare", false);
        helper.startSequence().thenWaitUntil(() -> ready(helper, drive)).thenExecute(() -> {
            seed(helper, port(), 5);
            NetworkGameTests.controllerEntity(helper, CORE).setOwner(OwnerRecord.of(owner));
        }).thenWaitUntil(() -> helper.assertTrue(state(helper).owner().isOwner(owner.getUUID()), "owned"))
                .thenExecute(() -> {
                    SetupGhosts.Ghost ghost = paste(helper, stranger, Rotation.NONE, row(2));
                    helper.assertValueEqual(SetupBuild.start(stranger, ghost), SetupBuild.Start.DENIED, "stranger");
                    SetupGhosts.Ghost bareGhost = paste(helper, bare, Rotation.NONE, row(2));
                    helper.assertValueEqual(SetupBuild.start(bare, bareGhost), SetupBuild.Start.NO_TERMINAL,
                            "no terminal");
                    // Per player: other tests of this batch run their own builds at the same time.
                    helper.assertTrue(
                            SetupBuild.job(stranger.getUUID()) == null && SetupBuild.job(bare.getUUID()) == null,
                            "jobs running");
                }).thenIdle(3).thenExecute(() -> {
                    helper.assertBlockNotPresent(PortContent.PORT.get(), ORIGIN);
                    helper.assertValueEqual(stored(helper, port()), 5L, "ports left");
                }).thenSucceed();
    }

    /**
     * With {@code setup.buildPerTick} at 2, a ghost of eight ports is built two per tick: after the first build tick at
     * most two stand, and the job needs at least four ticks.
     */
    private static void rate(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ServerPlayer player = builder(helper, "build_rate", true);
        SetupBuild.Job[] job = new SetupBuild.Job[1];
        helper.startSequence().thenWaitUntil(() -> ready(helper, drive)).thenExecute(() -> {
            seed(helper, port(), 8);
            SetupGhosts.Ghost ghost = paste(helper, player, Rotation.NONE, row(8));
            helper.assertValueEqual(SetupBuild.start(player, ghost), SetupBuild.Start.STARTED, "started");
            job[0] = job(helper, player);
        }).thenExecuteAfter(1, () -> {
            helper.assertTrue(job[0].placed() > 0 && job[0].placed() <= 2, "placed after one tick: " + job[0].placed());
        }).thenWaitUntil(() -> helper.assertTrue(job[0].ended(), "the job ended")).thenExecute(() -> {
            helper.assertValueEqual(job[0].placed(), 8, "placed");
            helper.assertTrue(job[0].ticks() >= 4, "ticks: " + job[0].ticks());
            helper.assertValueEqual(stored(helper, port()), 0L, "ports left");
        }).thenSucceed();
    }

    /**
     * With two placements per tick, the eight-port build looks the terminal up once when it starts; moving the terminal
     * to another slot mid-build makes it look once more, and the build goes on. Looking it up each tick would count at
     * least five.
     */
    private static void locate(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ServerPlayer player = builder(helper, "build_locate", true);
        SetupBuild.Job[] job = new SetupBuild.Job[1];
        helper.startSequence().thenWaitUntil(() -> ready(helper, drive)).thenExecute(() -> {
            seed(helper, port(), 8);
            SetupGhosts.Ghost ghost = paste(helper, player, Rotation.NONE, row(8));
            helper.assertValueEqual(SetupBuild.start(player, ghost), SetupBuild.Start.STARTED, "started");
            job[0] = job(helper, player);
        }).thenExecuteAfter(2, () -> {
            helper.assertValueEqual(job[0].locates(), 1, "look-ups while the terminal stays");
            Inventory inventory = player.getInventory();
            ItemStack terminal = inventory.getItem(TERMINAL_SLOT);
            inventory.setItem(TERMINAL_SLOT, ItemStack.EMPTY);
            inventory.setItem(TERMINAL_SLOT + 1, terminal);
        }).thenWaitUntil(() -> helper.assertTrue(job[0].ended(), "the job ended")).thenExecute(() -> {
            helper.assertValueEqual(job[0].placed(), 8, "placed");
            helper.assertTrue(job[0].ticks() >= 4, "ticks: " + job[0].ticks());
            helper.assertValueEqual(job[0].locates(), 2, "look-ups with one move");
            helper.assertValueEqual(stored(helper, port()), 0L, "ports left");
        }).thenSucceed();
    }

    /**
     * An eight-port build, two per tick, is saved after its first tick, every job is dropped as a server stop drops
     * them, and the saved builds and ghosts are loaded again: the player's job resumes on log-in with its count and
     * builds the rest. Exactly eight of ten ports left storage, each cell holds one port, none reached the player and
     * the finished ghost went away.
     */
    private static void resume(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ServerPlayer player = builder(helper, "build_resume", true);
        MinecraftServer server = helper.getLevel().getServer();
        SetupBuild.Job[] job = new SetupBuild.Job[2];
        helper.startSequence().thenWaitUntil(() -> ready(helper, drive)).thenExecute(() -> {
            seed(helper, port(), 10);
            SetupGhosts.Ghost ghost = paste(helper, player, Rotation.NONE, row(8));
            helper.assertValueEqual(SetupBuild.start(player, ghost), SetupBuild.Start.STARTED, "started");
            job[0] = job(helper, player);
        }).thenExecuteAfter(1, () -> {
            int placed = job[0].placed();
            helper.assertTrue(placed > 0 && placed < 8, "placed before the save: " + placed);
            RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
            Tag builds = SetupBuild.Store.CODEC.encodeStart(ops, SetupBuild.store(server)).getOrThrow();
            Tag ghosts = SetupGhosts.CODEC.encodeStart(ops, SetupGhosts.get(server)).getOrThrow();
            SetupBuild.clear();
            helper.assertValueEqual(SetupBuild.running(), 0, "jobs after the stop");
            server.overworld().getDataStorage().set(SetupGhosts.TYPE,
                    SetupGhosts.CODEC.parse(ops, ghosts).getOrThrow());
            SetupBuild.restore(server, SetupBuild.Store.CODEC.parse(ops, builds).getOrThrow());
            helper.assertValueEqual(SetupBuild.store(server).waiting(), 1, "saved jobs");
            helper.assertTrue(SetupBuild.resume(player), "the job resumed");
            job[1] = job(helper, player);
            helper.assertValueEqual(job[1].placed(), placed, "placed carried over");
            helper.assertValueEqual(SetupBuild.store(server).waiting(), 0, "saved jobs after the resume");
        }).thenWaitUntil(() -> helper.assertTrue(job[1] != null && job[1].ended(), "the resumed job ended"))
                .thenExecute(() -> {
                    helper.assertValueEqual(job[1].placed(), 8, "placed");
                    helper.assertValueEqual(job[1].missing().size(), 0, "missing");
                    helper.assertValueEqual(stored(helper, port()), 2L, "ports left");
                    for (int i = 0; i < 8; i++) {
                        helper.assertBlockPresent(PortContent.PORT.get(), ORIGIN.east(i));
                    }
                    helper.assertValueEqual(player.getInventory().countItem(port()), 0, "ports given to the player");
                    helper.assertValueEqual(SetupGhosts.get(server).ghosts(player.getUUID()).size(), 0,
                            "the finished ghost");
                }).thenSucceed();
    }

    /**
     * A red cable copied onto a setup blueprint keeps its colour through the saved and the network form, while an entry
     * saved without one (as before colours were kept) loads uncoloured. Built from the network, the cable is red and
     * one cable and one red dye left storage.
     */
    private static void colour(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ServerPlayer player = builder(helper, "build_colour", true);
        ServerLevel level = helper.getLevel();
        SetupBlueprint blueprint = redCable(helper, player, helper.absolutePos(new BlockPos(13, 1, 13)));
        helper.assertValueEqual(blueprint.entries().getFirst().colour(), Optional.of(DyeColor.RED), "copied colour");
        RegistryOps<Tag> ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        SetupBlueprint saved = SetupBlueprint.CODEC
                .parse(ops, SetupBlueprint.CODEC.encodeStart(ops, blueprint).getOrThrow()).getOrThrow();
        helper.assertValueEqual(saved, blueprint, "saved form");
        SetupBlueprint.Entry plain = new SetupBlueprint.Entry(BlockPos.ZERO, blueprint.entries().getFirst().state(),
                Optional.empty(), List.of());
        Tag plainTag = SetupBlueprint.Entry.CODEC.encodeStart(ops, plain).getOrThrow();
        helper.assertTrue(plainTag instanceof CompoundTag compound && !compound.contains("colour"),
                "an uncoloured entry saves no colour");
        helper.assertValueEqual(SetupBlueprint.Entry.CODEC.parse(ops, plainTag).getOrThrow().colour(),
                Optional.<DyeColor>empty(), "an entry without colour");
        build(helper, drive, player, saved, DyeColor.RED, 1L, 2L);
    }

    /** A red cable copied onto a blueprint at {@code template} (absolute). */
    private static SetupBlueprint redCable(GameTestHelper helper, ServerPlayer player, BlockPos template) {
        ConduitBlock.placeConduit(helper.getLevel(), template, CableContent.STORAGE_CABLE_TIER_1.type().get(),
                DyeColor.RED);
        SetupCopy.Result copied = SetupCopy.copy(player, helper.getLevel(), template, template);
        helper.assertValueEqual(copied.status(), SetupCopy.Status.COPIED, "copied");
        return copied.blueprint().orElseThrow();
    }

    /**
     * A red cable's blueprint built where right-clicks on the ghost cell are cancelled, as a claim refuses a use: the
     * cable is placed (its placement is allowed), stays uncoloured, the refusal counts, and no dye leaves storage or
     * reaches the player.
     */
    private static void dyeRefused(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ServerPlayer player = builder(helper, "build_dye_refused", true);
        SetupBlueprint blueprint = redCable(helper, player, helper.absolutePos(new BlockPos(13, 1, 13)));
        BlockPos claimed = helper.absolutePos(ORIGIN).immutable();
        NO_USE.add(claimed);
        // The position is this test's own, so leaving it in the set does not touch other tests.
        build(helper, drive, player, blueprint, null, 1L, 3L);
    }

    /**
     * Seeds two cables and three red dyes, builds {@code blueprint} at the origin and checks the cable's colour and
     * what is left in storage.
     */
    private static void build(GameTestHelper helper, TestStorage.TestDevice drive, ServerPlayer player,
            SetupBlueprint blueprint, @Nullable DyeColor expected, long cablesLeft, long dyeLeft) {
        Item cable = blueprint.entries().getFirst().state().getBlock().asItem();
        SetupBuild.Job[] job = new SetupBuild.Job[1];
        helper.startSequence().thenWaitUntil(() -> ready(helper, drive)).thenExecute(() -> {
            seed(helper, cable, 2);
            seed(helper, Items.RED_DYE, 3);
            SetupGhosts.Ghost ghost = paste(helper, player, Rotation.NONE, blueprint);
            helper.assertValueEqual(SetupBuild.start(player, ghost), SetupBuild.Start.STARTED, "started");
            job[0] = job(helper, player);
        }).thenWaitUntil(() -> helper.assertTrue(job[0] != null && job[0].ended(), "the job ended")).thenExecute(() -> {
            ConduitBlockEntity built = helper.getBlockEntity(ORIGIN, ConduitBlockEntity.class);
            helper.assertTrue(built.conduit().colour() == expected,
                    "the built cable's colour: " + built.conduit().colour());
            helper.assertValueEqual(job[0].placed(), 1, "placed");
            helper.assertValueEqual(job[0].blocked(), expected == null ? 1 : 0, "refused");
            helper.assertValueEqual(stored(helper, cable), cablesLeft, "cables left");
            helper.assertValueEqual(stored(helper, Items.RED_DYE), dyeLeft, "red dye left");
            helper.assertValueEqual(player.getInventory().countItem(Items.RED_DYE), 0, "dye given back");
            helper.assertValueEqual(player.getMainHandItem().isEmpty(), true, "the held stack put back");
        }).thenSucceed();
    }
}
