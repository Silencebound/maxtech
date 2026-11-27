package dev.silencebound.maxtech.lib.neoforge.gametest;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.inventory.ResourceTank;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.neoforge.api.inventory.Handlers;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Lib's inventories through the handlers {@link Handlers} gives out: roles, filters and limits for automation, real
 * hoppers, exact rollback, and a tank that keeps saved contents it cannot load.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class InventoryGameTests {
    private static final Identifier STRUCTURE = MaxtechLib.id("empty_7x2x3");
    private static final String[] NAMES = {"inventory_handler_roles", "inventory_abort_restores",
            "inventory_hoppers_follow_roles", "inventory_tank_handler", "inventory_tank_keeps_unloaded"};

    /** Slot 0: input for coal, at most 16. Slot 1: output. Slot 2: display. Slot 3: input for anything. */
    static final ItemSlots.Builder SLOTS = ItemSlots.builder(4).input(0, 1, stack -> stack.is(Items.COAL))
            .limit(0, 1, 16).output(1, 2).display(2, 3);

    private static final RegistryHelper.Registrar<Block> BLOCKS = Platform.get().registry().registrar(MaxtechLib.MOD_ID,
            Registries.BLOCK);
    static final RegistryEntry<Block, SlotsBlock> SLOTS_BLOCK = BLOCKS.register("test_slots",
            id -> new SlotsBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id))));
    private static final RegistryHelper.Registrar<BlockEntityType<?>> BLOCK_ENTITIES = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.BLOCK_ENTITY_TYPE);
    private static final RegistryEntry<BlockEntityType<?>, BlockEntityType<SlotsEntity>> SLOTS_ENTITY = BLOCK_ENTITIES
            .register("test_slots", () -> new BlockEntityType<>(SlotsEntity::new, Set.of(SLOTS_BLOCK.get())));
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        FUNCTIONS.register(NAMES[0], () -> InventoryGameTests::handlerRoles);
        FUNCTIONS.register(NAMES[1], () -> InventoryGameTests::abortRestores);
        FUNCTIONS.register(NAMES[2], () -> InventoryGameTests::hoppersFollowRoles);
        FUNCTIONS.register(NAMES[3], () -> InventoryGameTests::tankHandler);
        FUNCTIONS.register(NAMES[4], () -> InventoryGameTests::tankKeepsUnloaded);
    }

    private InventoryGameTests() {
    }

    @SubscribeEvent
    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, SLOTS_ENTITY.get(),
                (entity, side) -> Handlers.items(entity.slots));
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, SLOTS_ENTITY.get(),
                (entity, side) -> Handlers.fluids(entity.tank));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("inventory"));
        for (String name : NAMES) {
            event.registerTest(MaxtechLib.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechLib.id(name)),
                            new TestData<>(environment, STRUCTURE, 100, 0, true)));
        }
    }

    /** Counts change events per slot. */
    private static final class Counter {
        final int[] slots = new int[4];
        int tank;
    }

    /**
     * Automation inserts only into inputs, within filter and limit, and extracts only from outputs; slots that take
     * nothing report capacity 0. The machine's own methods ignore roles. Changes are reported once per commit.
     */
    private static void handlerRoles(GameTestHelper helper) {
        Counter counter = new Counter();
        ItemSlots slots = SLOTS.build(slot -> counter.slots[slot]++);
        ResourceHandler<ItemResource> handler = Handlers.items(slots);
        helper.assertTrue(handler.equals(Handlers.items(slots)), "a new handler on each call");
        ItemResource coal = ItemResource.of(Items.COAL);
        ItemResource dirt = ItemResource.of(Items.DIRT);
        try (Transaction tx = Transaction.openRoot()) {
            helper.assertValueEqual(handler.insert(0, dirt, 5, tx), 0, "filtered input took dirt");
            helper.assertValueEqual(handler.insert(0, coal, 64, tx), 16, "input past its limit");
            helper.assertValueEqual(handler.insert(1, coal, 5, tx), 0, "output took coal");
            helper.assertValueEqual(handler.insert(2, coal, 5, tx), 0, "display took coal");
            helper.assertValueEqual(handler.insert(dirt, 70, tx), 64, "index-free insert into the open input");
            helper.assertValueEqual(handler.extract(0, coal, 5, tx), 0, "automation took from an input");
            helper.assertValueEqual(counter.slots[0], 0, "reported before the commit");
            tx.commit();
        }
        helper.assertValueEqual(counter.slots[0], 1, "slot 0 changes");
        helper.assertValueEqual(counter.slots[3], 1, "slot 3 changes");
        helper.assertValueEqual(handler.getCapacityAsLong(1, ItemResource.EMPTY), 0L, "output capacity");
        helper.assertValueEqual(handler.getCapacityAsLong(2, ItemResource.EMPTY), 0L, "display capacity");
        helper.assertValueEqual(handler.getCapacityAsLong(0, coal), 16L, "input capacity");
        helper.assertFalse(handler.isValid(1, coal), "output valid for automation");

        helper.assertValueEqual(slots.insert(1, new ItemStack(Items.IRON_INGOT, 10), false), 10, "machine output");
        helper.assertValueEqual(slots.insert(1, new ItemStack(Items.IRON_INGOT, 60), true), 54, "simulated room");
        helper.assertValueEqual(slots.stack(1).getCount(), 10, "simulation changed the slot");
        slots.set(2, new ItemStack(Items.DIAMOND));
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        try (Transaction tx = Transaction.openRoot()) {
            helper.assertValueEqual(handler.extract(1, iron, 4, tx), 4, "output extract");
            helper.assertValueEqual(handler.extract(2, ItemResource.of(Items.DIAMOND), 1, tx), 0, "display gave");
            tx.commit();
        }
        helper.assertValueEqual(slots.stack(1).getCount(), 6, "output left");
        helper.assertValueEqual(slots.extract(0, 3, false), 3, "machine takes its own input");
        helper.assertValueEqual(slots.stack(0).getCount(), 13, "input left");
        helper.assertValueEqual(counter.slots[2], 1, "set reported once");
        helper.succeed();
    }

    /**
     * Moves into and out of slots and a tank, partly in a committed inner transaction, all rolled back by the root:
     * every stack (components included) and the tank are as before, and nothing is reported.
     */
    private static void abortRestores(GameTestHelper helper) {
        Counter counter = new Counter();
        ItemSlots slots = SLOTS.build(slot -> counter.slots[slot]++);
        FluidTank tank = FluidTank.create(4000, () -> counter.tank++);
        ItemStack named = new ItemStack(Items.STICK, 7);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Kept"));
        slots.set(0, new ItemStack(Items.COAL, 9));
        slots.set(1, new ItemStack(Items.IRON_INGOT, 30));
        slots.set(3, named);
        tank.set(FluidKey.of(Fluids.WATER), 1500);
        Optional<FluidKey> water = tank.resource();
        counter.slots[0] = counter.slots[1] = counter.slots[3] = counter.tank = 0;
        ResourceHandler<ItemResource> items = Handlers.items(slots);
        ResourceHandler<FluidResource> fluids = Handlers.fluids(tank);
        try (Transaction root = Transaction.openRoot()) {
            items.insert(0, ItemResource.of(Items.COAL), 5, root);
            items.extract(1, ItemResource.of(Items.IRON_INGOT), 30, root);
            fluids.extract(0, FluidResource.of(Fluids.WATER), 1500, root);
            try (Transaction inner = Transaction.open(root)) {
                fluids.insert(0, FluidResource.of(Fluids.LAVA), 3000, inner);
                items.extract(3, ItemResource.of(named), 7, inner);
                inner.commit();
            }
            helper.assertValueEqual(tank.amount(), 3000L, "lava in the tank inside the transaction");
        }
        helper.assertTrue(ItemStack.matches(slots.stack(0), new ItemStack(Items.COAL, 9)), "slot 0 restored");
        helper.assertTrue(ItemStack.matches(slots.stack(1), new ItemStack(Items.IRON_INGOT, 30)), "slot 1 restored");
        helper.assertTrue(ItemStack.matches(slots.stack(3), named), "named stack restored");
        helper.assertValueEqual(tank.resource(), water, "tank fluid restored");
        helper.assertValueEqual(tank.amount(), 1500L, "tank amount restored");
        helper.assertValueEqual(counter.slots[0] + counter.slots[1] + counter.slots[3] + counter.tank, 0,
                "changes reported for a rollback");
        helper.succeed();
    }

    /**
     * A hopper pushing into the block puts coal into the coal input and dirt into the open input; a hopper under it
     * pulls only from the output, never from the inputs or the display. No item is created or lost.
     */
    private static void hoppersFollowRoles(GameTestHelper helper) {
        BlockPos block = new BlockPos(2, 1, 1);
        BlockPos pusher = new BlockPos(1, 1, 1);
        BlockPos puller = new BlockPos(2, 0, 1);
        helper.setBlock(block, SLOTS_BLOCK.get());
        helper.setBlock(pusher, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
        helper.setBlock(puller, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
        SlotsEntity entity = helper.getBlockEntity(block, SlotsEntity.class);
        entity.slots.set(0, new ItemStack(Items.COAL, 5));
        entity.slots.set(1, new ItemStack(Items.IRON_INGOT, 3));
        entity.slots.set(2, new ItemStack(Items.DIAMOND));
        HopperBlockEntity push = helper.getBlockEntity(pusher, HopperBlockEntity.class);
        push.setItem(0, new ItemStack(Items.COAL, 2));
        push.setItem(1, new ItemStack(Items.DIRT, 1));
        helper.runAfterDelay(80, () -> {
            HopperBlockEntity pull = helper.getBlockEntity(puller, HopperBlockEntity.class);
            int iron = 0;
            int other = 0;
            for (int i = 0; i < pull.getContainerSize(); i++) {
                ItemStack stack = pull.getItem(i);
                if (stack.is(Items.IRON_INGOT)) {
                    iron += stack.getCount();
                } else {
                    other += stack.getCount();
                }
            }
            helper.assertValueEqual(iron, 3, "iron pulled from the output");
            helper.assertValueEqual(other, 0, "items pulled from the input or display");
            helper.assertTrue(entity.slots.stack(1).isEmpty(), "output not emptied");
            helper.assertTrue(ItemStack.matches(entity.slots.stack(0), new ItemStack(Items.COAL, 7)),
                    "coal input: " + entity.slots.stack(0));
            helper.assertTrue(entity.slots.stack(2).is(Items.DIAMOND), "display slot changed");
            helper.assertTrue(ItemStack.matches(entity.slots.stack(3), new ItemStack(Items.DIRT)),
                    "dirt not in the input for anything: " + entity.slots.stack(3));
            helper.assertTrue(push.isEmpty(), "items left in the pushing hopper");
            helper.succeed();
        });
    }

    /**
     * The fluid handler fills to capacity, refuses another fluid while holding one and what the filter refuses, and
     * drains only the fluid it holds. {@code ResourceTank.create} with the fluid kind gives a {@link FluidTank}.
     */
    private static void tankHandler(GameTestHelper helper) {
        Counter counter = new Counter();
        FluidTank tank = FluidTank.create(1000, key -> !key.fluid().isSame(Fluids.LAVA), () -> counter.tank++);
        ResourceHandler<FluidResource> handler = Handlers.fluids(tank);
        FluidResource water = FluidResource.of(Fluids.WATER);
        try (Transaction tx = Transaction.openRoot()) {
            helper.assertValueEqual(handler.insert(0, FluidResource.of(Fluids.LAVA), 100, tx), 0, "filter");
            helper.assertValueEqual(handler.insert(water, 1500, tx), 1000, "capacity");
            helper.assertValueEqual(handler.extract(0, FluidResource.of(Fluids.LAVA), 100, tx), 0, "wrong fluid");
            helper.assertValueEqual(handler.extract(0, water, 300, tx), 300, "drain");
            tx.commit();
        }
        helper.assertValueEqual(counter.tank, 1, "one change per commit");
        helper.assertValueEqual(tank.amount(), 700L, "amount");
        helper.assertTrue(handler.getResource(0).equals(water), "resource");
        helper.assertValueEqual(handler.getAmountAsLong(0), 700L, "handler amount");
        helper.assertValueEqual(tank.insert(FluidKey.of(Fluids.WATER), 500, false), 300L, "machine fill");
        ResourceTank<FluidKey> generic = ResourceTank.create(ResourceKinds.FLUID, 10, () -> {
        });
        helper.assertTrue(generic instanceof FluidTank, "a fluid-kind tank is not a FluidTank");
        helper.succeed();
    }

    /**
     * A saved tank round-trips; one whose fluid does not load reads as empty, takes nothing, and saves the unloaded
     * contents back unchanged until it is cleared.
     */
    private static void tankKeepsUnloaded(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        FluidTank tank = FluidTank.create(8000, () -> {
        });
        tank.set(FluidKey.of(Fluids.WATER), 2500);
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        tank.save(out);
        CompoundTag saved = out.buildResult();
        FluidTank loaded = FluidTank.create(8000, () -> {
        });
        loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, saved));
        helper.assertValueEqual(loaded.resource(), Optional.of(FluidKey.of(Fluids.WATER)), "round trip fluid");
        helper.assertValueEqual(loaded.amount(), 2500L, "round trip amount");

        CompoundTag missing = saved.copy();
        CompoundTag resource = missing.getCompoundOrEmpty("resource");
        resource.putString("id", "gone_mod:goo");
        missing.put("resource", resource);
        FluidTank unloaded = FluidTank.create(8000, () -> {
        });
        unloaded.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, missing));
        helper.assertValueEqual(unloaded.amount(), 0L, "unloaded amount");
        try (Transaction tx = Transaction.openRoot()) {
            helper.assertValueEqual(Handlers.fluids(unloaded).insert(0, FluidResource.of(Fluids.WATER), 100, tx), 0,
                    "unloaded tank took water");
            tx.commit();
        }
        TagValueOutput again = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        unloaded.save(again);
        helper.assertValueEqual(again.buildResult(), missing, "unloaded contents saved back");
        unloaded.clear();
        helper.assertValueEqual(unloaded.insert(FluidKey.of(Fluids.WATER), 100, false), 100L, "cleared tank fills");
        helper.succeed();
    }

    static final class SlotsBlock extends Block implements EntityBlock {
        static final MapCodec<SlotsBlock> CODEC = simpleCodec(SlotsBlock::new);

        SlotsBlock(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new SlotsEntity(pos, state);
        }
    }

    /** {@link #SLOTS} and a 4,000 mB tank; not saved. */
    static final class SlotsEntity extends BlockEntity {
        final ItemSlots slots = SLOTS.build(slot -> setChanged());
        final FluidTank tank = FluidTank.create(4000, this::setChanged);

        SlotsEntity(BlockPos pos, BlockState state) {
            super(SLOTS_ENTITY.get(), pos, state);
        }
    }
}
