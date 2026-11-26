package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.save.SaveFormat;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.module.AnchorChunks;
import dev.silencebound.maxtech.storage.controller.module.DimensionBridge;
import dev.silencebound.maxtech.storage.controller.module.ExpansionBlock;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.ModuleKind;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.controller.module.ServiceDeviceBlockEntity;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkService;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import dev.silencebound.maxtech.storage.signal.AntennaBlockEntity;
import dev.silencebound.maxtech.storage.signal.AntennaMountBlockEntity;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import dev.silencebound.maxtech.storage.signal.WirelessCoverage;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The network devices that were controller modules (M7R item 4): the security module, network anchor, antenna mount,
 * wireless access mount and dimension bridge work from anywhere on the network, each holding one channel, and modules
 * of an old world load as these devices in place, with their settings kept once.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
// Networks are compared by identity.
@SuppressWarnings("ReferenceEquality")
public final class ServiceDeviceGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String SECURITY = "service_security_anywhere";
    private static final String WIRELESS = "service_wireless_from_mount";
    private static final String ANCHOR = "service_anchor_anywhere";
    private static final String BRIDGE = "service_bridge_anywhere";
    private static final String CHANNELS = "service_devices_take_channels";
    private static final String MIGRATE = "service_old_modules_migrate";
    private static final String UNLOADED = "service_old_modules_controller_unloaded";
    private static final String SPLIT = "service_security_split";
    private static final Identifier OLD_TYPE = MaxtechStorage.id("controller_expansion");
    /** The core of the tests along the long row: a controller with a cable to x 40. */
    private static final BlockPos CORE = new BlockPos(1, 1, 13);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(SECURITY, ServiceDeviceGameTests::securityAnywhere);
        register(WIRELESS, ServiceDeviceGameTests::wirelessFromMount);
        register(ANCHOR, ServiceDeviceGameTests::anchorAnywhere);
        register(BRIDGE, ServiceDeviceGameTests::bridgeAnywhere);
        register(CHANNELS, ServiceDeviceGameTests::devicesTakeChannels);
        register(MIGRATE, ServiceDeviceGameTests::oldModulesMigrate);
        register(UNLOADED, ServiceDeviceGameTests::controllerUnloaded);
        register(SPLIT, ServiceDeviceGameTests::securitySplit);
    }

    private ServiceDeviceGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("service_devices"),
                StorageTestEnvironment.plain());
        for (String name : List.of(SECURITY, WIRELESS, ANCHOR, BRIDGE, CHANNELS, MIGRATE, UNLOADED, SPLIT)) {
            StorageTests.test(event, plain, name, STRUCTURE, 400);
        }
    }

    /**
     * A security module 29 cables from the controller turns the controller's rules on for the whole network: a stranger
     * the rules let view sees the network only while it is there. A second module counts too, and the rules stay on
     * until the last one breaks (several modules all turn on the same rules, which stay with the controller).
     */
    private static void securityAnywhere(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = NetworkGameTests.player(level, "svc_owner");
        ServerPlayer stranger = NetworkGameTests.player(level, "svc_stranger");
        row(helper, 30);
        NetworkGameTests.controllerEntity(helper, CORE).setOwner(OwnerRecord.of(owner));
        NetworkGameTests.controllerEntity(helper, CORE)
                .setSecurity(SecuritySettings.DEFAULT.withEveryone(AccessLevel.VIEW));
        TestStorage.TestDevice device = NetworkGameTests.device(helper, new BlockPos(31, 1, 13));
        BlockPos first = new BlockPos(30, 2, 13);
        BlockPos second = new BlockPos(15, 2, 13);
        int[] held = new int[1];
        helper.startSequence().thenWaitUntil(
                () -> helper.assertTrue(device.network() == NetworkGameTests.state(helper, CORE), "network formed"))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, CORE);
                    helper.assertValueEqual(network.accessLevel(stranger), AccessLevel.NONE, "no module: owner rules");
                    held[0] = network.channelsHeld();
                    helper.setBlock(first, ExpansionContent.SECURITY_MODULE.get());
                }).thenWaitUntil(() -> helper.assertTrue(NetworkGameTests.controllerEntity(helper, CORE).secured(),
                        "far module on the network"))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, CORE);
                    helper.assertValueEqual(network.accessLevel(stranger), AccessLevel.VIEW, "the rules apply");
                    helper.assertValueEqual(network.accessLevel(owner), AccessLevel.FULL, "owner");
                    helper.assertValueEqual(network.channelsHeld(), held[0], "the module holds no channel");
                    helper.assertTrue(device(helper, first).active(), "module active");
                    helper.assertValueEqual(helper.getBlockState(first).getValue(ExpansionBlock.LOAD), 1, "lit");
                    helper.setBlock(second, ExpansionContent.SECURITY_MODULE.get());
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.state(helper, CORE).serviceCount(NetworkService.SECURITY), 2, "two modules"))
                .thenExecute(() -> helper.destroyBlock(first))
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.state(helper, CORE).serviceCount(NetworkService.SECURITY), 1, "one left"))
                .thenExecute(() -> {
                    helper.assertValueEqual(NetworkGameTests.state(helper, CORE).accessLevel(stranger),
                            AccessLevel.VIEW, "still secured by the second");
                    helper.destroyBlock(second);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(NetworkGameTests.state(helper, CORE).accessLevel(stranger),
                        AccessLevel.NONE, "no module left: owner rules again"))
                .thenExecute(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, CORE).security().everyone(), AccessLevel.VIEW,
                        "the rules stay with the controller"))
                .thenSucceed();
    }

    /**
     * A wireless access mount at the end of a 39-cable run gives coverage around itself, not around the controller: a
     * terminal 60 blocks south of the mount (71 from the controller) reaches the network, one 30 blocks west of the
     * controller (69 from the mount) does not.
     */
    private static void wirelessFromMount(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        row(helper, 40);
        BlockPos mount = new BlockPos(40, 2, 13);
        helper.setBlock(mount, ExpansionContent.WIRELESS_ACCESS_MOUNT.get());
        ServerPlayer player = researched(helper, "svc_wireless");
        ItemStack terminal = new ItemStack(SignalContent.WIRELESS_TERMINAL.get());
        terminal.set(ModDataComponents.WIRELESS_LINK.get(), GlobalPos.of(level.dimension(), helper.absolutePos(CORE)));
        terminal.set(ModDataComponents.WIRELESS_ENERGY.get(), 100_000);
        player.setItemInHand(InteractionHand.MAIN_HAND, terminal);
        Vec3 atMount = helper.absolutePos(mount).getCenter();
        Vec3 atCore = helper.absolutePos(CORE).getCenter();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, CORE).services(NetworkService.WIRELESS_ACCESS), 1,
                        "mount on the network"))
                .thenExecute(() -> {
                    helper.assertValueEqual(WirelessCoverage.range(NetworkGameTests.controllerEntity(helper, CORE)), 64,
                            "one mount's range");
                    player.setPos(atMount.add(0, 0, 60));
                    helper.assertValueEqual(WirelessTerminalItem.reach(player, terminal), WirelessTerminalItem.Reach.OK,
                            "near the mount, far from the controller");
                    player.setPos(atCore.add(-30, 0, 0));
                    helper.assertValueEqual(WirelessTerminalItem.reach(player, terminal),
                            WirelessTerminalItem.Reach.OUT_OF_RANGE, "near the controller, far from the mount");
                    helper.destroyBlock(mount);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, CORE).services(NetworkService.WIRELESS_ACCESS), 0,
                        "mount gone"))
                .thenExecute(() -> {
                    player.setPos(atMount);
                    helper.assertValueEqual(WirelessTerminalItem.reach(player, terminal),
                            WirelessTerminalItem.Reach.OUT_OF_RANGE, "no mount, no range");
                }).thenSucceed();
    }

    /**
     * A network anchor at the end of a 38-cable run, with no drive: the controller keeps every chunk from itself to the
     * anchor loaded, and lets them go when the anchor breaks.
     */
    private static void anchorAnywhere(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        row(helper, 39);
        BlockPos anchor = new BlockPos(39, 2, 13);
        helper.setBlock(anchor, ExpansionContent.NETWORK_ANCHOR.get());
        BlockPos absoluteCore = helper.absolutePos(CORE);
        LongSet route = new LongOpenHashSet();
        for (int x = 1; x <= 39; x++) {
            route.add(ChunkPos.pack(helper.absolutePos(new BlockPos(x, 1, 13))));
        }
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(NetworkGameTests.controllerEntity(helper, CORE).anchored(),
                        "anchor on the network"))
                .thenWaitUntil(
                        () -> helper.assertValueEqual(new LongOpenHashSet(AnchorChunks.held(level, absoluteCore)),
                                new LongOpenHashSet(route), "chunks to the anchor held"))
                .thenExecute(() -> {
                    helper.assertTrue(route.size() >= 3, "the route crosses chunks: " + route.size());
                    helper.destroyBlock(anchor);
                }).thenWaitUntil(() -> helper.assertValueEqual(AnchorChunks.held(level, absoluteCore).size(), 0,
                        "nothing held without the anchor"))
                .thenSucceed();
    }

    /**
     * A dimension bridge 29 cables away bridges the network while a controller runs it; with the controller broken it
     * goes idle (dark) and the network is not bridged.
     */
    private static void bridgeAnywhere(GameTestHelper helper) {
        row(helper, 30);
        BlockPos bridge = new BlockPos(30, 2, 13);
        helper.setBlock(bridge, ExpansionContent.DIMENSION_BRIDGE.get());
        helper.startSequence().thenWaitUntil(() -> helper
                .assertTrue(DimensionBridge.bridged(NetworkGameTests.state(helper, CORE)), "bridged from far away"))
                .thenExecute(() -> {
                    helper.assertTrue(device(helper, bridge).active(), "bridge active");
                    helper.destroyBlock(CORE);
                }).thenWaitUntil(() -> {
                    helper.assertFalse(device(helper, bridge).active(), "bridge still active");
                    helper.assertValueEqual(helper.getBlockState(bridge).getValue(ExpansionBlock.LOAD), 0, "dark");
                    helper.assertFalse(DimensionBridge.bridged(device(helper, bridge).network()), "still bridged");
                }).thenSucceed();
    }

    /**
     * One device of each kind in a chain beside a cable, only the first touching it (M7N: touching devices link with
     * their own channels): all five join; the antenna mount, wireless access mount and dimension bridge hold one
     * channel each, the security module and network anchor none (owner 2026-10-17).
     */
    private static void devicesTakeChannels(GameTestHelper helper) {
        row(helper, 10);
        int[] held = new int[1];
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, CORE)).thenExecute(() -> {
            held[0] = NetworkGameTests.state(helper, CORE).channelsHeld();
            for (int i = 0; i < ExpansionContent.DEVICES.size(); i++) {
                helper.setBlock(new BlockPos(10, 1, 14 + i), ExpansionContent.DEVICES.get(i).get());
            }
        }).thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, CORE);
            for (NetworkService service : NetworkService.values()) {
                helper.assertValueEqual(network.serviceCount(service), 1, service.name());
            }
            helper.assertValueEqual(network.channelsHeld(), held[0] + 3, "one channel each but security and anchor");
            helper.assertTrue(network.offlineDevices().isEmpty(), "none offline");
            for (int i = 0; i < ExpansionContent.DEVICES.size(); i++) {
                ServiceDeviceBlockEntity device = device(helper, new BlockPos(10, 1, 14 + i));
                NetworkService service = device.service();
                helper.assertValueEqual(device.deviceLink().holdsChannel(),
                        service != NetworkService.SECURITY && service != NetworkService.ANCHOR, service.name());
            }
        }).thenSucceed();
    }

    /**
     * A controller of an old world (data version 4) with a security module, network anchor, wireless access mount,
     * dimension bridge and antenna mount attached, each module saved under the expansion block entity type, and an
     * antenna that saved its pairing with the mount: the modules load in place as network devices (their owner kept,
     * their old core gone), the controller's fill drops them from its module list, its FE and security rules stay and
     * apply because the module is on the network, and the mount takes its pairing from the antenna once, so the
     * antenna's site joins.
     */
    private static void oldModulesMigrate(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = NetworkGameTests.player(level, "svc_old_owner");
        ServerPlayer stranger = NetworkGameTests.player(level, "svc_old_stranger");
        BlockPos core = new BlockPos(5, 1, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        List<BlockPos> modules = List.of(core.above(), core.west(), core.north(), core.south(), core.east());
        List<ModuleKind> kinds = List.of(ModuleKind.SECURITY_MODULE, ModuleKind.NETWORK_ANCHOR,
                ModuleKind.WIRELESS_ACCESS_MOUNT, ModuleKind.DIMENSION_BRIDGE, ModuleKind.ANTENNA_MOUNT);
        helper.setBlock(modules.get(0), ExpansionContent.SECURITY_MODULE.get());
        helper.setBlock(modules.get(1), ExpansionContent.NETWORK_ANCHOR.get());
        helper.setBlock(modules.get(2), ExpansionContent.WIRELESS_ACCESS_MOUNT.get());
        helper.setBlock(modules.get(3), ExpansionContent.DIMENSION_BRIDGE.get());
        BlockPos mount = modules.get(4);
        helper.setBlock(mount, ExpansionContent.ANTENNA_MOUNT.get());
        BlockPos antenna = new BlockPos(30, 1, 5);
        helper.setBlock(antenna, SignalContent.ANTENNA_TIER_1.get());
        TestStorage.TestDevice remote = NetworkGameTests.device(helper, antenna.east());
        long corePos = helper.absolutePos(core).asLong();
        long mountPos = helper.absolutePos(mount).asLong();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, core).services(NetworkService.ANTENNA_MOUNT), 1,
                        "devices placed"))
                .thenExecute(() -> {
                    // Each module as an old world saved it: the expansion type, version 4, its core, an owner.
                    for (BlockPos pos : modules) {
                        CompoundTag tag = unload(helper, pos);
                        tag.putString("id", OLD_TYPE.toString());
                        tag.putInt(SaveFormat.VERSION_KEY, 4);
                        tag.putLong("core", corePos);
                        tag.remove("antenna");
                        tag.store("owner", OwnerRecord.CODEC, OwnerRecord.of(owner));
                        load(helper, pos, tag);
                    }
                    // The antenna saved its pairing with the mount; the old mount saved none.
                    CompoundTag paired = unload(helper, antenna);
                    paired.putLong("partner", mountPos);
                    paired.putBoolean("partner_mount", true);
                    paired.putInt("partner_tier", 1);
                    load(helper, antenna, paired);
                    // The controller as version 4 saved it, with the five modules attached.
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
                    CompoundTag old = controller.saveWithoutMetadata(level.registryAccess());
                    old.putInt(SaveFormat.VERSION_KEY, 4);
                    old.putLong("energy", 7_000L);
                    old.store("security", SecuritySettings.CODEC,
                            SecuritySettings.DEFAULT.withEveryone(AccessLevel.VIEW));
                    long[] members = new long[modules.size()];
                    int[] indices = new int[modules.size()];
                    for (int i = 0; i < members.length; i++) {
                        members[i] = helper.absolutePos(modules.get(i)).asLong();
                        indices[i] = kinds.get(i).index();
                    }
                    old.putLongArray("members", members);
                    old.putIntArray("kinds", indices);
                    controller.loadWithComponents(
                            TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), old));
                    CompoundTag saved = controller.saveWithoutMetadata(level.registryAccess());
                    helper.assertTrue(saved.getBooleanOr(ControllerBlockEntity.REDETECT, false), "asks for a fill");
                    helper.assertValueEqual(saved.getIntOr(SaveFormat.VERSION_KEY, 0),
                            StorageSaveFormats.BLOCK_ENTITIES.currentVersion(), "saved in the new version");
                    helper.assertValueEqual(controller.members().size(), 5, "listed until the fill");
                    controller.requestDetect();
                })
                .thenWaitUntil(
                        () -> helper.assertValueEqual(NetworkGameTests.controllerEntity(helper, core).members().size(),
                                0, "fill dropped the modules"))
                .thenWaitUntil(() -> {
                    helper.assertTrue(remote.network() == NetworkGameTests.state(helper, core), "antenna site joined");
                    helper.assertValueEqual(mountEntity(helper, mount).antenna(), helper.absolutePos(antenna).asLong(),
                            "the mount took its pairing");
                }).thenExecute(() -> {
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
                    NetworkState network = NetworkGameTests.state(helper, core);
                    helper.assertValueEqual(controller.buffer().stored(), 7_000L, "FE kept");
                    for (int i = 0; i < modules.size(); i++) {
                        BlockPos pos = modules.get(i);
                        ServiceDeviceBlockEntity device = device(helper, pos);
                        helper.assertTrue(device.getType() == ExpansionContent.SERVICE_DEVICE_BLOCK_ENTITY.get(),
                                "the new type at " + pos);
                        helper.assertTrue(device.ownerRecord().isOwner(owner.getUUID()), "owner kept at " + pos);
                        CompoundTag tag = device.saveWithFullMetadata(level.registryAccess());
                        helper.assertFalse(tag.contains("core"), "old core gone at " + pos);
                        helper.assertValueEqual(tag.getStringOr("id", ""), "maxtech_storage:service_device",
                                "saved under the new type at " + pos);
                        helper.assertValueEqual(network.serviceCount(device.service()), 1, kinds.get(i).name());
                    }
                    helper.assertValueEqual(network.accessLevel(stranger), AccessLevel.VIEW, "security rules kept");
                    helper.assertTrue(controller.anchored(), "anchored");
                    helper.assertTrue(DimensionBridge.bridged(network), "bridged");
                    AntennaBlockEntity far = helper.getBlockEntity(antenna, AntennaBlockEntity.class);
                    helper.assertValueEqual(far.partner(), mountPos, "the antenna keeps its pairing");
                    // Released before the area is cleared: the anchor holds tickets.
                    helper.destroyBlock(modules.get(1));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(AnchorChunks.held(level, helper.absolutePos(core)).size(),
                        0, "anchor let go"))
                .thenSucceed();
    }

    /**
     * An old world whose controller's chunk is not loaded yet while its modules load (M7R item 4): the security module
     * and wireless access mount, saved under the expansion block entity type with data version 4, load in place as idle
     * devices with their owner and without the old core; when the controller (data version 4, the two listed as
     * modules) loads, its fill drops them, its rules stay, and the devices rejoin under it and apply. Saved and loaded
     * again, neither the devices nor the controller migrate a second time.
     */
    private static void controllerUnloaded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = NetworkGameTests.player(level, "svc_unl_owner");
        ServerPlayer stranger = NetworkGameTests.player(level, "svc_unl_stranger");
        BlockPos core = new BlockPos(5, 1, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        List<BlockPos> modules = List.of(core.above(), core.west());
        helper.setBlock(modules.get(0), ExpansionContent.SECURITY_MODULE.get());
        helper.setBlock(modules.get(1), ExpansionContent.WIRELESS_ACCESS_MOUNT.get());
        long corePos = helper.absolutePos(core).asLong();
        CompoundTag[] controllerTag = new CompoundTag[1];
        CompoundTag[] deviceTags = new CompoundTag[2];
        BlockState[] controllerState = new BlockState[1];
        // Every step that changes the level runs once and asserts nothing: a sequence step that throws runs again.
        helper.startSequence().thenWaitUntil(
                () -> helper.assertTrue(NetworkGameTests.controllerEntity(helper, core).secured(), "devices placed"))
                .thenExecute(() -> {
                    // The controller as version 4 saved it, taken out of the level (its chunk not loaded yet): no
                    // block there either, so nothing makes a fresh controller in its place meanwhile.
                    controllerState[0] = helper.getBlockState(core);
                    CompoundTag old = unload(helper, core);
                    setQuietly(helper, core, Blocks.AIR.defaultBlockState());
                    old.putInt(SaveFormat.VERSION_KEY, 4);
                    old.putLong("energy", 5_000L);
                    old.store("security", SecuritySettings.CODEC,
                            SecuritySettings.DEFAULT.withEveryone(AccessLevel.VIEW));
                    old.putLongArray("members", new long[]{helper.absolutePos(modules.get(0)).asLong(),
                            helper.absolutePos(modules.get(1)).asLong()});
                    old.putIntArray("kinds",
                            new int[]{ModuleKind.SECURITY_MODULE.index(), ModuleKind.WIRELESS_ACCESS_MOUNT.index()});
                    controllerTag[0] = old;
                    for (BlockPos pos : modules) {
                        CompoundTag tag = unload(helper, pos);
                        tag.putString("id", OLD_TYPE.toString());
                        tag.putInt(SaveFormat.VERSION_KEY, 4);
                        tag.putLong("core", corePos);
                        tag.store("owner", OwnerRecord.CODEC, OwnerRecord.of(owner));
                        load(helper, pos, tag);
                    }
                }).thenWaitUntil(() -> {
                    for (BlockPos pos : modules) {
                        ServiceDeviceBlockEntity device = device(helper, pos);
                        helper.assertTrue(device.network() != null, "device joined at " + pos);
                        helper.assertFalse(device.active(), "idle without its controller at " + pos);
                        helper.assertTrue(device.ownerRecord().isOwner(owner.getUUID()), "owner kept at " + pos);
                        helper.assertFalse(device.saveWithFullMetadata(level.registryAccess()).contains("core"),
                                "old core gone at " + pos);
                    }
                }).thenExecute(() -> {
                    setQuietly(helper, core, controllerState[0]);
                    load(helper, core, controllerTag[0]);
                }).thenWaitUntil(() -> {
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
                    helper.assertValueEqual(controller.members().size(), 0, "fill dropped the modules");
                    helper.assertTrue(controller.secured() && controller.services(NetworkService.WIRELESS_ACCESS) == 1,
                            "devices rejoined");
                    helper.assertTrue(device(helper, modules.get(0)).active(), "security module active");
                }).thenExecute(() -> {
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
                    helper.assertValueEqual(controller.buffer().stored(), 5_000L, "FE kept");
                    helper.assertValueEqual(NetworkGameTests.state(helper, core).accessLevel(stranger),
                            AccessLevel.VIEW, "rules kept and applied");
                    CompoundTag saved = controller.saveWithoutMetadata(level.registryAccess());
                    helper.assertFalse(saved.getBooleanOr(ControllerBlockEntity.REDETECT, false), "no second fill");
                    helper.assertValueEqual(saved.getIntOr(SaveFormat.VERSION_KEY, 0),
                            StorageSaveFormats.BLOCK_ENTITIES.currentVersion(), "controller stamp");
                    for (int i = 0; i < modules.size(); i++) {
                        CompoundTag tag = device(helper, modules.get(i)).saveWithFullMetadata(level.registryAccess());
                        helper.assertValueEqual(tag.getStringOr("id", ""), "maxtech_storage:service_device",
                                "new type at " + modules.get(i));
                        helper.assertValueEqual(tag.getIntOr(SaveFormat.VERSION_KEY, 0),
                                StorageSaveFormats.BLOCK_ENTITIES.currentVersion(),
                                "device stamp at " + modules.get(i));
                        deviceTags[i] = tag;
                    }
                })
                // Saved and loaded again: current data, nothing migrates a second time.
                .thenExecute(() -> {
                    for (int i = 0; i < modules.size(); i++) {
                        unload(helper, modules.get(i));
                        load(helper, modules.get(i), deviceTags[i].copy());
                    }
                }).thenExecute(() -> {
                    for (int i = 0; i < modules.size(); i++) {
                        helper.assertValueEqual(
                                device(helper, modules.get(i)).saveWithFullMetadata(level.registryAccess()),
                                deviceTags[i], "loaded again unchanged at " + modules.get(i));
                    }
                }).thenWaitUntil(() -> helper.assertTrue(NetworkGameTests.controllerEntity(helper, core).secured(),
                        "rejoined after the second load"))
                .thenSucceed();
    }

    /**
     * Two controllers joined by a cable (B on standby), each with its own rules (A: everyone views, B: everyone
     * deposits) and a security module near each. Breaking B's module leaves the joined network secured by A's; cutting
     * the cable then splits it, and the rules apply only on A's part, where a module remains: B's part falls back to
     * owner security.
     */
    private static void securitySplit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = NetworkGameTests.player(level, "svc_split_owner");
        ServerPlayer stranger = NetworkGameTests.player(level, "svc_split_stranger");
        BlockPos coreA = new BlockPos(1, 1, 13);
        BlockPos coreB = new BlockPos(31, 1, 13);
        BlockPos cut = new BlockPos(16, 1, 13);
        BlockPos moduleA = new BlockPos(5, 2, 13);
        BlockPos moduleB = new BlockPos(27, 2, 13);
        row(helper, 30);
        NetworkGameTests.controllerEntity(helper, coreA).setOwner(OwnerRecord.of(owner));
        NetworkGameTests.controllerEntity(helper, coreA)
                .setSecurity(SecuritySettings.DEFAULT.withEveryone(AccessLevel.VIEW));
        TestStorage.TestDevice deviceA = NetworkGameTests.device(helper, new BlockPos(5, 1, 12));
        TestStorage.TestDevice deviceB = NetworkGameTests.device(helper, new BlockPos(27, 1, 12));
        helper.startSequence().thenIdle(3).thenExecute(() -> {
            helper.setBlock(coreB, ControllerContent.CONTROLLER.get());
            NetworkGameTests.controllerEntity(helper, coreB).setOwner(OwnerRecord.of(owner));
            NetworkGameTests.controllerEntity(helper, coreB)
                    .setSecurity(SecuritySettings.DEFAULT.withEveryone(AccessLevel.DEPOSIT));
            helper.setBlock(moduleA, ExpansionContent.SECURITY_MODULE.get());
            helper.setBlock(moduleB, ExpansionContent.SECURITY_MODULE.get());
        }).thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.net(helper, deviceA);
            helper.assertTrue(network == deviceB.network(), "one network");
            helper.assertTrue(network.controllerDevice() == NetworkGameTests.controllerEntity(helper, coreA),
                    "A in charge");
            helper.assertValueEqual(network.serviceCount(NetworkService.SECURITY), 2, "two modules");
        }).thenExecute(() -> {
            helper.assertValueEqual(NetworkGameTests.net(helper, deviceA).accessLevel(stranger), AccessLevel.VIEW,
                    "A's rules on the joined network");
            helper.destroyBlock(moduleB);
        }).thenWaitUntil(() -> helper.assertValueEqual(
                NetworkGameTests.net(helper, deviceA).serviceCount(NetworkService.SECURITY), 1, "one module left"))
                .thenExecute(() -> {
                    helper.assertValueEqual(NetworkGameTests.net(helper, deviceB).accessLevel(stranger),
                            AccessLevel.VIEW, "still secured by A's module");
                    helper.destroyBlock(cut);
                }).thenWaitUntil(() -> {
                    NetworkState partA = NetworkGameTests.net(helper, deviceA);
                    NetworkState partB = NetworkGameTests.net(helper, deviceB);
                    helper.assertTrue(partA != partB, "split");
                    helper.assertTrue(partB.controllerDevice() == NetworkGameTests.controllerEntity(helper, coreB),
                            "B runs its part");
                    helper.assertValueEqual(partA.serviceCount(NetworkService.SECURITY), 1,
                            "A's part keeps its module");
                    helper.assertValueEqual(partB.serviceCount(NetworkService.SECURITY), 0, "B's part has none");
                    helper.assertValueEqual(partA.accessLevel(stranger), AccessLevel.VIEW, "A's rules on A's part");
                    helper.assertValueEqual(partB.accessLevel(stranger), AccessLevel.NONE,
                            "no module on B's part: owner security");
                }).thenSucceed();
    }

    /** The controller at {@link #CORE} with a tier-1 cable from x 2 to {@code toX} on its row. */
    private static void row(GameTestHelper helper, int toX) {
        helper.setBlock(CORE, ControllerContent.CONTROLLER.get());
        for (int x = 2; x <= toX; x++) {
            ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(new BlockPos(x, 1, 13)),
                    CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        }
    }

    private static ServiceDeviceBlockEntity device(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockEntity(pos, ServiceDeviceBlockEntity.class);
    }

    private static AntennaMountBlockEntity mountEntity(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockEntity(pos, AntennaMountBlockEntity.class);
    }

    private static ServerPlayer researched(GameTestHelper helper, String name) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = NetworkGameTests.player(level, name);
        ResearchTeam team = Research.team(player, true);
        Research.addPoints(level.getServer(), team, 2000);
        for (String node : List.of("port_tier_2", "antennas", "wireless")) {
            helper.assertValueEqual(Research.spendPoints(level.getServer(), team, MaxtechStorage.id(node)),
                    Research.Result.DONE, node);
        }
        return player;
    }

    /** Sets the block at {@code pos} in its chunk without side effects, neighbour updates or {@code onPlace}. */
    private static void setQuietly(GameTestHelper helper, BlockPos pos, BlockState state) {
        BlockPos abs = helper.absolutePos(pos);
        helper.getLevel().getChunkAt(abs).setBlockState(abs, state,
                Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS | Block.UPDATE_SKIP_ON_PLACE);
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
    private static void load(GameTestHelper helper, BlockPos pos, CompoundTag tag) {
        BlockPos abs = helper.absolutePos(pos);
        BlockEntity loaded = BlockEntity.loadStatic(abs, helper.getLevel().getBlockState(abs), tag,
                helper.getLevel().registryAccess());
        if (loaded == null) {
            throw helper.assertionException(Component.literal("Nothing loaded at " + pos.toShortString()));
        }
        helper.getLevel().getChunkAt(abs).addAndRegisterBlockEntity(loaded);
    }
}
