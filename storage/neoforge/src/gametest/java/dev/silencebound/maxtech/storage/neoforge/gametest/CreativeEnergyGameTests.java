package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.creative.CreativeEnergyBlockEntity;
import dev.silencebound.maxtech.storage.creative.CreativeEnergyContent;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.machine.StorageMachineBlockEntity;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.network.chat.Component;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The creative energy source: a network with only the source is online and powered however much it does, an adjacent FE
 * consumer can pull from it on every side, and nothing crafts it.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class CreativeEnergyGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final String[] NAMES = {"creative_energy_powers_network", "creative_energy_pulled_by_neighbours",
            "creative_energy_has_no_recipe", "creative_energy_pushes_into_machine",
            "creative_energy_push_survives_break_and_place"};
    private static final BlockPos SOURCE = new BlockPos(2, 2, 2);
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    static {
        StorageTestEnvironment.init();
        register(NAMES[0], CreativeEnergyGameTests::poweredNetwork);
        register(NAMES[1], CreativeEnergyGameTests::pulledByNeighbours);
        register(NAMES[2], CreativeEnergyGameTests::noRecipe);
        register(NAMES[3], CreativeEnergyGameTests::pushesIntoMachine);
        register(NAMES[4], CreativeEnergyGameTests::pushSurvivesBreakAndPlace);
    }

    private CreativeEnergyGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("creative_energy"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 400, 0, true)));
        }
    }

    /**
     * A source and a device form an ad-hoc network that is online and powered. A thousand operations of 64 items cost
     * far more than the 1,000 FE an ad-hoc buffer holds, and the network is still powered and unchanged afterwards.
     */
    private static void poweredNetwork(GameTestHelper helper) {
        helper.setBlock(SOURCE, CreativeEnergyContent.CREATIVE_ENERGY_SOURCE.get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, SOURCE.east());
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(device.network() != null, "no network"))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.net(helper, device);
                    helper.assertTrue(network.status() == StorageNetwork.Status.ONLINE, "not online");
                    helper.assertTrue(network.isPowered(), "not powered");
                    long before = network.storedEnergy();
                    for (int i = 0; i < 1000; i++) {
                        helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 64), 64L, "insert " + i);
                    }
                    helper.assertValueEqual(network.contents().amount(COBBLE), 64_000L, "stored");
                    helper.assertTrue(network.isPowered(), "ran out");
                    helper.assertValueEqual(network.storedEnergy(), before, "energy changed");
                }).thenSucceed();
    }

    /** Every side gives {@link Integer#MAX_VALUE} FE per call, as often as asked, and takes none. */
    private static void pulledByNeighbours(GameTestHelper helper) {
        helper.setBlock(SOURCE, CreativeEnergyContent.CREATIVE_ENERGY_SOURCE.get());
        for (Direction side : Direction.values()) {
            EnergyHandler handler = helper.getLevel().getCapability(Capabilities.Energy.BLOCK,
                    helper.absolutePos(SOURCE), side);
            if (handler == null) {
                throw helper.assertionException(Component.literal("no FE on " + side));
            }
            for (int call = 0; call < 3; call++) {
                try (Transaction transaction = Transaction.openRoot()) {
                    helper.assertValueEqual(handler.extract(Integer.MAX_VALUE, transaction), Integer.MAX_VALUE,
                            "extract on " + side);
                    helper.assertValueEqual(handler.insert(1000, transaction), 0, "insert on " + side);
                    transaction.commit();
                }
            }
        }
        helper.succeed();
    }

    private static void noRecipe(GameTestHelper helper) {
        Identifier item = MaxtechStorage.id("creative_energy_source");
        for (RecipeHolder<?> holder : helper.getLevel().recipeAccess().recipeMap().values()) {
            helper.assertTrue(!holder.id().identifier().equals(item), "recipe " + holder.id());
        }
        helper.succeed();
    }

    /**
     * In push mode a lithograph next to the source gets FE and makes wafers. Without push it stays unpowered, and a
     * source with no neighbour that takes FE sleeps instead of running every tick.
     */
    private static void pushesIntoMachine(GameTestHelper helper) {
        helper.setBlock(SOURCE, CreativeEnergyContent.CREATIVE_ENERGY_SOURCE.get());
        helper.setBlock(SOURCE.east(), MachineContent.block(MachineKind.LITHOGRAPH));
        CreativeEnergyBlockEntity source = helper.getBlockEntity(SOURCE, CreativeEnergyBlockEntity.class);
        StorageMachineBlockEntity lithograph = helper.getBlockEntity(SOURCE.east(), StorageMachineBlockEntity.class);
        int[] runs = new int[1];
        helper.startSequence().thenExecute(() -> {
            helper.assertFalse(source.pushing(), "pushes by default");
            MachineGameTests.insert(lithograph, new ItemStack(MaterialContent.SILICON.get(), 2));
        }).thenIdle(20).thenExecute(() -> {
            helper.assertValueEqual(lithograph.energy().stored(), 0L, "energy without push mode");
            helper.assertValueEqual(source.pushRuns(), 0, "push runs while off");
            source.setPushing(true);
        }).thenWaitUntil(() -> helper.assertTrue(lithograph.outputs().stack(0).getCount() >= 1, "no wafers yet"))
                .thenExecute(() -> {
                    helper.setBlock(SOURCE.east(), Blocks.AIR);
                })
                .thenWaitUntil(
                        () -> helper.assertFalse(source.isAwake(), "source awake with no neighbour that takes FE"))
                .thenExecute(() -> runs[0] = source.pushRuns()).thenIdle(20).thenExecute(() -> {
                    helper.assertValueEqual(source.pushRuns(), runs[0], "push runs with nothing to feed");
                }).thenSucceed();
    }

    /** Push mode is saved with the block and travels in the dropped item to the next block. */
    private static void pushSurvivesBreakAndPlace(GameTestHelper helper) {
        helper.setBlock(SOURCE, CreativeEnergyContent.CREATIVE_ENERGY_SOURCE.get());
        CreativeEnergyBlockEntity source = helper.getBlockEntity(SOURCE, CreativeEnergyBlockEntity.class);
        source.setPushing(true);
        CompoundTag saved = source.saveCustomOnly(helper.getLevel().registryAccess());
        helper.assertTrue(saved.getBooleanOr("push", false), "push not saved");
        helper.getLevel().destroyBlock(helper.absolutePos(SOURCE), true);
        ItemStack item = ItemStack.EMPTY;
        for (ItemEntity drop : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(helper.absolutePos(SOURCE)).inflate(2))) {
            if (drop.getItem().is(CreativeEnergyContent.CREATIVE_ENERGY_SOURCE.get().asItem())) {
                item = drop.getItem().copy();
            }
            drop.discard();
        }
        TypedEntityData<BlockEntityType<?>> data = item.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null) {
            throw helper.assertionException(Component.literal("no block entity data on the dropped item"));
        }
        helper.setBlock(SOURCE, CreativeEnergyContent.CREATIVE_ENERGY_SOURCE.get());
        CreativeEnergyBlockEntity again = helper.getBlockEntity(SOURCE, CreativeEnergyBlockEntity.class);
        helper.assertFalse(again.pushing(), "a fresh source pushes");
        data.loadInto(again, helper.getLevel().registryAccess());
        helper.assertTrue(again.pushing(), "push lost through the item");
        helper.succeed();
    }
}
