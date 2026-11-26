package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.network.NetworkCommand;
import dev.silencebound.maxtech.storage.network.NetworkProfiler;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.network.StorageTask;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The network profiler (M7C item 26): of two networks, the one whose device works hard every tick comes first in the
 * profile, and so does its device; a second session cannot start while one runs.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class NetworkProfileGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    /** How long the busy task works on each run. */
    private static final long BUSY_NANOS = 200_000;
    private static final int TICKS = 20;

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("network_profile_busy_first", NetworkProfileGameTests::busyFirst);
    }

    private NetworkProfileGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("network_profile"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 200);
        }
    }

    private static ServerPlayer op(ServerLevel level, String name) {
        return new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), name),
                ClientInformation.createDefault()) {
            @Override
            public PermissionSet permissions() {
                return LevelBasedPermissionSet.GAMEMASTER;
            }
        };
    }

    private static int run(GameTestHelper helper, String command) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(Commands.literal("maxtech").then(NetworkCommand.build()));
        CommandSourceStack source = op(helper.getLevel(), "np_admin").createCommandSourceStack().withSuppressedOutput();
        try {
            return dispatcher.execute(command, source);
        } catch (CommandSyntaxException e) {
            throw helper.assertionException(Component.literal(command + ": " + e.getMessage()));
        }
    }

    /**
     * Two networks of a controller, a cable and a test device each. One device's task works for 0.2 ms every tick, the
     * other's returns at once every tick. After a second of profiling the busy network is the first of the two in the
     * report, at no less than the busy work's time per tick, and the busy device is the first of the two devices.
     */
    private static void busyFirst(GameTestHelper helper) {
        BlockPos busyController = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice busy = NetworkGameTests.device(helper, NetworkGameTests.at(3));
        BlockPos quietController = NetworkGameTests.controller(helper, 10);
        NetworkGameTests.cables(helper, 11, 11, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice quiet = NetworkGameTests.device(helper, NetworkGameTests.at(12));
        StorageTask work = (transfer, gameTime) -> {
            long end = System.nanoTime() + BUSY_NANOS;
            while (System.nanoTime() < end) {
                Thread.onSpinWait();
            }
            return StorageTask.NEXT_TICK;
        };
        StorageTask rest = (transfer, gameTime) -> StorageTask.NEXT_TICK;
        NetworkProfiler.Report[] report = new NetworkProfiler.Report[1];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkGameTests.net(helper, busy);
            NetworkGameTests.net(helper, quiet);
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, busyController, 10_000);
            NetworkGameTests.power(helper, quietController, 10_000);
            busy.schedule(work);
            quiet.schedule(rest);
            helper.assertTrue(NetworkProfiler.start(helper.getLevel().getServer(), TICKS, r -> report[0] = r),
                    "no other session runs");
            helper.assertValueEqual(run(helper, "maxtech network profile 5"), 0, "a second session is refused");
        }).thenWaitUntil(() -> helper.assertTrue(report[0] != null, "the session has not ended")).thenExecute(() -> {
            NetworkProfiler.Report done = report[0];
            BlockPos busyAt = NetworkProfiler.where(NetworkGameTests.net(helper, busy));
            BlockPos quietAt = NetworkProfiler.where(NetworkGameTests.net(helper, quiet));
            List<NetworkProfiler.NetworkRow> networks = new ArrayList<>();
            for (NetworkProfiler.NetworkRow row : done.networks()) {
                if (row.pos().equals(busyAt) || row.pos().equals(quietAt)) {
                    networks.add(row);
                }
            }
            helper.assertValueEqual(networks.size(), 2, "both networks are in the report: " + networks);
            helper.assertValueEqual(networks.get(0).pos(), busyAt, "the busy network first: " + networks);
            helper.assertTrue(done.microsPerTick(networks.get(0).nanos()) >= BUSY_NANOS / 1000.0 * 0.9,
                    "it shows the busy work's time: " + done.microsPerTick(networks.get(0).nanos()));
            BlockPos busyPos = busy.getBlockPos();
            BlockPos quietPos = quiet.getBlockPos();
            List<NetworkProfiler.DeviceRow> devices = new ArrayList<>();
            for (NetworkProfiler.DeviceRow row : done.devices()) {
                if (row.pos().equals(busyPos) || row.pos().equals(quietPos)) {
                    devices.add(row);
                }
            }
            helper.assertValueEqual(devices.size(), 2, "both devices are in the report: " + devices);
            helper.assertValueEqual(devices.get(0).pos(), busyPos, "the busy device first: " + devices);
            helper.assertValueEqual(run(helper, "maxtech network profile stop"), 0, "nothing left to stop");
        }).thenSucceed();
    }
}
