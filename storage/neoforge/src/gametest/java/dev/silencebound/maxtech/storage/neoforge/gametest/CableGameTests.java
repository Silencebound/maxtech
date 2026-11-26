package dev.silencebound.maxtech.storage.neoforge.gametest;

import static dev.silencebound.maxtech.storage.neoforge.gametest.NetworkGameTests.at;
import static dev.silencebound.maxtech.storage.neoforge.gametest.NetworkGameTests.cables;
import static dev.silencebound.maxtech.storage.neoforge.gametest.NetworkGameTests.controller;
import static dev.silencebound.maxtech.storage.neoforge.gametest.NetworkGameTests.device;
import static dev.silencebound.maxtech.storage.neoforge.gametest.NetworkGameTests.power;
import static dev.silencebound.maxtech.storage.neoforge.gametest.NetworkGameTests.state;

import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.facade.Facades;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cable.CableLook;
import dev.silencebound.maxtech.storage.cable.SmartLoad;
import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.cable.StorageCableConduitType;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.registry.ModCreativeTabs;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * M6d's storage cables: every tier carries its configured channels in every look (M7N), a smart cable's load bucket
 * follows its segment's channel use at the network's cadence and falls back to idle, and a facade on a storage cable
 * keeps its links.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class CableGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> NAMES = new ArrayList<>();

    static {
        register("cable_tier_channels", CableGameTests::tierChannels);
        register("cable_equal_width", CableGameTests::equalWidth);
        register("cable_replaced_tier", CableGameTests::replacedTier);
        register("cable_smart_load", CableGameTests::smartLoad);
        register("cable_facade_keeps_links", CableGameTests::facadeKeepsLinks);
        register("cable_glass_retired", CableGameTests::glassRetired);
    }

    private CableGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> env = event.registerEnvironment(MaxtechStorage.id("cables"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(env, STRUCTURE, 400, 0, true)));
        }
    }

    /**
     * Every look of every tier is registered with its tier and look; a tier-4 cable at the controller carries 64
     * channels (its root link carries as many), a tier-3 one behind it 32 (the slower end of the link), a smart tier-3
     * cable the same as a plain one, and tiers 2 and 1 16 and 8. The device at the end holds one channel on each.
     */
    private static void tierChannels(GameTestHelper helper) {
        for (CableLook look : CableLook.values()) {
            for (int tier = 1; tier <= StorageCableConduitType.MAX_TIER; tier++) {
                StorageCableConduitType type = CableContent.cable(look, tier).type().get();
                helper.assertValueEqual(type.level(), tier, look + " tier");
                helper.assertValueEqual(type.look(), look, "look of tier " + tier);
            }
        }
        BlockPos controller = controller(helper, 1);
        cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_4.type().get());
        cables(helper, 3, 3, CableContent.STORAGE_CABLE_TIER_3.type().get());
        cables(helper, 4, 4, CableContent.cable(CableLook.SMART, 3).type().get());
        cables(helper, 5, 5, CableContent.cable(CableLook.COVERED, 4).type().get());
        cables(helper, 6, 6, CableContent.cable(CableLook.GLASS, 2).type().get());
        cables(helper, 7, 7, CableContent.cable(CableLook.FIBER, 1).type().get());
        device(helper, at(8));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(state(helper, controller).nodeCount(), 8, "nodes"))
                .thenExecute(() -> {
                    NetworkState network = state(helper, controller);
                    helper.assertValueEqual(network.segmentCapacity(abs(helper, 2)), 64, "tier 4 to controller");
                    helper.assertValueEqual(network.segmentCapacity(abs(helper, 3)), 32, "tier 3 to tier 4");
                    helper.assertValueEqual(network.segmentCapacity(abs(helper, 4)), 32, "smart tier 3");
                    helper.assertValueEqual(network.segmentCapacity(abs(helper, 5)), 32, "covered tier 4 to tier 3");
                    helper.assertValueEqual(network.segmentCapacity(abs(helper, 6)), 16, "glass tier 2 behind tier 3");
                    helper.assertValueEqual(network.segmentCapacity(abs(helper, 7)), 8, "fiber tier 1 behind tier 2");
                    for (int x = 2; x <= 7; x++) {
                        helper.assertValueEqual(network.segmentChannels(abs(helper, x)), 1, "channels at " + x);
                    }
                    helper.assertValueEqual(network.smartCableCount(), 1, "smart cables");
                }).thenSucceed();
    }

    /**
     * A cable swapped for another tier in one tick (broken and placed again before the graph applies either) counts at
     * its new tier: the network keeps each node's cable type from its node add, so the swap has to replace it.
     */
    private static void replacedTier(GameTestHelper helper) {
        BlockPos controller = controller(helper, 1);
        cables(helper, 2, 3, CableContent.STORAGE_CABLE_TIER_4.type().get());
        device(helper, at(4));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(state(helper, controller).segmentCapacity(abs(helper, 3)),
                        64, "tier 4 behind tier 4"))
                .thenExecute(() -> {
                    helper.setBlock(at(3), Blocks.AIR);
                    cables(helper, 3, 3, CableContent.STORAGE_CABLE_TIER_1.type().get());
                })
                .thenWaitUntil(() -> helper.assertValueEqual(state(helper, controller).segmentCapacity(abs(helper, 3)),
                        8, "tier 1 behind tier 4"))
                .thenSucceed();
    }

    /**
     * The glass look is retired (owner decision 2026-10-15: it draws like plain): its cables stay registered, so the
     * tier channel test above still lays one, but no recipe makes them and Storage's creative tab leaves them out,
     * while every listed look keeps both.
     */
    private static void glassRetired(GameTestHelper helper) {
        Set<Identifier> recipes = new HashSet<>();
        for (RecipeHolder<?> holder : helper.getLevel().recipeAccess().recipeMap().values()) {
            recipes.add(holder.id().identifier());
        }
        CreativeModeTab tab = ModCreativeTabs.STORAGE.get();
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(helper.getLevel().enabledFeatures(), false,
                helper.getLevel().registryAccess()));
        Set<Item> shown = new HashSet<>();
        for (ItemStack stack : tab.getDisplayItems()) {
            shown.add(stack.getItem());
        }
        for (CableLook look : CableLook.values()) {
            for (int tier = 1; tier <= StorageCableConduitType.MAX_TIER; tier++) {
                Item item = CableContent.cable(look, tier).item().get();
                Identifier id = MaxtechStorage.id(look.name(tier));
                helper.assertValueEqual(recipes.contains(id), look.listed(), id + " has a recipe");
                helper.assertValueEqual(shown.contains(item), look.listed(), id + " in the creative tab");
            }
        }
        helper.succeed();
    }

    /**
     * Every look of every tier is 4 px wide (owner decision 2026-10-06: the tier shows as bands, not width), and a
     * placed tier-1 and tier-4 cable have the same shape.
     */
    private static void equalWidth(GameTestHelper helper) {
        for (CableLook look : CableLook.values()) {
            for (int tier = 1; tier <= StorageCableConduitType.MAX_TIER; tier++) {
                helper.assertValueEqual(CableContent.cable(look, tier).type().get().thickness(), 4.0F / 16.0F,
                        look + " tier " + tier + " width");
            }
        }
        cables(helper, 1, 1, CableContent.STORAGE_CABLE_TIER_1.type().get());
        cables(helper, 3, 3, CableContent.STORAGE_CABLE_TIER_4.type().get());
        helper.assertValueEqual(
                helper.getBlockState(at(3)).getShape(helper.getLevel(), helper.absolutePos(at(3))).bounds(),
                helper.getBlockState(at(1)).getShape(helper.getLevel(), helper.absolutePos(at(1))).bounds(), "shape");
        helper.succeed();
    }

    /**
     * Eight devices behind a tier-1 smart cable fill its eight channels: the smart cable shows the saturated bucket on
     * its synced state, the plain one beside it shows nothing; once the devices are gone, the bucket goes back to idle
     * at the next cadence.
     */
    private static void smartLoad(GameTestHelper helper) {
        BlockPos controller = controller(helper, 1);
        cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        cables(helper, 3, 3, CableContent.cable(CableLook.SMART, 1).type().get());
        cables(helper, 4, 5, CableContent.STORAGE_CABLE_TIER_4.type().get());
        List<BlockPos> devices = new ArrayList<>();
        for (int x = 4; x <= 5; x++) {
            devices.add(new BlockPos(x, 1, 0));
            devices.add(new BlockPos(x, 1, 2));
            devices.add(new BlockPos(x, 2, 1));
            devices.add(new BlockPos(x, 0, 1));
        }
        for (BlockPos pos : devices) {
            device(helper, pos);
        }
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(state(helper, controller).channelsHeld(), 8, "channels"))
                .thenWaitUntil(
                        () -> helper.assertValueEqual(parts(helper, 3).load(), SmartLoad.SATURATED, "smart cable load"))
                .thenExecute(() -> {
                    helper.assertValueEqual(parts(helper, 2).load(), SmartLoad.IDLE, "plain cable");
                    for (BlockPos pos : devices) {
                        helper.destroyBlock(pos);
                    }
                }).thenWaitUntil(() -> helper.assertValueEqual(state(helper, controller).channelsHeld(), 0, "channels"))
                .thenWaitUntil(
                        () -> helper.assertValueEqual(parts(helper, 3).load(), SmartLoad.IDLE, "load after removal"))
                .thenSucceed();
    }

    /**
     * A facade on a storage cable (Lib's mechanism, as Lib's facade item sets it) hides the cable's parts but keeps it
     * a linked node; taking it off leaves the network as it was.
     */
    private static void facadeKeepsLinks(GameTestHelper helper) {
        BlockPos controller = controller(helper, 1);
        cables(helper, 2, 3, CableContent.cable(CableLook.COVERED, 2).type().get());
        TestStorage.TestDevice port = device(helper, at(4));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(state(helper, controller).nodeCount(), 4, "nodes"))
                .thenExecute(() -> {
                    ConduitBlockEntity cable = helper.getBlockEntity(at(2), ConduitBlockEntity.class);
                    BlockPos pos = helper.absolutePos(at(2));
                    helper.assertTrue(Facades.set(helper.getLevel(), pos, cable, Blocks.STONE.defaultBlockState()),
                            "facade refused");
                    helper.assertTrue(helper.getBlockState(at(2)).getValue(ConduitBlock.FACADE), "facade flag");
                    helper.assertTrue(StorageCableBlock.parts(helper.getLevel(), pos) == null, "parts under facade");
                })
                .thenWaitUntil(
                        () -> helper.assertValueEqual(state(helper, controller).nodeCount(), 4, "nodes with facade"))
                .thenExecute(() -> {
                    helper.assertTrue(port.network() == state(helper, controller), "device cut off by the facade");
                    ConduitBlockEntity cable = helper.getBlockEntity(at(2), ConduitBlockEntity.class);
                    helper.assertTrue(Facades.remove(helper.getLevel(), helper.absolutePos(at(2)), cable) != null,
                            "facade not removed");
                }).thenWaitUntil(() -> helper.assertTrue(port.network() == state(helper, controller), "device"))
                .thenSucceed();
    }

    private static long abs(GameTestHelper helper, int x) {
        return helper.absolutePos(at(x)).asLong();
    }

    private static CableParts parts(GameTestHelper helper, int x) {
        ConduitBlockEntity cable = helper.getBlockEntity(at(x), ConduitBlockEntity.class);
        if (!(cable.conduit().state() instanceof CableParts parts)) {
            throw helper.assertionException(Component.literal("No cable parts"));
        }
        return parts;
    }
}
