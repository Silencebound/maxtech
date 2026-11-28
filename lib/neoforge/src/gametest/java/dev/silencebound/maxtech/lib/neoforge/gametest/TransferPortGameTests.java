package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetworks;
import dev.silencebound.maxtech.lib.api.energy.EnergyPriority;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import dev.silencebound.maxtech.lib.api.transfer.PortFilter;
import dev.silencebound.maxtech.lib.api.transfer.PortSettings;
import dev.silencebound.maxtech.lib.api.transfer.TransferPorts;
import dev.silencebound.maxtech.lib.neoforge.api.transfer.KindBridge;
import dev.silencebound.maxtech.lib.neoforge.api.transfer.KindBridges;
import dev.silencebound.maxtech.lib.neoforge.api.transfer.KindHandler;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** Port filters and settings over the built-in kinds, which need registries and tags (JUnit covers the rest). */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class TransferPortGameTests {
    private static final String TAGS = "transfer_filter_tags";
    private static final String SAVED = "transfer_settings_saved";
    private static final String RAW_KEYS = "transfer_filter_raw_keys";
    private static final String BRIDGE = "transfer_bridge_chest";
    private static final String ENERGY = "transfer_energy_buffer";
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        FUNCTIONS.register(TAGS, () -> TransferPortGameTests::tags);
        FUNCTIONS.register(SAVED, () -> TransferPortGameTests::saved);
        FUNCTIONS.register(RAW_KEYS, () -> TransferPortGameTests::rawKeys);
        FUNCTIONS.register(BRIDGE, () -> TransferPortGameTests::bridgeChest);
        FUNCTIONS.register(ENERGY, () -> TransferPortGameTests::energyBuffer);
    }

    private TransferPortGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("transfer"));
        for (String name : List.of(TAGS, SAVED, RAW_KEYS, BRIDGE, ENERGY)) {
            Identifier id = MaxtechLib.id(name);
            event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                    new TestData<>(environment, LibGameTests.EMPTY_STRUCTURE, 20, 0, true)));
        }
    }

    /** Item and fluid tags match their members; Lib bridges items and fluids, not energy. */
    private static void tags(GameTestHelper helper) {
        PortFilter<ItemKey> logs = PortFilter.of(ResourceKinds.ITEM, PortFilter.Mode.ALLOW,
                List.of(ItemKey.of(Items.STONE)), List.of(Identifier.withDefaultNamespace("logs")));
        helper.assertTrue(logs.test(ItemKey.of(Items.OAK_LOG)), "oak log in #logs");
        helper.assertTrue(logs.test(ItemKey.of(Items.STONE)), "stone listed");
        helper.assertFalse(logs.test(ItemKey.of(Items.DIRT)), "dirt allowed");
        PortFilter<FluidKey> water = PortFilter.of(ResourceKinds.FLUID, PortFilter.Mode.DENY, List.of(),
                List.of(Identifier.withDefaultNamespace("water")));
        helper.assertFalse(water.test(FluidKey.of(Fluids.WATER)), "water denied");
        helper.assertTrue(water.test(FluidKey.of(Fluids.LAVA)), "lava allowed");
        helper.assertTrue(KindBridges.get(ResourceKinds.ITEM).isPresent(), "item bridge");
        helper.assertTrue(KindBridges.get(ResourceKinds.FLUID).isPresent(), "fluid bridge");
        helper.assertFalse(KindBridges.get(ResourceKinds.ENERGY).isPresent(), "energy bridge");
        helper.succeed();
    }

    /** Settings with item and fluid filters and energy faces save by kind id and load back equal. */
    private static void saved(GameTestHelper helper) {
        PortSettings settings = PortSettings.EMPTY.withMode(ResourceKinds.ITEM, Direction.UP, PortFaceMode.PUSH)
                .withFilter(ResourceKinds.ITEM,
                        Optional.of(PortFilter.of(ResourceKinds.ITEM, PortFilter.Mode.DENY,
                                List.of(ItemKey.of(Items.DIRT)), List.of(Identifier.withDefaultNamespace("logs")))))
                .withMode(ResourceKinds.FLUID, Direction.DOWN, PortFaceMode.PULL).withRate(ResourceKinds.FLUID, 500)
                .withMode(ResourceKinds.ENERGY, Direction.NORTH, PortFaceMode.PUSH);
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Tag saved = PortSettings.CODEC.encodeStart(ops, settings).getOrThrow();
        helper.assertValueEqual(settings, PortSettings.CODEC.parse(ops, saved).getOrThrow(), "loaded settings");
        helper.assertTrue(saved.toString().contains("maxtech_lib:item"), "saved by kind id: " + saved);
        helper.succeed();
    }

    /** A removed item and an unknown component stay raw in an item filter, are saved back and match nothing. */
    private static void rawKeys(GameTestHelper helper) {
        CompoundTag goneItem = new CompoundTag();
        goneItem.putString("id", "maxtech_lib:removed_item");
        CompoundTag components = new CompoundTag();
        components.putInt("maxtech_lib:removed_component", 1);
        CompoundTag goneComponent = new CompoundTag();
        goneComponent.putString("id", "minecraft:stone");
        goneComponent.put("components", components);
        CompoundTag stone = new CompoundTag();
        stone.putString("id", "minecraft:stone");
        ListTag keys = new ListTag();
        keys.add(goneItem);
        keys.add(stone);
        keys.add(goneComponent);
        CompoundTag saved = new CompoundTag();
        saved.putString("mode", "deny");
        saved.put("keys", keys);

        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        PortFilter<ItemKey> filter = PortFilter.codec(ResourceKinds.ITEM).parse(ops, saved).getOrThrow();
        helper.assertValueEqual(filter.keys(), List.of(ItemKey.of(Items.STONE)), "keys that load");
        helper.assertValueEqual(filter.unknownKeys(), List.<Tag>of(goneItem, goneComponent), "raw keys");
        helper.assertValueEqual(filter.size(), 3, "entries");
        helper.assertFalse(filter.test(ItemKey.of(Items.STONE)), "stone denied");
        helper.assertTrue(filter.test(ItemKey.of(Items.DIRT)), "raw keys match nothing");
        CompoundTag again = (CompoundTag) PortFilter.codec(ResourceKinds.ITEM).encodeStart(ops, filter).getOrThrow();
        ListTag savedKeys = again.getListOrEmpty("keys");
        helper.assertTrue(savedKeys.contains(goneItem) && savedKeys.contains(goneComponent) && savedKeys.size() == 3,
                "raw keys saved back: " + savedKeys);
        helper.succeed();
    }

    /** The item bridge on a real chest: one wrapper per handler, simulations and open transactions roll back. */
    @SuppressWarnings("ReferenceEquality") // identity is what the cache promises
    private static void bridgeChest(GameTestHelper helper) {
        helper.setBlock(BlockPos.ZERO, Blocks.CHEST);
        KindBridge<ItemKey> bridge = KindBridges.get(ResourceKinds.ITEM).orElseThrow();
        KindBridge.Cache<ItemKey> cache = bridge.cache(helper.getLevel(), helper.absolutePos(BlockPos.ZERO),
                Direction.UP, () -> true, () -> {
                });
        KindHandler<ItemKey> chest = cache.handler().orElseThrow();
        helper.assertTrue(chest == cache.handler().orElseThrow(), "the cache keeps one wrapper per handler");
        helper.assertTrue(chest.transactional(), "chests roll back");
        ItemKey stone = ItemKey.of(Items.STONE);

        try (Transaction tx = Transaction.openRoot()) {
            helper.assertValueEqual(chest.simulateInsert(stone, 10, tx), 10L, "simulated insert");
            helper.assertValueEqual(total(chest), 0L, "a simulated insert changed the chest");
            helper.assertValueEqual(chest.insert(stone, 10, tx), 10L, "insert in an open transaction");
        }
        helper.assertValueEqual(total(chest), 0L, "an insert without commit rolled back");

        try (Transaction tx = Transaction.openRoot()) {
            helper.assertValueEqual(chest.insert(stone, 10, tx), 10L, "insert");
            tx.commit();
        }
        helper.assertValueEqual(total(chest), 10L, "committed insert");
        try (Transaction tx = Transaction.openRoot()) {
            helper.assertValueEqual(chest.simulateExtract(stone, 4, tx), 4L, "simulated extract");
            helper.assertValueEqual(total(chest), 10L, "a simulated extract changed the chest");
            helper.assertValueEqual(chest.extract(stone, 4, tx), 4L, "extract in an open transaction");
        }
        helper.assertValueEqual(total(chest), 10L, "an extract without commit rolled back");

        // Amounts above int range saturate instead of wrapping to zero or below.
        try (Transaction tx = Transaction.openRoot()) {
            helper.assertValueEqual(chest.simulateExtract(stone, 1L << 32, tx), 10L, "extract 2^32");
            helper.assertValueEqual(chest.simulateExtract(stone, Long.MAX_VALUE, tx), 10L, "extract Long.MAX");
            helper.assertValueEqual(chest.simulateInsert(ItemKey.of(Items.DIRT), 1L << 32, tx), 26L * 64,
                    "insert 2^32 fills the free slots");
            helper.assertValueEqual(chest.simulateInsert(ItemKey.of(Items.DIRT), Long.MAX_VALUE, tx), 26L * 64,
                    "insert Long.MAX fills the free slots");
        }
        helper.succeed();
    }

    /** The builder takes a standalone energy buffer, and refuses it once it joined an energy network. */
    private static void energyBuffer(GameTestHelper helper) {
        EnergyEndpoint buffer = EnergyEndpoint.buffer(1000, 10, 10, EnergyPriority.DEFAULT);
        TransferPorts.Builder builder = TransferPorts.builder().energy(buffer, Set.of(PortFaceMode.PUSH));
        EnergyNetworks.of(helper.getLevel()).createNetwork().add(buffer);
        boolean refused = false;
        try {
            builder.build();
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        helper.assertTrue(refused, "an endpoint that joined a network is refused at build");
        helper.succeed();
    }

    private static long total(KindHandler<ItemKey> handler) {
        long total = 0;
        for (int i = 0; i < handler.size(); i++) {
            total += handler.amountAt(i);
        }
        return total;
    }
}
