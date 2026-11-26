package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.module.AlarmSettings;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorContent;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorProviderBlock;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorSettings;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperBlockEntity;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperContent;
import dev.silencebound.maxtech.storage.crafting.stock.StockRule;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkNames;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.snapshot.NetworkSnapshot;
import dev.silencebound.maxtech.storage.network.snapshot.SnapshotDevices;
import dev.silencebound.maxtech.storage.p2p.P2PBlock;
import dev.silencebound.maxtech.storage.p2p.P2PBlockEntity;
import dev.silencebound.maxtech.storage.p2p.P2PContent;
import dev.silencebound.maxtech.storage.p2p.P2PKind;
import dev.silencebound.maxtech.storage.p2p.P2PRole;
import dev.silencebound.maxtech.storage.p2p.P2PSettings;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import dev.silencebound.maxtech.storage.sensor.SensorBlock;
import dev.silencebound.maxtech.storage.sensor.SensorBlockEntity;
import dev.silencebound.maxtech.storage.sensor.SensorContent;
import dev.silencebound.maxtech.storage.sensor.SensorPoll;
import dev.silencebound.maxtech.storage.sensor.SensorSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Network snapshots of the device kinds added after M7C item 27's first round: sensors, stock keepers, P2P ends,
 * generator providers, the controller's alarm rules (M7R item 3; the alarm modules before) and the network name each
 * come back from a capture, a trip through the saved data's codec and a restore; a taken network name is kept and
 * reported; a snapshot saved before these were kept loads and leaves their settings alone. A controller at x 1, a cable
 * along x 2 to 5, and south of it a sensor at x 2, a stock keeper at x 3, an item P2P end at x 4 and a generator
 * provider at x 5.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class NetworkSnapshotKindsGameTests {
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final Codec<List<NetworkSnapshot>> SAVED = StorageSaveFormats.SAVED_DATA
            .codec(MaxtechStorage.id("network_snapshots"), NetworkSnapshot.CODEC.listOf());
    private static final List<String> NAMES = new ArrayList<>();
    private static final List<AlarmSettings> RULES = List.of(new AlarmSettings(AlarmSettings.Type.STORAGE_FULL, 75),
            new AlarmSettings(AlarmSettings.Type.LOW_POWER, 20));

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        for (Kind kind : Kind.values()) {
            register("network_snapshot_kind_" + kind.id, helper -> roundTrip(helper, kind));
        }
        register("network_snapshot_name_taken", NetworkSnapshotKindsGameTests::nameTaken);
        register("network_snapshot_old_format", NetworkSnapshotKindsGameTests::oldFormat);
        register("network_snapshot_switched_off", NetworkSnapshotKindsGameTests::switchedOff);
        register("network_snapshot_switched_old", NetworkSnapshotKindsGameTests::switchedOld);
    }

    private NetworkSnapshotKindsGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("network_snapshot_kinds"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 200);
        }
    }

    // ---- set-up

    private record Layout(ControllerBlockEntity core, SensorBlockEntity sensor, StockKeeperBlockEntity keeper,
            P2PBlockEntity p2p, GeneratorProviderBlockEntity generator) {
        List<NetworkDevice> devices() {
            return List.of(core, sensor, keeper, p2p, generator);
        }
    }

    private static Layout layout(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 10_000_000);
        SensorGameTests.ownNetwork(helper, core);
        NetworkGameTests.cables(helper, 2, 5, CableContent.STORAGE_CABLE_TIER_1.type().get());
        BlockPos sensor = NetworkGameTests.at(2).south();
        helper.setBlock(sensor,
                SensorContent.SENSOR.get().defaultBlockState().setValue(SensorBlock.FACING, Direction.SOUTH));
        BlockPos keeper = NetworkGameTests.at(3).south();
        helper.setBlock(keeper, StockKeeperContent.KEEPER.get());
        BlockPos p2p = NetworkGameTests.at(4).south();
        helper.setBlock(p2p,
                P2PContent.block(P2PKind.ITEM).get().defaultBlockState().setValue(P2PBlock.FACING, Direction.SOUTH));
        BlockPos generator = NetworkGameTests.at(5).south();
        helper.setBlock(generator, GeneratorContent.GENERATOR.get().defaultBlockState()
                .setValue(GeneratorProviderBlock.FACING, Direction.SOUTH));
        return new Layout(NetworkGameTests.controllerEntity(helper, core),
                helper.getBlockEntity(sensor, SensorBlockEntity.class),
                helper.getBlockEntity(keeper, StockKeeperBlockEntity.class),
                helper.getBlockEntity(p2p, P2PBlockEntity.class),
                helper.getBlockEntity(generator, GeneratorProviderBlockEntity.class));
    }

    /** The network every device of {@code layout} is in; fails (to be retried) until they all joined it. */
    private static NetworkState joined(GameTestHelper helper, Layout layout) {
        NetworkState network = layout.core().network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the controller is in no network"));
        }
        for (NetworkDevice device : layout.devices()) {
            helper.assertTrue(device.deviceLink().network() == network, "devices joined");
        }
        return network;
    }

    /** A snapshot of {@code network} as it comes back from the saved data's codec. */
    private static NetworkSnapshot captured(GameTestHelper helper, Layout layout, NetworkState network, String name) {
        NetworkSnapshot snapshot = new NetworkSnapshot(name, Level.OVERWORLD, layout.core().getBlockPos(), 1, "test",
                SnapshotDevices.capture(network));
        DynamicOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Tag tag = SAVED.encodeStart(ops, List.of(snapshot)).getOrThrow();
        List<NetworkSnapshot> back = SAVED.parse(ops, tag).getOrThrow();
        helper.assertValueEqual(back.size(), 1, "read back");
        return back.getFirst();
    }

    private static String unique(GameTestHelper helper, String prefix) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return prefix + " " + Integer.toHexString(origin.hashCode());
    }

    // ---- kinds

    /** One newly covered kind: what an owner sets, what a griefer changes, and the check that it is back. */
    private enum Kind {
        SENSOR("sensor") {
            @Override
            void configure(GameTestHelper helper, Layout layout) {
                layout.sensor().sensor().setSettings(SensorSettings.DEFAULT.withPoll(SensorPoll.FIVE_SECONDS));
            }

            @Override
            void grief(GameTestHelper helper, Layout layout) {
                layout.sensor().sensor().setSettings(SensorSettings.DEFAULT.withPoll(SensorPoll.THIRTY_SECONDS));
            }

            @Override
            void check(GameTestHelper helper, Layout layout) {
                helper.assertValueEqual(layout.sensor().sensor().settings(),
                        SensorSettings.DEFAULT.withPoll(SensorPoll.FIVE_SECONDS), "sensor settings back");
            }
        },
        STOCK("stock") {
            @Override
            void configure(GameTestHelper helper, Layout layout) {
                layout.keeper().worker().setRule(0, StockRule.EMPTY.withKey(Optional.of(COBBLE)).withKeep(64));
            }

            @Override
            void grief(GameTestHelper helper, Layout layout) {
                layout.keeper().worker().setRules(List.of());
            }

            @Override
            void check(GameTestHelper helper, Layout layout) {
                helper.assertValueEqual(layout.keeper().worker().usedRules(),
                        List.of(StockRule.EMPTY.withKey(Optional.of(COBBLE)).withKeep(64)), "stock rules back");
            }
        },
        P2P("p2p") {
            @Override
            void configure(GameTestHelper helper, Layout layout) {
                layout.p2p().endpoint().link(link(helper));
            }

            @Override
            void grief(GameTestHelper helper, Layout layout) {
                layout.p2p().endpoint().link(P2PSettings.NONE);
            }

            @Override
            void check(GameTestHelper helper, Layout layout) {
                helper.assertValueEqual(layout.p2p().endpoint().settings(), link(helper), "P2P link back");
            }

            private P2PSettings link(GameTestHelper helper) {
                return new P2PSettings(P2PRole.INPUT, 7_000_000L + helper.absolutePos(BlockPos.ZERO).asLong());
            }
        },
        GENERATOR("generator") {
            @Override
            void configure(GameTestHelper helper, Layout layout) {
                layout.generator().logic().setSettings(SETTINGS);
            }

            @Override
            void grief(GameTestHelper helper, Layout layout) {
                layout.generator().logic().setSettings(GeneratorSettings.DEFAULT);
            }

            @Override
            void check(GameTestHelper helper, Layout layout) {
                helper.assertValueEqual(layout.generator().logic().settings(), SETTINGS, "generator settings back");
            }
        },
        ALARM("alarm") {
            @Override
            void configure(GameTestHelper helper, Layout layout) {
                layout.core().recorders().setRules(RULES);
            }

            @Override
            void grief(GameTestHelper helper, Layout layout) {
                layout.core().recorders().setRules(List.of(AlarmSettings.DEFAULT));
            }

            @Override
            void check(GameTestHelper helper, Layout layout) {
                helper.assertValueEqual(layout.core().recorders().ruleSettings(), RULES, "alarm rules back");
            }
        },
        NAME("name") {
            @Override
            void configure(GameTestHelper helper, Layout layout) {
                helper.assertValueEqual(layout.core().rename(unique(helper, "Main")), NetworkNames.Renamed.DONE,
                        "named");
            }

            @Override
            void grief(GameTestHelper helper, Layout layout) {
                helper.assertValueEqual(layout.core().rename(unique(helper, "Griefed")), NetworkNames.Renamed.DONE,
                        "renamed");
            }

            @Override
            void check(GameTestHelper helper, Layout layout) {
                helper.assertValueEqual(layout.core().networkName(), unique(helper, "Main"), "network name back");
            }
        };

        private static final GeneratorSettings SETTINGS = GeneratorSettings.DEFAULT.withOutput(Optional.of(COBBLE))
                .withStrength(7);

        final String id;

        Kind(String id) {
            this.id = id;
        }

        abstract void configure(GameTestHelper helper, Layout layout);

        abstract void grief(GameTestHelper helper, Layout layout);

        abstract void check(GameTestHelper helper, Layout layout);
    }

    // ---- tests

    /** {@code kind}'s settings are captured, survive the codec, and a restore puts them back after a change. */
    private static void roundTrip(GameTestHelper helper, Kind kind) {
        Layout layout = layout(helper);
        helper.startSequence().thenWaitUntil(() -> joined(helper, layout)).thenExecute(() -> {
            kind.configure(helper, layout);
            NetworkSnapshot snapshot = captured(helper, layout, joined(helper, layout), "kind_" + kind.id);
            helper.assertValueEqual(snapshot.format(), NetworkSnapshot.FORMAT_CURRENT, "current format");
            kind.grief(helper, layout);
            SnapshotDevices.Result result = SnapshotDevices.restore(helper.getLevel(), snapshot);
            helper.assertValueEqual(result.skipped().size(), 0, "nothing skipped");
            helper.assertTrue(result.restored() >= layout.devices().size(), "every device restored");
            helper.assertValueEqual(result.nameTaken(), Optional.<String>empty(), "no name taken");
            kind.check(helper, layout);
        }).thenSucceed();
    }

    /**
     * The saved network name is held by another network at restore: the controller keeps the name it has, the result
     * names the taken one, and the other settings still come back.
     */
    private static void nameTaken(GameTestHelper helper) {
        Layout layout = layout(helper);
        BlockPos otherPos = NetworkGameTests.controller(helper, 10);
        helper.startSequence().thenWaitUntil(() -> joined(helper, layout)).thenExecute(() -> {
            String saved = unique(helper, "Taken");
            String griefed = unique(helper, "Other");
            helper.assertValueEqual(layout.core().rename(saved), NetworkNames.Renamed.DONE, "named");
            Kind.SENSOR.configure(helper, layout);
            NetworkSnapshot snapshot = captured(helper, layout, joined(helper, layout), "taken");
            layout.core().rename(griefed);
            Kind.SENSOR.grief(helper, layout);
            ControllerBlockEntity other = NetworkGameTests.controllerEntity(helper, otherPos);
            helper.assertValueEqual(other.rename(saved), NetworkNames.Renamed.DONE, "the other network took it");
            SnapshotDevices.Result result = SnapshotDevices.restore(helper.getLevel(), snapshot);
            helper.assertValueEqual(result.nameTaken(), Optional.of(saved), "taken name reported");
            helper.assertValueEqual(layout.core().networkName(), griefed, "kept its current name");
            helper.assertValueEqual(other.networkName(), saved, "the other network keeps its name");
            Kind.SENSOR.check(helper, layout);
        }).thenSucceed();
    }

    /**
     * A snapshot as the first round saved it (no format field, no settings for the new kinds) loads as
     * {@link NetworkSnapshot#FORMAT_FIRST}; its restore puts labels back and leaves the new kinds' settings and the
     * network name as they are now instead of resetting them.
     */
    private static void oldFormat(GameTestHelper helper) {
        Layout layout = layout(helper);
        helper.startSequence().thenWaitUntil(() -> joined(helper, layout)).thenExecute(() -> {
            layout.sensor().setLabel(DeviceLabel.of("probe", DyeColor.BLUE));
            List<NetworkSnapshot.Device> old = new ArrayList<>();
            for (NetworkDevice device : layout.devices()) {
                NetworkSnapshot.Device entry = SnapshotDevices.capture(device);
                if (entry == null) {
                    throw helper.assertionException(Component.literal("not captured: " + device));
                }
                DataComponentPatch label = device.label().isEmpty()
                        ? DataComponentPatch.EMPTY
                        : DataComponentPatch.builder().set(ModDataComponents.DEVICE_LABEL.get(), device.label())
                                .build();
                old.add(new NetworkSnapshot.Device(entry.pos(), entry.face(), entry.kind(), label, entry.priority(),
                        entry.powerPriority()));
            }
            NetworkSnapshot first = new NetworkSnapshot("old", Level.OVERWORLD, layout.core().getBlockPos(), 1, "test",
                    old, NetworkSnapshot.FORMAT_FIRST);
            DynamicOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
            Tag tag = NetworkSnapshot.CODEC.listOf().encodeStart(ops, List.of(first)).getOrThrow();
            // As the first round wrote it: no format field and no save version.
            for (Tag element : (ListTag) tag) {
                ((CompoundTag) element).remove("format");
            }
            NetworkSnapshot loaded = SAVED.parse(ops, tag).getOrThrow().getFirst();
            helper.assertValueEqual(loaded.format(), NetworkSnapshot.FORMAT_FIRST, "loaded as the first format");
            helper.assertValueEqual(loaded.devices().size(), layout.devices().size(), "every device loaded");

            for (Kind kind : Kind.values()) {
                kind.configure(helper, layout);
            }
            layout.sensor().setLabel(DeviceLabel.NONE);
            SnapshotDevices.Result result = SnapshotDevices.restore(helper.getLevel(), loaded);
            helper.assertValueEqual(result.skipped().size(), 0, "nothing skipped");
            helper.assertValueEqual(layout.sensor().label(), DeviceLabel.of("probe", DyeColor.BLUE), "label back");
            for (Kind kind : Kind.values()) {
                kind.check(helper, layout);
            }
        }).thenSucceed();
    }

    /**
     * A device switched off when the snapshot was taken comes back off, one that was on comes back on; the controller,
     * which cannot be switched, has no entry for it. The state passes through the saved data's codec.
     */
    private static void switchedOff(GameTestHelper helper) {
        Layout layout = layout(helper);
        helper.startSequence().thenWaitUntil(() -> joined(helper, layout)).thenExecute(() -> {
            GeneratorProviderBlockEntity generator = layout.generator();
            helper.assertTrue(generator.switchable(), "the generator provider can be switched");
            generator.setSwitchedOff(true);
            NetworkSnapshot off = captured(helper, layout, joined(helper, layout), "off");
            generator.setSwitchedOff(false);
            NetworkSnapshot on = captured(helper, layout, joined(helper, layout), "on");
            for (NetworkSnapshot.Device entry : off.devices()) {
                if (entry.pos().equals(layout.core().getBlockPos())) {
                    helper.assertValueEqual(entry.switchedOff(), Optional.<Boolean>empty(), "controller has no state");
                }
            }
            SnapshotDevices.restore(helper.getLevel(), off);
            helper.assertTrue(generator.switchedOff(), "off in the snapshot: back off");
            SnapshotDevices.restore(helper.getLevel(), on);
            helper.assertFalse(generator.switchedOff(), "on in the snapshot: back on");
            SnapshotDevices.restore(helper.getLevel(), on);
            helper.assertFalse(generator.switchedOff(), "on stays on");
        }).thenSucceed();
    }

    /** A format 2 snapshot (no switched-off state) leaves the switch as it is, off or on. */
    private static void switchedOld(GameTestHelper helper) {
        Layout layout = layout(helper);
        helper.startSequence().thenWaitUntil(() -> joined(helper, layout)).thenExecute(() -> {
            GeneratorProviderBlockEntity generator = layout.generator();
            generator.setSwitchedOff(true);
            List<NetworkSnapshot.Device> old = new ArrayList<>();
            for (NetworkSnapshot.Device entry : SnapshotDevices.capture(joined(helper, layout))) {
                old.add(new NetworkSnapshot.Device(entry.pos(), entry.face(), entry.kind(), entry.settings(),
                        entry.priority(), entry.powerPriority()));
            }
            NetworkSnapshot kinds = new NetworkSnapshot("old_switch", Level.OVERWORLD, layout.core().getBlockPos(), 1,
                    "test", old, NetworkSnapshot.FORMAT_KINDS);
            DynamicOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
            Tag tag = SAVED.encodeStart(ops, List.of(kinds)).getOrThrow();
            NetworkSnapshot loaded = SAVED.parse(ops, tag).getOrThrow().getFirst();
            helper.assertValueEqual(loaded.format(), NetworkSnapshot.FORMAT_KINDS, "loaded as the second format");
            SnapshotDevices.restore(helper.getLevel(), loaded);
            helper.assertTrue(generator.switchedOff(), "off stays off");
            generator.setSwitchedOff(false);
            SnapshotDevices.restore(helper.getLevel(), loaded);
            helper.assertFalse(generator.switchedOff(), "on stays on");
        }).thenSucceed();
    }
}
