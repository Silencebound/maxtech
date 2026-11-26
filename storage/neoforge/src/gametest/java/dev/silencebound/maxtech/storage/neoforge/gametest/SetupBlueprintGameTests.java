package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.serialization.DataResult;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.port.PortPart;
import dev.silencebound.maxtech.storage.tool.blueprint.Blueprints;
import dev.silencebound.maxtech.storage.tool.setup.SetupBlueprint;
import dev.silencebound.maxtech.storage.tool.setup.SetupContent;
import dev.silencebound.maxtech.storage.tool.setup.SetupGhosts;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Setup blueprints (M7C item 21): copying a group of devices onto the item, pasting it as a ghost, and devices placed
 * into the ghost taking the copied settings only when the player may configure them; the item's codec. Cables run along
 * x at y = 1, z = 1 ({@link NetworkGameTests#at}).
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class SetupBlueprintGameTests {
    private static final String[] NAMES = {"setup_blueprint_codec", "setup_blueprint_build", "setup_blueprint_denied",
            "setup_blueprint_rotated", "setup_blueprint_pending", "setup_blueprint_saved", "setup_blueprint_caps",
            "setup_blueprint_login"};

    static {
        StorageTestEnvironment.init();
        register(NAMES[0], SetupBlueprintGameTests::codec);
        register(NAMES[1], SetupBlueprintGameTests::build);
        register(NAMES[2], SetupBlueprintGameTests::denied);
        register(NAMES[3], SetupBlueprintGameTests::rotated);
        register(NAMES[4], SetupBlueprintGameTests::pending);
        register(NAMES[5], SetupBlueprintGameTests::saved);
        register(NAMES[6], SetupBlueprintGameTests::caps);
        register(NAMES[7], SetupBlueprintGameTests::login);
    }

    private SetupBlueprintGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("setup_blueprint"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 200);
        }
    }

    /**
     * Port blocks south of cables x 2 and 3 and a port part on the north face of cable x 4, copied from corner (2, 1,
     * 1) to (4, 1, 2), go into the item; the saved and the network forms give the same blueprint back, a newer version
     * and an entry outside the box do not load, and pasting turned maps every offset back to itself.
     */
    private static void codec(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        NetworkGameTests.cables(helper, 2, 4, CableContent.STORAGE_CABLE_TIER_1.type().get());
        PortBlockEntity port = port(helper, NetworkGameTests.at(3).south());
        port.setOwner(OwnerRecord.of(player));
        port.filter().setItem(0, new ItemStack(Items.COBBLESTONE));
        port.setLabel(DeviceLabel.of("ore", DyeColor.BLUE));
        PartGameTests.usePart(helper, player, 4, Direction.NORTH, new ItemStack(PortContent.PORT_PART_ITEM.get()));
        ItemStack item = new ItemStack(SetupContent.ITEM.get());
        use(helper, player, item, NetworkGameTests.at(2), Direction.UP, false);
        use(helper, player, item, NetworkGameTests.at(4).south(), Direction.UP, false);
        SetupBlueprint blueprint = item.get(SetupContent.BLUEPRINT.get());
        if (blueprint == null) {
            throw helper.assertionException(Component.literal("Nothing copied"));
        }
        helper.assertValueEqual(blueprint.entries().size(), 4, "three cables and a port");
        helper.assertValueEqual(blueprint.size(), new Vec3i(3, 1, 2), "the box");
        SetupBlueprint.Entry cable = blueprint.at(new BlockPos(2, 0, 0)).orElse(null);
        helper.assertTrue(cable != null && cable.part(Direction.NORTH).isPresent(), "the part on cable x 4");
        SetupBlueprint.Entry copiedPort = blueprint.at(new BlockPos(1, 0, 1)).orElse(null);
        helper.assertTrue(copiedPort != null && copiedPort.settings().isPresent(), "the port's settings");

        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Tag saved = SetupBlueprint.CODEC.encodeStart(ops, blueprint).getOrThrow();
        helper.assertValueEqual(SetupBlueprint.CODEC.parse(ops, saved).getOrThrow(), blueprint, "saved form");
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess());
        SetupBlueprint.STREAM_CODEC.encode(buf, blueprint);
        helper.assertValueEqual(SetupBlueprint.STREAM_CODEC.decode(buf), blueprint, "network form");

        CompoundTag newer = ((CompoundTag) saved).copy();
        newer.put("version", IntTag.valueOf(SetupBlueprint.VERSION + 1));
        helper.assertTrue(SetupBlueprint.CODEC.parse(ops, newer).isError(), "a newer version loaded");
        SetupBlueprint outside = new SetupBlueprint(new Vec3i(1, 1, 1), List.of(
                new SetupBlueprint.Entry(new BlockPos(1, 0, 0), port.getBlockState(), Optional.empty(), List.of())));
        DataResult<Tag> encoded = SetupBlueprint.CODEC.encodeStart(ops, outside);
        helper.assertTrue(encoded.isError(), "an entry outside the box saved");

        BlockPos origin = new BlockPos(100, 64, -50);
        for (Rotation rotation : Rotation.values()) {
            for (SetupBlueprint.Entry entry : blueprint.entries()) {
                BlockPos placed = blueprint.place(origin, rotation, entry.offset());
                helper.assertValueEqual(blueprint.offsetOf(origin, rotation, placed), entry.offset(),
                        "offset back at " + rotation);
                Vec3i size = blueprint.rotatedSize(rotation);
                helper.assertTrue(placed.getX() >= origin.getX() && placed.getZ() >= origin.getZ()
                        && placed.getX() < origin.getX() + size.getX() && placed.getZ() < origin.getZ() + size.getZ(),
                        "inside the turned box at " + rotation);
            }
        }
        helper.succeed();
    }

    /**
     * A port (export, cobblestone filter, label) south of cable x 3 and a port part (export, cobblestone) on the north
     * face of cable x 4 are copied and pasted with the floor at x 8 clicked: cables placed at x 8 to 10, the port item
     * placed south of x 9 and a port part put on cable x 10 take the copied settings, and the finished ghost goes away.
     */
    private static void build(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        NetworkGameTests.cables(helper, 2, 4, CableContent.STORAGE_CABLE_TIER_1.type().get());
        PortBlockEntity source = port(helper, NetworkGameTests.at(3).south());
        source.setOwner(OwnerRecord.of(player));
        source.setMode(PortMode.EXPORT);
        source.filter().setItem(0, new ItemStack(Items.COBBLESTONE));
        source.setLabel(DeviceLabel.of("ore", DyeColor.BLUE));
        PortPart sourcePart = portPart(helper, player, 4);
        sourcePart.setMode(PortMode.EXPORT);
        sourcePart.filter().setItem(0, new ItemStack(Items.COBBLESTONE));

        ItemStack item = new ItemStack(SetupContent.ITEM.get());
        use(helper, player, item, NetworkGameTests.at(2), Direction.UP, false);
        use(helper, player, item, NetworkGameTests.at(4).south(), Direction.UP, false);
        use(helper, player, item, new BlockPos(8, 0, 1), Direction.UP, false);
        SetupGhosts ghosts = SetupGhosts.get(helper.getLevel().getServer());
        helper.assertValueEqual(ghosts.ghosts(player.getUUID()).size(), 1, "ghosts after the paste");
        helper.assertValueEqual(ghosts.view(player.getUUID(), helper.getLevel().dimension()).positions().size(), 4,
                "ghost cells");
        helper.assertValueEqual(ghosts.ghosts(player.getUUID()).getFirst().origin(),
                helper.absolutePos(NetworkGameTests.at(8)), "the ghost's origin");

        NetworkGameTests.cables(helper, 8, 10, CableContent.STORAGE_CABLE_TIER_1.type().get());
        helper.setBlock(new BlockPos(9, 0, 2), Blocks.STONE);
        ItemStack portItem = new ItemStack(PortContent.PORT.get().asItem());
        player.setItemInHand(InteractionHand.MAIN_HAND, portItem);
        BlockPos floor = helper.absolutePos(new BlockPos(9, 0, 2));
        portItem.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(floor).relative(Direction.UP, 0.5), Direction.UP, floor, false)));
        BlockPos builtPos = NetworkGameTests.at(9).south();
        helper.assertBlockPresent(PortContent.PORT.get(), builtPos);
        helper.startSequence().thenWaitUntil(() -> {
            PortBlockEntity built = helper.getBlockEntity(builtPos, PortBlockEntity.class);
            helper.assertValueEqual(built.mode(), PortMode.EXPORT, "the built port's mode");
            helper.assertTrue(built.filter().getItem(0).is(Items.COBBLESTONE), "the built port's filter");
            helper.assertValueEqual(built.label(), DeviceLabel.of("ore", DyeColor.BLUE), "the built port's label");
        }).thenExecute(() -> {
            helper.assertValueEqual(ghosts.ghosts(player.getUUID()).size(), 1, "the ghost before the part");
            portPart(helper, player, 10);
        }).thenWaitUntil(() -> {
            PortPart built = (PortPart) PartGameTests.part(helper, PartGameTests.parts(helper, 10), Direction.NORTH);
            helper.assertValueEqual(built.mode(), PortMode.EXPORT, "the built part's mode");
            helper.assertTrue(built.filter().getItem(0).is(Items.COBBLESTONE), "the built part's filter");
        }).thenExecute(() -> helper.assertTrue(ghosts.ghosts(player.getUUID()).isEmpty(), "the finished ghost"))
                .thenSucceed();
    }

    /**
     * A network whose security module gives the player no access: a port the player places into their ghost south of
     * cable x 3 joins it and stays as placed; the ghost's export mode and filter are not applied. A sneak-use on
     * another ghost removes it.
     */
    private static void denied(GameTestHelper helper) {
        Player owner = helper.makeMockPlayer(GameType.SURVIVAL);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        ControllerClusterGameTests.expansion(helper, core.above(), ExpansionContent.SECURITY_MODULE);
        NetworkGameTests.controllerEntity(helper, core).setSecurity(SecuritySettings.DEFAULT
                .withPlayer(player.getUUID(), player.getGameProfile().name(), AccessLevel.NONE));
        NetworkGameTests.cables(helper, 2, 4, CableContent.STORAGE_CABLE_TIER_1.type().get());

        PortBlockEntity template = port(helper, new BlockPos(6, 1, 8));
        template.setMode(PortMode.EXPORT);
        template.filter().setItem(0, new ItemStack(Items.COBBLESTONE));
        SetupBlueprint blueprint = new SetupBlueprint(new Vec3i(1, 1, 1),
                List.of(new SetupBlueprint.Entry(BlockPos.ZERO, template.getBlockState(),
                        Optional.of(Blueprints.copy(template)), List.of())));
        BlockPos builtPos = NetworkGameTests.at(3).south();
        SetupGhosts ghosts = SetupGhosts.get(helper.getLevel().getServer());
        ghosts.paste(player, helper.getLevel(), helper.absolutePos(builtPos), Rotation.NONE, blueprint);
        // Clicked on its north face, the port faces south: its back touches cable x 3.
        helper.setBlock(builtPos.south(), Blocks.STONE);
        BlockPos other = new BlockPos(12, 1, 12);
        ghosts.paste(player, helper.getLevel(), helper.absolutePos(other), Rotation.NONE, blueprint);
        ItemStack item = new ItemStack(SetupContent.ITEM.get());
        use(helper, player, item, other, Direction.UP, true);
        helper.assertValueEqual(ghosts.ghosts(player.getUUID()).size(), 1, "ghosts after a sneak-use on one");
        player.setShiftKeyDown(false);
        helper.startSequence().thenWaitUntil(
                () -> helper.assertTrue(NetworkGameTests.controllerEntity(helper, core).secured(), "module active"))
                .thenExecute(() -> {
                    ItemStack portItem = new ItemStack(PortContent.PORT.get().asItem());
                    player.setItemInHand(InteractionHand.MAIN_HAND, portItem);
                    BlockPos wall = helper.absolutePos(builtPos.south());
                    portItem.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, new BlockHitResult(
                            Vec3.atCenterOf(wall).relative(Direction.NORTH, 0.5), Direction.NORTH, wall, false)));
                    helper.assertBlockPresent(PortContent.PORT.get(), builtPos);
                })
                .thenWaitUntil(() -> helper.assertTrue(helper.getBlockEntity(builtPos, PortBlockEntity.class)
                        .network() == NetworkGameTests.state(helper, core), "port joined"))
                .thenIdle(3).thenExecute(() -> {
                    PortBlockEntity built = helper.getBlockEntity(builtPos, PortBlockEntity.class);
                    helper.assertTrue(!NetworkAccess.canConfigure(player, built), "the player may configure the port");
                    helper.assertValueEqual(built.mode(), PortMode.IMPORT, "the mode without access");
                    helper.assertTrue(built.filter().isBlank(), "the filter without access");
                    helper.assertTrue(
                            !SetupGhosts.isPending(helper.getLevel().dimension(), helper.absolutePos(builtPos)),
                            "the port still waiting");
                    helper.assertTrue(ghosts.ghosts(player.getUUID()).isEmpty(), "the finished ghost");
                }).thenSucceed();
    }

    /** A port part on the north face with settings (export, cobblestone). */
    private static SetupBlueprint.Part exportPart(GameTestHelper helper, Player player) {
        NetworkGameTests.cables(helper, 4, 4, CableContent.STORAGE_CABLE_TIER_1.type().get());
        PortPart template = portPart(helper, player, 4);
        template.setMode(PortMode.EXPORT);
        template.filter().setItem(0, new ItemStack(Items.COBBLESTONE));
        return new SetupBlueprint.Part(Direction.NORTH, PortContent.PORT_PART.id(),
                Optional.of(Blueprints.copy(template)));
    }

    /**
     * Three cables in a row with a port part on the north face of the last, pasted turned 90 degrees clockwise: the
     * cables land along z, the part goes on the east face. A part put on the unturned north face does not take the
     * settings and leaves the ghost; one on the east face does, and the finished ghost goes away.
     */
    private static void rotated(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        BlockState cable = helper.getLevel().getBlockState(helper.absolutePos(NetworkGameTests.at(2)));
        SetupBlueprint.Part part = exportPart(helper, player);
        SetupBlueprint blueprint = new SetupBlueprint(new Vec3i(3, 1, 1),
                List.of(new SetupBlueprint.Entry(new BlockPos(0, 0, 0), cable, Optional.empty(), List.of()),
                        new SetupBlueprint.Entry(new BlockPos(1, 0, 0), cable, Optional.empty(), List.of()),
                        new SetupBlueprint.Entry(new BlockPos(2, 0, 0), cable, Optional.empty(), List.of(part))));
        SetupGhosts ghosts = SetupGhosts.get(helper.getLevel().getServer());
        SetupGhosts.Ghost ghost = ghosts.paste(player, helper.getLevel(), helper.absolutePos(new BlockPos(8, 1, 1)),
                Rotation.CLOCKWISE_90, blueprint);
        BlockPos[] cables = new BlockPos[3];
        for (int i = 0; i < 3; i++) {
            cables[i] = ghost.posOf(blueprint.entries().get(i));
            ConduitBlock.placeConduit(helper.getLevel(), cables[i], CableContent.STORAGE_CABLE_TIER_1.type().get(),
                    null);
        }
        helper.assertValueEqual(cables[2].subtract(cables[0]), new Vec3i(0, 0, 2), "the row runs along z");
        CableParts parts = StorageCableBlock.parts(helper.getLevel(), cables[2]);
        if (parts == null) {
            throw helper.assertionException(Component.literal("The last cable has no parts"));
        }
        useOn(player, new ItemStack(PortContent.PORT_PART_ITEM.get()), cables[2], Direction.NORTH);
        PortPart onNorth = (PortPart) parts.part(Direction.NORTH);
        helper.assertTrue(onNorth != null && onNorth.mode() == PortMode.IMPORT && onNorth.filter().isBlank(),
                "the unturned face took settings");
        helper.assertValueEqual(ghosts.ghosts(player.getUUID()).size(), 1, "the ghost after the wrong face");
        useOn(player, new ItemStack(PortContent.PORT_PART_ITEM.get()), cables[2], Direction.EAST);
        if (!(parts.part(Direction.EAST) instanceof PortPart onEast)) {
            throw helper.assertionException(Component.literal("No part on the turned face"));
        }
        helper.assertTrue(ghosts.ghosts(player.getUUID()).isEmpty(), "the finished ghost");
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        onEast.mode() == PortMode.EXPORT && onEast.filter().getItem(0).is(Items.COBBLESTONE),
                        "the turned face took no settings"))
                .thenSucceed();
    }

    /**
     * A port block placed into a ghost before it has a network keeps its own settings for now and takes the ghost's
     * once it has joined one, within {@link SetupGhosts#PENDING_TICKS}. (A lone device joins its own network after
     * about 20 ticks, so the time-out itself is not reachable here.)
     */
    private static void pending(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        PortBlockEntity template = port(helper, new BlockPos(6, 1, 8));
        template.setMode(PortMode.EXPORT);
        template.filter().setItem(0, new ItemStack(Items.COBBLESTONE));
        SetupBlueprint blueprint = new SetupBlueprint(new Vec3i(1, 1, 1),
                List.of(new SetupBlueprint.Entry(BlockPos.ZERO, template.getBlockState(),
                        Optional.of(Blueprints.copy(template)), List.of())));
        BlockPos spot = new BlockPos(12, 1, 12);
        SetupGhosts ghosts = SetupGhosts.get(helper.getLevel().getServer());
        ghosts.paste(player, helper.getLevel(), helper.absolutePos(spot), Rotation.NONE, blueprint);
        PortBlockEntity built = port(helper, spot);
        helper.assertValueEqual(ghosts.blockPlaced(player, helper.getLevel(), helper.absolutePos(spot)),
                SetupGhosts.Placed.PENDING, "a lone port waits");
        helper.assertTrue(SetupGhosts.isPending(helper.getLevel().dimension(), helper.absolutePos(spot)),
                "the port not waiting");
        long placedAt = helper.getLevel().getGameTime();
        helper.assertValueEqual(built.mode(), PortMode.IMPORT, "applied before joining");
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(built.mode(), PortMode.EXPORT, "applied later"))
                .thenExecute(() -> {
                    helper.assertTrue(helper.getLevel().getGameTime() - placedAt <= SetupGhosts.PENDING_TICKS + 2,
                            "applied after the timeout window");
                    helper.assertTrue(built.filter().getItem(0).is(Items.COBBLESTONE), "the filter");
                }).thenSucceed();
    }

    /**
     * Ghosts of two players survive the saved form; a store drops ghosts older than the keep time, then the oldest past
     * the total cap.
     */
    private static void saved(GameTestHelper helper) {
        Player one = helper.makeMockPlayer(GameType.SURVIVAL);
        Player two = helper.makeMockPlayer(GameType.SURVIVAL);
        PortBlockEntity template = port(helper, new BlockPos(6, 1, 8));
        template.setMode(PortMode.EXPORT);
        SetupBlueprint blueprint = new SetupBlueprint(new Vec3i(1, 1, 1),
                List.of(new SetupBlueprint.Entry(BlockPos.ZERO, template.getBlockState(),
                        Optional.of(Blueprints.copy(template)), List.of())));
        SetupGhosts store = new SetupGhosts();
        store.paste(one, helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)), Rotation.NONE, blueprint);
        store.paste(one, helper.getLevel(), helper.absolutePos(new BlockPos(3, 1, 1)), Rotation.CLOCKWISE_180,
                blueprint);
        store.paste(two, helper.getLevel(), helper.absolutePos(new BlockPos(5, 1, 1)), Rotation.COUNTERCLOCKWISE_90,
                blueprint);
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Tag tag = SetupGhosts.CODEC.encodeStart(ops, store).getOrThrow();
        SetupGhosts loaded = SetupGhosts.CODEC.parse(ops, tag).getOrThrow();
        helper.assertValueEqual(loaded.ghosts(one.getUUID()), store.ghosts(one.getUUID()), "the first player's ghosts");
        helper.assertValueEqual(loaded.ghosts(two.getUUID()), store.ghosts(two.getUUID()),
                "the second player's ghosts");
        helper.assertValueEqual(loaded.ghosts(one.getUUID()).size(), 2, "ghost count after loading");

        long now = helper.getLevel().getServer().overworld().getGameTime();
        helper.assertValueEqual(store.prune(now + 500, 0, 100), 0, "a keep time of 0 keeps ghosts");
        helper.assertValueEqual(loaded.prune(now, 0, 2), 1, "dropped over the total cap");
        helper.assertValueEqual(loaded.ghosts(one.getUUID()).size() + loaded.ghosts(two.getUUID()).size(), 2,
                "ghosts left after the cap");
        helper.assertValueEqual(loaded.prune(now + 500, 100, 100), 2, "dropped for age");
        helper.assertTrue(loaded.ghosts(one.getUUID()).isEmpty() && loaded.ghosts(two.getUUID()).isEmpty(),
                "old ghosts kept");
        helper.succeed();
    }

    /** A player joining through the player list with no mod channels must not make login handlers send packets. */
    @SuppressWarnings("removal") // makeMockServerPlayerInLevel is the way to put a player on the player list
    private static void login(GameTestHelper helper) {
        // Joins through PlayerList.placeNewPlayer, which fires the login event with a connection that has no channels.
        helper.makeMockServerPlayerInLevel();
        helper.succeed();
    }

    /** A blueprint with more settings than {@link SetupBlueprint#MAX_WEIGHT} neither saves nor decodes. */
    private static void caps(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        SetupBlueprint.Part part = exportPart(helper, player);
        BlockState state = PortContent.PORT.get().defaultBlockState();
        List<SetupBlueprint.Part> parts = new ArrayList<>();
        for (Direction face : Direction.values()) {
            parts.add(new SetupBlueprint.Part(face, part.type(), part.settings()));
        }
        List<SetupBlueprint.Entry> entries = new ArrayList<>();
        for (int i = 0; i < SetupBlueprint.MAX_ENTRIES; i++) {
            entries.add(new SetupBlueprint.Entry(new BlockPos(i % 16, 0, i / 16), state, Optional.empty(), parts));
        }
        SetupBlueprint big = new SetupBlueprint(new Vec3i(16, 1, 16), entries);
        helper.assertTrue(big.weight() > SetupBlueprint.MAX_WEIGHT, "test blueprint is not heavy enough");
        helper.assertTrue(!big.valid(), "a heavy blueprint is valid");
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        helper.assertTrue(SetupBlueprint.CODEC.encodeStart(ops, big).isError(), "a heavy blueprint saved");
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess());
        SetupBlueprint.STREAM_CODEC.encode(buf, big);
        boolean refused = false;
        try {
            SetupBlueprint.STREAM_CODEC.decode(buf);
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        helper.assertTrue(refused, "a heavy blueprint decoded");
        helper.succeed();
    }

    private static void useOn(Player player, ItemStack item, BlockPos abs, Direction face) {
        player.setItemInHand(InteractionHand.MAIN_HAND, item);
        Vec3 location = abs.getCenter().relative(face, 1.0 / 16.0);
        item.useOn(new UseOnContext(player.level(), player, InteractionHand.MAIN_HAND, item,
                new BlockHitResult(location, face, abs, false)));
    }

    private static PortBlockEntity port(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.SOUTH)
                .setValue(PortBlock.MODE, PortMode.IMPORT));
        return helper.getBlockEntity(pos, PortBlockEntity.class);
    }

    private static PortPart portPart(GameTestHelper helper, Player player, int x) {
        PartGameTests.usePart(helper, player, x, Direction.NORTH, new ItemStack(PortContent.PORT_PART_ITEM.get()));
        CableParts parts = PartGameTests.parts(helper, x);
        if (PartGameTests.part(helper, parts, Direction.NORTH) instanceof PortPart port) {
            return port;
        }
        throw helper.assertionException(Component.literal("Not a port part at x " + x));
    }

    /** Uses {@code item} on the block at {@code pos} (relative) on {@code face}, as a player would. */
    private static void use(GameTestHelper helper, Player player, ItemStack item, BlockPos pos, Direction face,
            boolean sneaking) {
        BlockPos abs = helper.absolutePos(pos);
        player.setItemInHand(InteractionHand.MAIN_HAND, item);
        player.setShiftKeyDown(sneaking);
        item.onItemUseFirst(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(abs).relative(face, 0.5), face, abs, false)));
    }
}
