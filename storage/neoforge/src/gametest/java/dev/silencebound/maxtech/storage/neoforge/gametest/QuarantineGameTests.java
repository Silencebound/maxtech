package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.authlib.GameProfile;
import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.Quarantines;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import java.util.concurrent.atomic.AtomicInteger;
import dev.silencebound.maxtech.storage.tool.quarantine.QuarantineContent;
import dev.silencebound.maxtech.storage.tool.quarantine.QuarantineTagItem;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** M6e area F: the quarantine tag freezes a network, keeps the freeze through a split and a merge, and lifts it. */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class QuarantineGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String FREEZE = "server_quarantine_freeze";
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(FREEZE, StorageTestEnvironment.clearing(QuarantineGameTests::freeze));
    }

    private QuarantineGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("server_quarantine"),
                StorageTestEnvironment.plain());
        StorageTests.test(event, plain, FREEZE, STRUCTURE, 400);
    }

    /**
     * An owned, powered network with a drive at the far end: a stranger's tag changes nothing; the owner's freezes it
     * (paused status, the quarantine line, no insert). Cutting a cable leaves both parts frozen, and joining them again
     * keeps the freeze; the broken cable's spot leaves the saved set. A task scheduled while frozen runs only once the
     * owner's second use lifts the quarantine, and inserts work again.
     */
    private static void freeze(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer owner = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "quarantine_owner"));
        ServerPlayer stranger = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "quarantine_stranger"));
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        NetworkGameTests.power(helper, core, 100_000);
        NetworkGameTests.cables(helper, 2, 8, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice far = NetworkGameTests.device(helper, NetworkGameTests.at(9));
        BlockPos cut = NetworkGameTests.at(5);
        ItemStack tag = new ItemStack(QuarantineContent.QUARANTINE_TAG.get());
        AtomicInteger runs = new AtomicInteger();
        StorageTask task = new StorageTask() {
            @Override
            public int run(StorageTransfer transfer, long gameTime) {
                runs.incrementAndGet();
                return DONE;
            }
        };
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(NetworkGameTests.net(helper, far) == NetworkGameTests.state(helper, core), "joined");
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, core);
            useOn(tag, stranger, helper.absolutePos(NetworkGameTests.at(3)));
            helper.assertTrue(!network.quarantined(), "a stranger's tag");
            useOn(tag, owner, helper.absolutePos(NetworkGameTests.at(3)));
            helper.assertTrue(network.quarantined(), "the owner's tag");
            helper.assertValueEqual(network.status(), StorageNetwork.Status.PAUSED, "status");
            helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 5), 0L, "frozen insert");
            helper.assertTrue(NetworkGameTests.keys(helper, core, BlockInfoRequest.Detail.FULL)
                    .contains(NetworkInfo.QUARANTINED_KEY), "the quarantine line");
            far.schedule(task);
        }).thenIdle(10).thenExecute(() -> {
            helper.assertValueEqual(runs.get(), 0, "task runs while frozen");
            helper.destroyBlock(cut);
        }).thenWaitUntil(() -> {
            NetworkState main = NetworkGameTests.state(helper, core);
            NetworkState split = NetworkGameTests.net(helper, far);
            helper.assertTrue(split != main, "not split");
            helper.assertTrue(main.quarantined() && split.quarantined(), "both parts frozen");
            helper.assertTrue(!Quarantines.get(level).contains(helper.absolutePos(cut).asLong()),
                    "the broken cable's spot is pruned");
        }).thenExecute(() -> ConduitBlock.placeConduit(level, helper.absolutePos(cut),
                CableContent.STORAGE_CABLE_TIER_1.type().get(), null)).thenWaitUntil(() -> {
                    NetworkState network = NetworkGameTests.state(helper, core);
                    helper.assertTrue(network == NetworkGameTests.net(helper, far), "not merged");
                    helper.assertTrue(network.quarantined(), "merged network frozen");
                }).thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, core);
                    helper.assertTrue(QuarantineTagItem.toggle(owner, network), "the owner lifts it");
                    helper.assertValueEqual(network.status(), StorageNetwork.Status.ONLINE, "status after lifting");
                    helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 5), 5L, "insert after lifting");
                }).thenWaitUntil(() -> helper.assertValueEqual(runs.get(), 1, "task runs after lifting")).thenSucceed();
    }

    private static void useOn(ItemStack tag, ServerPlayer player, BlockPos at) {
        tag.onItemUseFirst(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false)));
    }
}
