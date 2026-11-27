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
import it.unimi.dsi.fastutil.ints.IntArrayList;
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
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
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
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
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
            "inventory_hoppers_follow_roles", "inventory_tank_handler", "inventory_tank_keeps_unloaded",
            "inventory_slots_round_trip", "inventory_slots_keep_unknown", "inventory_slots_keep_overflow",
            "inventory_tank_keeps_overfull", "inventory_output_comparator", "inventory_no_set_in_transaction",
            "inventory_filter_reuses_stack", "inventory_slots_drop_contents", "inventory_slots_keep_unknown_component",
            "inventory_tank_keeps_unknown_component"};

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
        FUNCTIONS.register(NAMES[5], () -> InventoryGameTests::slotsRoundTrip);
        FUNCTIONS.register(NAMES[6], () -> InventoryGameTests::slotsKeepUnknown);
        FUNCTIONS.register(NAMES[7], () -> InventoryGameTests::slotsKeepOverflow);
        FUNCTIONS.register(NAMES[8], () -> InventoryGameTests::tankKeepsOverfull);
        FUNCTIONS.register(NAMES[9], () -> InventoryGameTests::outputComparator);
        FUNCTIONS.register(NAMES[10], () -> InventoryGameTests::noSetInTransaction);
        FUNCTIONS.register(NAMES[11], () -> InventoryGameTests::filterReusesStack);
        FUNCTIONS.register(NAMES[12], () -> InventoryGameTests::slotsDropContents);
        FUNCTIONS.register(NAMES[13], () -> InventoryGameTests::slotsKeepUnknownComponent);
        FUNCTIONS.register(NAMES[14], () -> InventoryGameTests::tankKeepsUnknownComponent);
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
     * nothing still report their capacity but refuse. The machine's own methods ignore roles. Changes are reported once
     * per commit.
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
        helper.assertTrue(handler.getCapacityAsLong(1, ItemResource.EMPTY) > 0, "output capacity");
        helper.assertTrue(handler.getCapacityAsLong(2, ItemResource.EMPTY) > 0, "display capacity");
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
     * A saved tank round-trips; one whose fluid does not load is unknown, reads as empty, takes nothing, refuses
     * {@code set} and {@code clear}, and saves the unloaded contents back unchanged until they are discarded.
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
        helper.assertTrue(unloaded.unknown(), "unloaded tank not unknown");
        helper.assertFalse(loaded.unknown(), "loaded tank unknown");
        assertThrows(helper, IllegalStateException.class, unloaded::clear, "clear wiped unknown contents");
        assertThrows(helper, IllegalStateException.class, () -> unloaded.set(FluidKey.of(Fluids.WATER), 5),
                "set wiped unknown contents");
        unloaded.discardUnknown();
        helper.assertFalse(unloaded.unknown(), "discarded tank still unknown");
        helper.assertValueEqual(unloaded.insert(FluidKey.of(Fluids.WATER), 100, false), 100L, "discarded tank fills");
        helper.succeed();
    }

    private static void assertThrows(GameTestHelper helper, Class<? extends Throwable> type, Runnable action,
            String message) {
        try {
            action.run();
        } catch (Throwable thrown) {
            helper.assertTrue(type.isInstance(thrown), message + ": threw " + thrown);
            return;
        }
        helper.fail(message);
    }

    private static CompoundTag save(GameTestHelper helper, Consumer<ValueOutput> saver) {
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
                helper.getLevel().registryAccess());
        saver.accept(out);
        return out.buildResult();
    }

    private static ValueInput input(GameTestHelper helper, CompoundTag tag) {
        return TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), tag);
    }

    /** Slots 0 to 3 filled (one stack with components) save and load back exactly, roles aside. */
    private static ItemSlots filled() {
        ItemSlots slots = SLOTS.build(slot -> {
        });
        ItemStack named = new ItemStack(Items.STICK, 7);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Kept"));
        slots.set(0, new ItemStack(Items.COAL, 9));
        slots.set(1, new ItemStack(Items.IRON_INGOT, 30));
        slots.set(2, new ItemStack(Items.DIAMOND));
        slots.set(3, named);
        return slots;
    }

    /** Every slot, components included, survives a save and load; empty slots stay empty. */
    private static void slotsRoundTrip(GameTestHelper helper) {
        ItemSlots slots = filled();
        slots.set(2, ItemStack.EMPTY);
        ItemSlots loaded = SLOTS.build(slot -> {
        });
        loaded.set(2, new ItemStack(Items.DIRT));
        loaded.load(input(helper, save(helper, slots::save)));
        for (int i = 0; i < 4; i++) {
            helper.assertTrue(ItemStack.matches(loaded.stack(i), slots.stack(i)),
                    "slot " + i + ": " + loaded.stack(i) + " instead of " + slots.stack(i));
        }
        helper.succeed();
    }

    /**
     * A saved stack in the middle whose item no longer loads stays in its slot without moving the others, is hidden
     * from automation, refuses {@code set}, and saves back unchanged through any number of loads.
     */
    private static void slotsKeepUnknown(GameTestHelper helper) {
        ItemSlots slots = filled();
        CompoundTag saved = save(helper, slots::save);
        ListTag items = saved.getListOrEmpty("items");
        CompoundTag entry = items.getCompoundOrEmpty(1);
        helper.assertValueEqual(entry.getIntOr("slot", -1), 1, "second entry slot");
        CompoundTag item = entry.getCompoundOrEmpty("item");
        item.putString("id", "gone_mod:widget");
        CompoundTag missing = saved.copy();

        ItemSlots loaded = SLOTS.build(slot -> {
        });
        loaded.load(input(helper, missing));
        helper.assertTrue(loaded.unknown(1), "slot 1 not unknown");
        helper.assertTrue(loaded.stack(1).isEmpty(), "unknown slot shows a stack");
        helper.assertTrue(ItemStack.matches(loaded.stack(0), slots.stack(0)), "slot 0 moved");
        helper.assertTrue(ItemStack.matches(loaded.stack(2), slots.stack(2)), "slot 2 moved: " + loaded.stack(2));
        helper.assertTrue(ItemStack.matches(loaded.stack(3), slots.stack(3)), "slot 3 moved: " + loaded.stack(3));
        helper.assertValueEqual(loaded.insert(1, new ItemStack(Items.IRON_INGOT), false), 0, "unknown slot took");
        ResourceHandler<ItemResource> handler = Handlers.items(loaded);
        helper.assertValueEqual(handler.getCapacityAsLong(1, ItemResource.of(Items.IRON_INGOT)), 0L,
                "unknown slot capacity");
        assertThrows(helper, IllegalStateException.class, () -> loaded.set(1, new ItemStack(Items.DIRT)),
                "set wiped an unknown item");

        CompoundTag again = save(helper, loaded::save);
        helper.assertValueEqual(again, missing, "unknown item not saved back unchanged");
        ItemSlots twice = SLOTS.build(slot -> {
        });
        twice.load(input(helper, again));
        helper.assertValueEqual(save(helper, twice::save), missing, "second cycle changed the save");
        twice.discardUnknown(1);
        helper.assertFalse(twice.unknown(1), "discarded slot still unknown");
        helper.assertValueEqual(twice.insert(1, new ItemStack(Items.IRON_INGOT), false), 1, "discarded slot");
        helper.succeed();
    }

    /**
     * Loading a save of four slots into two keeps the two extra stacks as overflow: not seen by automation, saved back,
     * and handed out by {@code takeOverflow}.
     */
    private static void slotsKeepOverflow(GameTestHelper helper) {
        ItemSlots slots = filled();
        CompoundTag saved = save(helper, slots::save);
        ItemSlots.Builder small = ItemSlots.builder(2);
        IntArrayList changes = new IntArrayList();
        ItemSlots loaded = small.build(changes::add);
        loaded.load(input(helper, saved));
        helper.assertTrue(ItemStack.matches(loaded.stack(0), slots.stack(0)), "slot 0");
        helper.assertValueEqual(loaded.overflow().size(), 2, "overflow size");
        helper.assertTrue(ItemStack.matches(loaded.overflow().get(0), slots.stack(2)), "overflow 0");
        helper.assertTrue(ItemStack.matches(loaded.overflow().get(1), slots.stack(3)), "overflow 1");
        helper.assertValueEqual(Handlers.items(loaded).size(), 2, "automation sees overflow");

        ItemSlots back = SLOTS.build(slot -> {
        });
        back.load(input(helper, save(helper, loaded::save)));
        for (int i = 0; i < 4; i++) {
            helper.assertTrue(ItemStack.matches(back.stack(i), slots.stack(i)), "slot " + i + " after growing back");
        }
        ItemStack taken = loaded.takeOverflow(0);
        helper.assertTrue(ItemStack.matches(taken, slots.stack(2)), "taken overflow");
        helper.assertValueEqual(loaded.overflow().size(), 1, "overflow after take");
        helper.assertValueEqual(changes, IntArrayList.of(-1), "taking overflow did not report a change");
        ItemSlots reloaded = small.build(slot -> {
        });
        reloaded.load(input(helper, save(helper, loaded::save)));
        helper.assertValueEqual(reloaded.overflow().size(), 1, "overflow saved");
        helper.succeed();
    }

    /**
     * A save holding more than the tank's capacity loads whole: the tank takes nothing until drained below its
     * capacity, and saves the full amount back.
     */
    private static void tankKeepsOverfull(GameTestHelper helper) {
        FluidTank big = FluidTank.create(8000, () -> {
        });
        big.set(FluidKey.of(Fluids.WATER), 6000);
        FluidTank small = FluidTank.create(4000, () -> {
        });
        small.load(input(helper, save(helper, big::save)));
        helper.assertValueEqual(small.amount(), 6000L, "overfull amount");
        helper.assertValueEqual(small.insert(FluidKey.of(Fluids.WATER), 100, false), 0L, "overfull tank took");
        try (Transaction tx = Transaction.openRoot()) {
            helper.assertValueEqual(Handlers.fluids(small).insert(0, FluidResource.of(Fluids.WATER), 100, tx), 0,
                    "overfull handler took");
            tx.commit();
        }
        FluidTank again = FluidTank.create(4000, () -> {
        });
        again.load(input(helper, save(helper, small::save)));
        helper.assertValueEqual(again.amount(), 6000L, "overfull amount saved back");
        helper.assertValueEqual(small.extract(FluidKey.of(Fluids.WATER), 3000, false), 3000L, "drain");
        helper.assertValueEqual(small.insert(FluidKey.of(Fluids.WATER), 5000, false), 1000L, "fills to capacity");
        helper.succeed();
    }

    /** A full output slot reads as full to a comparator through the automation handler. */
    private static void outputComparator(GameTestHelper helper) {
        ItemSlots slots = SLOTS.build(slot -> {
        });
        slots.set(1, new ItemStack(Items.IRON_INGOT, 64));
        helper.assertValueEqual(ResourceHandlerUtil.getRedstoneSignalFromResourceHandler(Handlers.items(slots)), 15,
                "comparator signal of a full output");
        helper.succeed();
    }

    /** The machine's direct changes refuse to run inside a transfer transaction. */
    private static void noSetInTransaction(GameTestHelper helper) {
        ItemSlots slots = SLOTS.build(slot -> {
        });
        FluidTank tank = FluidTank.create(1000, () -> {
        });
        try (Transaction tx = Transaction.openRoot()) {
            assertThrows(helper, IllegalStateException.class, () -> slots.set(0, new ItemStack(Items.COAL)),
                    "slot set in a transaction");
            assertThrows(helper, IllegalStateException.class, () -> tank.set(FluidKey.of(Fluids.WATER), 10),
                    "tank set in a transaction");
            assertThrows(helper, IllegalStateException.class, tank::clear, "tank clear in a transaction");
        }
        helper.assertTrue(slots.stack(0).isEmpty(), "slot changed");
        helper.assertValueEqual(tank.amount(), 0L, "tank changed");
        helper.succeed();
    }

    /** Asking a filtered slot about the same item again reuses the stack the filter got (no allocation per poll). */
    private static void filterReusesStack(GameTestHelper helper) {
        ItemStack[] seen = new ItemStack[2];
        int[] calls = {0};
        ItemSlots slots = ItemSlots.builder(1).input(0, 1, stack -> {
            seen[Math.min(calls[0]++, 1)] = stack;
            return true;
        }).build(slot -> {
        });
        ResourceHandler<ItemResource> handler = Handlers.items(slots);
        handler.isValid(0, ItemResource.of(Items.COAL));
        handler.isValid(0, ItemResource.of(Items.COAL));
        helper.assertValueEqual(calls[0], 2, "filter calls");
        helper.assertTrue(seen[0] == seen[1], "a new stack for each poll");
        helper.succeed();
    }

    /**
     * Breaking a block that drops its slots from {@code preRemoveSideEffects} drops every stack and the overflow, and
     * reports the change; an unknown item is not dropped.
     */
    private static void slotsDropContents(GameTestHelper helper) {
        ItemSlots six = ItemSlots.builder(6).build(slot -> {
        });
        six.set(0, new ItemStack(Items.COAL, 9));
        six.set(3, new ItemStack(Items.STICK, 5));
        six.set(4, new ItemStack(Items.DIAMOND, 2));
        six.set(5, new ItemStack(Items.EMERALD, 3));
        CompoundTag saved = save(helper, six::save);
        CompoundTag item = saved.getListOrEmpty("items").getCompoundOrEmpty(1).getCompoundOrEmpty("item");
        item.putString("id", "gone_mod:widget");

        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, SLOTS_BLOCK.get());
        SlotsEntity entity = helper.getBlockEntity(pos, SlotsEntity.class);
        entity.slots.load(input(helper, saved));
        helper.assertTrue(entity.slots.unknown(3), "slot 3 not unknown");
        helper.assertValueEqual(entity.slots.overflow().size(), 2, "overflow before breaking");
        helper.destroyBlock(pos);
        helper.assertValueEqual(dropped(helper, pos, Items.COAL), 9, "dropped coal");
        helper.assertValueEqual(dropped(helper, pos, Items.DIAMOND), 2, "dropped overflow diamonds");
        helper.assertValueEqual(dropped(helper, pos, Items.EMERALD), 3, "dropped overflow emeralds");
        helper.assertValueEqual(dropped(helper, pos, Items.STICK), 0, "dropped the unknown item");
        helper.assertTrue(entity.slots.overflow().isEmpty(), "overflow kept after dropping");
        helper.assertTrue(entity.slots.stack(0).isEmpty(), "slot kept after dropping");
        helper.assertValueEqual(entity.changes, IntArrayList.of(0, -1), "changes reported by dropping");
        helper.succeed();
    }

    private static int dropped(GameTestHelper helper, BlockPos pos, Item item) {
        int count = 0;
        for (ItemEntity entity : helper.getEntities(EntityType.ITEM, pos, 2)) {
            if (entity.getItem().is(item)) {
                count += entity.getItem().getCount();
            }
        }
        return count;
    }

    /**
     * A vanilla item carrying a component of a removed mod does not load without it: the slot is unknown and saves the
     * entry back exactly as it was.
     */
    private static void slotsKeepUnknownComponent(GameTestHelper helper) {
        ItemSlots slots = filled();
        CompoundTag saved = save(helper, slots::save);
        CompoundTag entry = saved.getListOrEmpty("items").getCompoundOrEmpty(0);
        helper.assertValueEqual(entry.getIntOr("slot", -1), 0, "first entry slot");
        CompoundTag item = entry.getCompoundOrEmpty("item");
        CompoundTag components = new CompoundTag();
        components.putInt("gone_mod:charge", 5);
        item.put("components", components);

        ItemSlots loaded = SLOTS.build(slot -> {
        });
        loaded.load(input(helper, saved));
        helper.assertTrue(loaded.unknown(0), "an item with an unknown component loaded: " + loaded.stack(0));
        helper.assertTrue(ItemStack.matches(loaded.stack(1), slots.stack(1)), "slot 1 moved");
        helper.assertValueEqual(save(helper, loaded::save), saved, "unknown component not saved back unchanged");
        helper.succeed();
    }

    /**
     * A fluid carrying a component of a removed mod does not load without it: the tank is unknown and saves its
     * contents back exactly as they were.
     */
    private static void tankKeepsUnknownComponent(GameTestHelper helper) {
        FluidTank tank = FluidTank.create(8000, () -> {
        });
        tank.set(FluidKey.of(Fluids.WATER), 2500);
        CompoundTag saved = save(helper, tank::save);
        CompoundTag resource = saved.getCompoundOrEmpty("resource");
        CompoundTag components = new CompoundTag();
        components.putInt("gone_mod:charge", 5);
        resource.put("components", components);

        FluidTank loaded = FluidTank.create(8000, () -> {
        });
        loaded.load(input(helper, saved));
        helper.assertTrue(loaded.unknown(), "a fluid with an unknown component loaded: " + loaded.resource());
        helper.assertValueEqual(loaded.amount(), 0L, "unknown tank amount");
        helper.assertValueEqual(save(helper, loaded::save), saved, "unknown component not saved back unchanged");
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

    /** {@link #SLOTS} and a 4,000 mB tank; not saved. Drops its slots when broken and records their changes. */
    static final class SlotsEntity extends BlockEntity {
        final IntArrayList changes = new IntArrayList();
        final ItemSlots slots = SLOTS.build(slot -> {
            changes.add(slot);
            setChanged();
        });
        final FluidTank tank = FluidTank.create(4000, this::setChanged);

        SlotsEntity(BlockPos pos, BlockState state) {
            super(SLOTS_ENTITY.get(), pos, state);
        }

        @Override
        public void preRemoveSideEffects(BlockPos pos, BlockState state) {
            if (level != null) {
                slots.dropContents(level, pos);
            }
        }
    }
}
