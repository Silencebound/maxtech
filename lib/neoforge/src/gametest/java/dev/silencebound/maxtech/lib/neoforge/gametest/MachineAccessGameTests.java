package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.neoforge.api.inventory.Handlers;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * A machine's own transactional access ({@link Handlers#machineItems}, {@link Handlers#machineTank}): one step drains
 * inputs and fills outputs in one root transaction, all or nothing, past the slots' roles but within their filters and
 * limits, never touching what Lib keeps for a removed mod, reporting changes only on commit and saving exactly what was
 * committed.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class MachineAccessGameTests {
    private static final Identifier STRUCTURE = MaxtechLib.id("empty_7x2x3");
    private static final String[] NAMES = {"machine_access_step_rolls_back", "machine_access_partial_fit_refused",
            "machine_access_filters_and_limits", "machine_access_unknown_untouched",
            "machine_access_listeners_on_commit", "machine_access_no_dupe_across_save"};

    /**
     * Slot 0: input for coal, at most 16. Slot 1: input for anything. Slots 2 to 4: outputs, slot 4 at most 8. Slot 5:
     * display.
     */
    private static final ItemSlots.Builder MACHINE = ItemSlots.builder(6).input(0, 1, stack -> stack.is(Items.COAL))
            .limit(0, 1, 16).output(2, 5).limit(4, 5, 8).display(5, 6);

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        FUNCTIONS.register(NAMES[0], () -> MachineAccessGameTests::stepRollsBack);
        FUNCTIONS.register(NAMES[1], () -> MachineAccessGameTests::partialFitRefused);
        FUNCTIONS.register(NAMES[2], () -> MachineAccessGameTests::filtersAndLimits);
        FUNCTIONS.register(NAMES[3], () -> MachineAccessGameTests::unknownUntouched);
        FUNCTIONS.register(NAMES[4], () -> MachineAccessGameTests::listenersOnCommit);
        FUNCTIONS.register(NAMES[5], () -> MachineAccessGameTests::noDupeAcrossSave);
    }

    private MachineAccessGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("machine_access"));
        for (String name : NAMES) {
            event.registerTest(MaxtechLib.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechLib.id(name)),
                            new TestData<>(environment, STRUCTURE, 100, 0, true)));
        }
    }

    /** Counts change events per slot and for the tank. */
    private static final class Counter {
        final int[] slots = new int[6];
        int tank;

        int total() {
            int sum = tank;
            for (int count : slots) {
                sum += count;
            }
            return sum;
        }
    }

    /**
     * A step drains coal and water, then finds too little output room: it does not commit, and every slot and the tank
     * are as before, nothing reported. A short output inside a root that commits anyway rolls back only the output.
     * With room the step commits whole. The views are the same instance on each call.
     */
    @SuppressWarnings("ReferenceEquality")
    private static void stepRollsBack(GameTestHelper helper) {
        Counter counter = new Counter();
        ItemSlots slots = MACHINE.build(slot -> counter.slots[slot]++);
        FluidTank tank = FluidTank.create(4000, () -> counter.tank++);
        slots.set(0, new ItemStack(Items.COAL, 8));
        slots.set(2, new ItemStack(Items.IRON_INGOT, 60));
        slots.set(3, new ItemStack(Items.GOLD_INGOT, 64));
        slots.set(4, new ItemStack(Items.IRON_INGOT, 8));
        tank.set(FluidKey.of(Fluids.WATER), 1000);
        Arrays.fill(counter.slots, 0);
        counter.tank = 0;
        ResourceHandler<ItemResource> items = Handlers.machineItems(slots);
        Handlers.MachineTank<FluidKey> water = Handlers.machineTank(tank);
        helper.assertTrue(items == Handlers.machineItems(slots), "a new item handler on each call");
        helper.assertTrue(water == Handlers.machineTank(tank), "a new tank view on each call");
        List<ItemStack> coal = List.of(new ItemStack(Items.COAL, 4));
        List<ItemStack> tenIron = List.of(new ItemStack(Items.IRON_INGOT, 10));

        try (Transaction step = Transaction.openRoot()) {
            helper.assertTrue(Handlers.extractAll(items, 0, 2, coal, step), "coal drained");
            helper.assertTrue(water.extractExactly(FluidKey.of(Fluids.WATER), 500, step), "water drained");
            helper.assertFalse(Handlers.insertAll(items, 2, 5, tenIron, step), "10 iron fit into 4 of room");
        }
        assertStack(helper, slots, 0, new ItemStack(Items.COAL, 8), "coal after the rollback");
        assertStack(helper, slots, 2, new ItemStack(Items.IRON_INGOT, 60), "iron after the rollback");
        helper.assertValueEqual(tank.amount(), 1000L, "water after the rollback");
        helper.assertValueEqual(counter.total(), 0, "changes reported for a rollback");

        try (Transaction step = Transaction.openRoot()) {
            helper.assertTrue(Handlers.extractAll(items, 0, 2, coal, step), "coal drained");
            helper.assertFalse(Handlers.insertAll(items, 2, 5, tenIron, step), "10 iron fit");
            assertStack(helper, slots, 2, new ItemStack(Items.IRON_INGOT, 60), "short insert left iron in");
            step.commit();
        }
        assertStack(helper, slots, 0, new ItemStack(Items.COAL, 4), "drain of a committed root");
        helper.assertValueEqual(counter.slots[2], 0, "output reported after its nested rollback");

        try (Transaction step = Transaction.openRoot()) {
            helper.assertTrue(Handlers.extractAll(items, 0, 2, coal, step), "coal drained");
            helper.assertTrue(water.extractExactly(FluidKey.of(Fluids.WATER), 500, step), "water drained");
            helper.assertTrue(Handlers.insertAll(items, 2, 5, List.of(new ItemStack(Items.IRON_INGOT, 4)), step),
                    "4 iron into 4 of room");
            step.commit();
        }
        helper.assertTrue(slots.stack(0).isEmpty(), "coal left");
        assertStack(helper, slots, 2, new ItemStack(Items.IRON_INGOT, 64), "iron output");
        helper.assertValueEqual(tank.amount(), 500L, "water left");
        helper.succeed();
    }

    /**
     * A stack that fits only in part is refused with nothing moved, and so is a list whose last stack does not fit
     * after the first did: inside the open transaction the slots are already as before.
     */
    private static void partialFitRefused(GameTestHelper helper) {
        ItemSlots slots = MACHINE.build(slot -> {
        });
        slots.set(2, new ItemStack(Items.IRON_INGOT, 54));
        slots.set(3, new ItemStack(Items.GOLD_INGOT, 64));
        ResourceHandler<ItemResource> items = Handlers.machineItems(slots);
        try (Transaction tx = Transaction.openRoot()) {
            helper.assertFalse(Handlers.insertAll(items, 2, 5, List.of(new ItemStack(Items.IRON_INGOT, 19)), tx),
                    "19 iron into 18 of room");
            assertStack(helper, slots, 2, new ItemStack(Items.IRON_INGOT, 54), "part of the iron went in");
            helper.assertTrue(slots.stack(4).isEmpty(), "part of the iron went into the empty output");
            helper.assertFalse(
                    Handlers.insertAll(items, 2, 5,
                            List.of(new ItemStack(Items.IRON_INGOT, 18), new ItemStack(Items.DIAMOND)), tx),
                    "a diamond after 18 iron");
            assertStack(helper, slots, 2, new ItemStack(Items.IRON_INGOT, 54), "the iron before the diamond stayed");
            helper.assertFalse(
                    Handlers.extractAll(items, 2, 5,
                            List.of(new ItemStack(Items.GOLD_INGOT, 64), new ItemStack(Items.IRON_INGOT, 55)), tx),
                    "55 iron out of 54");
            assertStack(helper, slots, 3, new ItemStack(Items.GOLD_INGOT, 64), "the gold before the iron was taken");
            helper.assertTrue(Handlers.insertAll(items, 2, 5, List.of(new ItemStack(Items.IRON_INGOT, 18)), tx),
                    "18 iron into 18 of room");
            assertStack(helper, slots, 2, new ItemStack(Items.IRON_INGOT, 64), "iron stacked first");
            assertStack(helper, slots, 4, new ItemStack(Items.IRON_INGOT, 8), "then into the empty output");
            tx.commit();
        }
        helper.succeed();
    }

    /**
     * The machine's handler writes outputs (automation's does not) but keeps filters, slot limits and item stack sizes;
     * components must match for stacking and draining.
     */
    private static void filtersAndLimits(GameTestHelper helper) {
        ItemSlots slots = MACHINE.build(slot -> {
        });
        ResourceHandler<ItemResource> items = Handlers.machineItems(slots);
        ItemStack named = new ItemStack(Items.STICK, 3);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Kept"));
        try (Transaction tx = Transaction.openRoot()) {
            helper.assertValueEqual(Handlers.items(slots).insert(2, ItemResource.of(Items.IRON_INGOT), 1, tx), 0,
                    "automation wrote an output");
            helper.assertFalse(Handlers.insertAll(items, 0, 1, List.of(new ItemStack(Items.DIRT)), tx),
                    "coal input took dirt");
            helper.assertFalse(Handlers.insertAll(items, 0, 1, List.of(new ItemStack(Items.COAL, 17)), tx),
                    "17 coal into a slot of 16");
            helper.assertTrue(Handlers.insertAll(items, 0, 1, List.of(new ItemStack(Items.COAL, 16)), tx), "16 coal");
            helper.assertFalse(Handlers.insertAll(items, 4, 5, List.of(new ItemStack(Items.IRON_INGOT, 9)), tx),
                    "9 iron into an output of 8");
            helper.assertFalse(Handlers.insertAll(items, 1, 2, List.of(new ItemStack(Items.ENDER_PEARL, 17)), tx),
                    "17 ender pearls in one slot");
            helper.assertTrue(Handlers.insertAll(items, 1, 2, List.of(new ItemStack(Items.ENDER_PEARL, 16)), tx),
                    "16 ender pearls");
            helper.assertTrue(Handlers.insertAll(items, 2, 3, List.of(named), tx), "named sticks");
            helper.assertFalse(Handlers.insertAll(items, 2, 3, List.of(new ItemStack(Items.STICK)), tx),
                    "a plain stick stacked onto named ones");
            helper.assertFalse(Handlers.extractAll(items, 2, 3, List.of(new ItemStack(Items.STICK)), tx),
                    "a plain stick drained from named ones");
            helper.assertTrue(Handlers.insertAll(items, 5, 6, List.of(new ItemStack(Items.DIAMOND)), tx),
                    "the machine's display slot");
            tx.commit();
        }
        assertStack(helper, slots, 0, new ItemStack(Items.COAL, 16), "coal");
        assertStack(helper, slots, 1, new ItemStack(Items.ENDER_PEARL, 16), "ender pearls");
        assertStack(helper, slots, 2, named, "named sticks");
        assertThrows(helper, () -> {
            try (Transaction tx = Transaction.openRoot()) {
                Handlers.insertAll(items, 4, 7, List.of(), tx);
            }
        }, "a range past the slots");
        helper.succeed();
    }

    /**
     * An output slot holding an item that no longer loads is skipped and saved back unchanged; the handler exposes only
     * the slots, not overflow, which stays as it was; an unknown tank takes and gives nothing.
     */
    private static void unknownUntouched(GameTestHelper helper) {
        ItemSlots original = MACHINE.build(slot -> {
        });
        original.set(2, new ItemStack(Items.IRON_INGOT, 10));
        original.set(3, new ItemStack(Items.DIAMOND));
        CompoundTag saved = save(helper, original::save);
        ListTag entries = saved.getListOrEmpty("items");
        CompoundTag gone = entries.getCompoundOrEmpty(0);
        helper.assertValueEqual(gone.getIntOr("slot", -1), 2, "first entry slot");
        gone.getCompoundOrEmpty("item").putString("id", "gone_mod:widget");
        CompoundTag goneEntry = gone.copy();

        ItemSlots slots = MACHINE.build(slot -> {
        });
        slots.load(input(helper, saved));
        helper.assertTrue(slots.unknown(2), "slot 2 not unknown");
        ResourceHandler<ItemResource> items = Handlers.machineItems(slots);
        try (Transaction tx = Transaction.openRoot()) {
            helper.assertFalse(Handlers.insertAll(items, 2, 3, List.of(new ItemStack(Items.IRON_INGOT)), tx),
                    "unknown slot took iron");
            helper.assertFalse(Handlers.insertAll(items, 2, 5, List.of(new ItemStack(Items.IRON_INGOT, 9)), tx),
                    "9 iron with the unknown slot counted as room");
            helper.assertTrue(Handlers.insertAll(items, 2, 5, List.of(new ItemStack(Items.IRON_INGOT, 8)), tx),
                    "8 iron into the free output");
            helper.assertFalse(Handlers.extractAll(items, 2, 3, List.of(new ItemStack(Items.IRON_INGOT)), tx),
                    "drained the unknown slot");
            tx.commit();
        }
        assertStack(helper, slots, 4, new ItemStack(Items.IRON_INGOT, 8), "iron went elsewhere");
        CompoundTag again = save(helper, slots::save);
        helper.assertValueEqual(again.getListOrEmpty("items").getCompoundOrEmpty(0), goneEntry,
                "unknown item not saved back unchanged");

        ItemSlots small = ItemSlots.builder(2).build(slot -> {
        });
        small.load(input(helper, save(helper, original::save)));
        helper.assertValueEqual(small.overflow().size(), 2, "overflow before");
        ResourceHandler<ItemResource> smallItems = Handlers.machineItems(small);
        helper.assertValueEqual(smallItems.size(), 2, "handler slots (overflow exposed)");
        try (Transaction tx = Transaction.openRoot()) {
            helper.assertTrue(
                    Handlers.insertAll(smallItems, 0, 2,
                            List.of(new ItemStack(Items.DIRT, 64), new ItemStack(Items.STONE, 64)), tx),
                    "fill both slots");
            tx.commit();
        }
        helper.assertValueEqual(small.overflow().size(), 2, "overflow after");
        helper.assertTrue(ItemStack.matches(small.overflow().get(0), new ItemStack(Items.IRON_INGOT, 10)),
                "overflow iron");
        helper.assertTrue(ItemStack.matches(small.overflow().get(1), new ItemStack(Items.DIAMOND)), "overflow diamond");

        FluidTank tank = FluidTank.create(4000, () -> {
        });
        tank.set(FluidKey.of(Fluids.WATER), 2000);
        CompoundTag tankSave = save(helper, tank::save);
        CompoundTag resource = tankSave.getCompoundOrEmpty("resource");
        resource.putString("id", "gone_mod:goo");
        tankSave.put("resource", resource);
        FluidTank unknown = FluidTank.create(4000, () -> {
        });
        unknown.load(input(helper, tankSave));
        Handlers.MachineTank<FluidKey> view = Handlers.machineTank(unknown);
        try (Transaction tx = Transaction.openRoot()) {
            helper.assertValueEqual(view.insert(FluidKey.of(Fluids.WATER), 100, tx), 0L, "unknown tank took water");
            helper.assertFalse(view.extractExactly(FluidKey.of(Fluids.WATER), 1, tx), "unknown tank gave water");
            tx.commit();
        }
        helper.assertValueEqual(save(helper, unknown::save), tankSave, "unknown tank not saved back unchanged");
        helper.succeed();
    }

    /**
     * A committed inner transaction under a rolled-back root reports nothing; a committed root reports once per touched
     * slot and once for the tank, after the commit only.
     */
    private static void listenersOnCommit(GameTestHelper helper) {
        Counter counter = new Counter();
        ItemSlots slots = MACHINE.build(slot -> counter.slots[slot]++);
        FluidTank tank = FluidTank.create(4000, () -> counter.tank++);
        ResourceHandler<ItemResource> items = Handlers.machineItems(slots);
        Handlers.MachineTank<FluidKey> lava = Handlers.machineTank(tank);
        try (Transaction root = Transaction.openRoot()) {
            try (Transaction inner = Transaction.open(root)) {
                helper.assertTrue(Handlers.insertAll(items, 2, 5, List.of(new ItemStack(Items.IRON_INGOT)), inner),
                        "iron");
                helper.assertTrue(lava.insertExactly(FluidKey.of(Fluids.LAVA), 1000, inner), "lava");
                inner.commit();
            }
        }
        helper.assertValueEqual(counter.total(), 0, "changes reported for a rolled-back root");
        try (Transaction root = Transaction.openRoot()) {
            helper.assertTrue(
                    Handlers.insertAll(items, 0, 5,
                            List.of(new ItemStack(Items.COAL, 3), new ItemStack(Items.IRON_INGOT, 70)), root),
                    "coal, iron");
            helper.assertTrue(Handlers.insertAll(items, 0, 1, List.of(new ItemStack(Items.COAL, 2)), root),
                    "more coal");
            helper.assertTrue(lava.insertExactly(FluidKey.of(Fluids.LAVA), 1000, root), "lava");
            helper.assertValueEqual(counter.total(), 0, "reported before the commit");
            root.commit();
        }
        helper.assertValueEqual(counter.slots[0], 1, "coal slot changes");
        helper.assertValueEqual(counter.slots[1], 1, "slot 1 changes (64 iron)");
        helper.assertValueEqual(counter.slots[2], 1, "slot 2 changes (6 iron)");
        helper.assertValueEqual(counter.slots[3] + counter.slots[4] + counter.slots[5], 0, "untouched slots");
        helper.assertValueEqual(counter.tank, 1, "tank changes");
        helper.succeed();
    }

    /**
     * A committed step saves and loads back exactly what the slots and tank hold; a rolled-back step leaves the save as
     * it was before the step. Item totals stay what the step moved.
     */
    private static void noDupeAcrossSave(GameTestHelper helper) {
        ItemSlots slots = MACHINE.build(slot -> {
        });
        FluidTank tank = FluidTank.create(4000, () -> {
        });
        slots.set(0, new ItemStack(Items.COAL, 10));
        tank.set(FluidKey.of(Fluids.WATER), 3000);
        CompoundTag before = save(helper, slots::save);
        CompoundTag tankBefore = save(helper, tank::save);
        ResourceHandler<ItemResource> items = Handlers.machineItems(slots);
        try (Transaction step = Transaction.openRoot()) {
            helper.assertTrue(Handlers.extractAll(items, 0, 2, List.of(new ItemStack(Items.COAL, 10)), step),
                    "coal in the rolled-back step");
            helper.assertTrue(Handlers.machineTank(tank).extractExactly(FluidKey.of(Fluids.WATER), 3000, step),
                    "water in the rolled-back step");
            helper.assertTrue(Handlers.insertAll(items, 2, 5, List.of(new ItemStack(Items.DIAMOND, 5)), step),
                    "diamonds in the rolled-back step");
        }
        helper.assertValueEqual(save(helper, slots::save), before, "save after a rolled-back step");
        helper.assertValueEqual(save(helper, tank::save), tankBefore, "tank save after a rolled-back step");

        try (Transaction step = Transaction.openRoot()) {
            helper.assertTrue(Handlers.extractAll(items, 0, 2, List.of(new ItemStack(Items.COAL, 4)), step), "coal");
            helper.assertTrue(Handlers.machineTank(tank).extractExactly(FluidKey.of(Fluids.WATER), 1000, step),
                    "water");
            helper.assertTrue(Handlers.insertAll(items, 2, 5, List.of(new ItemStack(Items.DIAMOND, 70)), step),
                    "diamonds");
            step.commit();
        }
        ItemSlots loaded = MACHINE.build(slot -> {
        });
        loaded.load(input(helper, save(helper, slots::save)));
        FluidTank loadedTank = FluidTank.create(4000, () -> {
        });
        loadedTank.load(input(helper, save(helper, tank::save)));
        int coal = 0;
        int diamonds = 0;
        for (int i = 0; i < loaded.size(); i++) {
            helper.assertTrue(ItemStack.matches(loaded.stack(i), slots.stack(i)), "slot " + i + " after loading");
            ItemStack stack = loaded.stack(i);
            coal += stack.is(Items.COAL) ? stack.getCount() : 0;
            diamonds += stack.is(Items.DIAMOND) ? stack.getCount() : 0;
        }
        helper.assertValueEqual(coal, 6, "coal after loading");
        helper.assertValueEqual(diamonds, 70, "diamonds after loading");
        helper.assertTrue(loaded.overflow().isEmpty(), "overflow after loading");
        helper.assertValueEqual(loadedTank.amount(), 2000L, "water after loading");
        helper.succeed();
    }

    private static void assertStack(GameTestHelper helper, ItemSlots slots, int slot, ItemStack expected,
            String message) {
        helper.assertTrue(ItemStack.matches(slots.stack(slot), expected), message + ": " + slots.stack(slot));
    }

    private static void assertThrows(GameTestHelper helper, Runnable action, String message) {
        try {
            action.run();
        } catch (IndexOutOfBoundsException expected) {
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
}
