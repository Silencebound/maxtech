package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.sync.ClientSyncedList;
import dev.silencebound.maxtech.lib.sync.ServerSyncedList;
import dev.silencebound.maxtech.lib.sync.SyncedListType;
import dev.silencebound.maxtech.lib.sync.SyncedListUpdate;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Synced lists on a running server: changes made every tick reach the client at the configured rate
 * ({@code sync.listUpdatesPerSecond}), merged per id, and a list of real item stacks survives the payload codec with
 * the server's registries. Batches are recorded instead of sent; GameTest mock players have no connection.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class SyncedListGameTests {
    private static final String[] NAMES = {"sync_list_rate_limited_and_merged", "sync_list_item_stacks_round_trip"};
    /** A list kind of plain item stacks, registered like a mod's would be. */
    static final SyncedListType<ItemStack> ITEMS = SyncedListType.register(MaxtechLib.MOD_ID,
            MaxtechLib.id("test_item_list"), ItemStack.STREAM_CODEC);
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);
    private static final int TICKS = 40;

    static {
        FUNCTIONS.register(NAMES[0], () -> SyncedListGameTests::rateLimitedAndMerged);
        FUNCTIONS.register(NAMES[1], () -> SyncedListGameTests::itemStacksRoundTrip);
    }

    private SyncedListGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("synced_lists"));
        for (String name : NAMES) {
            Identifier id = MaxtechLib.id(name);
            event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                    new TestData<>(environment, LibGameTests.EMPTY_STRUCTURE, TICKS + 20, 0, true)));
        }
    }

    /**
     * Every tick for {@value #TICKS} ticks, three ids change (one of them twice). At 4 sends a second that is one batch
     * every 5 ticks, each holding at most three counts, the latest ones.
     */
    private static void rateLimitedAndMerged(GameTestHelper helper) {
        List<SyncedListUpdate<ItemStack>> sent = new ArrayList<>();
        ServerSyncedList<ItemStack> list = new ServerSyncedList<>(ITEMS, 1, id -> new ItemStack(Item.byId(id)),
                sent::add);
        long start = helper.getLevel().getGameTime();
        list.open(new Int2LongOpenHashMap(), start);
        int perSecond = ServerConfig.LIST_UPDATES_PER_SECOND.getAsInt();
        // Counted from the rate itself, not from intervalTicks, so a wrong interval fails here; rates that divide 20
        // evenly (the presets use 4) send exactly TICKS * perSecond / 20 times.
        int expected = TICKS * perSecond / 20;
        int interval = 20 / perSecond;
        int[] ticks = new int[1];
        helper.onEachTick(() -> {
            long now = helper.getLevel().getGameTime();
            if (now == start || ticks[0] >= TICKS) {
                return;
            }
            ticks[0]++;
            list.changed(1, ticks[0]);
            list.changed(1, ticks[0] * 10L);
            list.changed(2, ticks[0]);
            list.changed(3, 1);
            list.tick(now);
        });
        helper.runAfterDelay(TICKS + 5, () -> {
            helper.assertValueEqual(ticks[0], TICKS, "ticks with changes");
            helper.assertValueEqual(sent.size() - 1, expected, "batches after the dump");
            ClientSyncedList<ItemStack> client = new ClientSyncedList<>();
            for (int i = 1; i < sent.size(); i++) {
                SyncedListUpdate<ItemStack> update = sent.get(i);
                helper.assertTrue(update.countCount() <= 3, "batch " + i + " holds " + update.countCount());
                client.apply(update);
            }
            client.apply(sent.get(0));
            helper.assertValueEqual(client.size(), 0, "a late dump resets the client");
            for (int i = 1; i < sent.size(); i++) {
                client.apply(sent.get(i));
            }
            int lastSentTick = expected * interval;
            helper.assertValueEqual(client.count(1), lastSentTick * 10L, "id 1 holds its last merged count");
            helper.succeed();
        });
    }

    /** A dump of every item, with real registries: definitions decode to the same items. */
    private static void itemStacksRoundTrip(GameTestHelper helper) {
        List<SyncedListUpdate<ItemStack>> sent = new ArrayList<>();
        ServerSyncedList<ItemStack> list = new ServerSyncedList<>(ITEMS, 1, id -> new ItemStack(Item.byId(id)),
                sent::add);
        Int2LongOpenHashMap contents = new Int2LongOpenHashMap();
        for (Item item : BuiltInRegistries.ITEM) {
            if (!new ItemStack(item).isEmpty()) {
                contents.put(Item.getId(item), 64);
            }
        }
        list.open(contents, helper.getLevel().getGameTime());
        helper.assertValueEqual(sent.size(),
                (contents.size() + SyncedListUpdate.MAX_ENTRIES - 1) / SyncedListUpdate.MAX_ENTRIES, "batches");
        ClientSyncedList<ItemStack> client = new ClientSyncedList<>();
        for (SyncedListUpdate<ItemStack> update : sent) {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                    helper.getLevel().registryAccess());
            ITEMS.codec().encode(buf, update);
            client.apply(ITEMS.codec().decode(buf));
        }
        helper.assertValueEqual(client.size(), contents.size(), "entries after the dump");
        int stone = Item.getId(Items.STONE);
        ItemStack entry = client.entry(stone);
        helper.assertTrue(entry != null && entry.is(Items.STONE), "stone decoded");
        helper.succeed();
    }
}
