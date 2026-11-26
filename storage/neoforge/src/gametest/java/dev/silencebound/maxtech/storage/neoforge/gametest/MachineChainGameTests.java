package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.machine.ProcessingMachineBlockEntity;
import dev.silencebound.maxtech.storage.machine.StorageMachineRecipe;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrafterBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The first production line end to end, from lumen quartz and vanilla items to a basic logic processor, through the
 * loaded recipes only: the crafting and furnace steps by recipe lookup (as a Crafter or furnace would), the machine
 * steps in real machines. Also every loaded machine recipe survives its JSON and network codecs, and recipes that do
 * not fit their machine are refused.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class MachineChainGameTests {
    private static final BlockPos LITHOGRAPH = new BlockPos(1, 1, 1);
    private static final BlockPos FABRICATOR = new BlockPos(3, 1, 1);
    private static final BlockPos ASSEMBLER = new BlockPos(5, 1, 1);

    static {
        StorageTests.function("machine_chain_to_logic_processor", MachineChainGameTests::chain);
        StorageTests.function("machine_recipes_round_trip", MachineChainGameTests::roundTrip);
    }

    private MachineChainGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("machine_chain"));
        StorageTests.test(event, environment, "machine_chain_to_logic_processor", StorageTests.SMALL, 3000);
        StorageTests.test(event, environment, "machine_recipes_round_trip", StorageTests.SMALL, 20);
    }

    private static void chain(GameTestHelper helper) {
        helper.setBlock(LITHOGRAPH, MachineContent.block(MachineKind.LITHOGRAPH));
        helper.setBlock(FABRICATOR, MachineContent.block(MachineKind.FABRICATOR));
        helper.setBlock(ASSEMBLER, MachineContent.block(MachineKind.ASSEMBLER));
        ProcessingMachineBlockEntity lithograph = machine(helper, LITHOGRAPH);
        ProcessingMachineBlockEntity fabricator = machine(helper, FABRICATOR);
        ProcessingMachineBlockEntity assembler = machine(helper, ASSEMBLER);
        List<ItemStack> bag = new ArrayList<>();
        Item quartz = MaterialContent.LUMEN_QUARTZ.get();
        helper.startSequence()
                // Lumen quartz dust, display glass and the logic mask.
                .thenExecute(() -> feed(fabricator, "press", stack(quartz, 1)))
                .thenWaitUntil(() -> collect(helper, fabricator, MaterialContent.LUMEN_QUARTZ_DUST.get(), 1, bag))
                .thenExecute(() -> {
                    ItemStack blend = craft(helper, 2, 1, take(bag, MaterialContent.LUMEN_QUARTZ_DUST.get()),
                            new ItemStack(Items.GLASS));
                    ItemStack glass = smelt(helper, blend);
                    ItemStack iron = new ItemStack(Items.IRON_INGOT);
                    ItemStack redstone = new ItemStack(Items.REDSTONE);
                    bag.add(craft(helper, 3, 3, iron, redstone, iron, redstone, glass, redstone, iron, redstone, iron));
                    // Silicon from a blend, then slicing.
                    ItemStack silicon = smelt(helper, craft(helper, 2, 1, stack(quartz, 1), new ItemStack(Items.COAL)));
                    feed(lithograph, "slice", silicon);
                }).thenWaitUntil(() -> collect(helper, lithograph, MaterialContent.SILICON_WAFER.get(), 3, bag))
                .thenExecute(() -> feed(lithograph, "dope", take(bag, MaterialContent.SILICON_WAFER.get()),
                        new ItemStack(Items.REDSTONE)))
                .thenWaitUntil(() -> collect(helper, lithograph, MaterialContent.DOPED_WAFER.get(), 1, bag))
                .thenExecute(() -> feed(lithograph, "print", take(bag, MaterialContent.DOPED_WAFER.get()),
                        take(bag, MaterialContent.LOGIC_MASK.get())))
                .thenWaitUntil(() -> collect(helper, lithograph, MaterialContent.LOGIC_DIE.get(), 2, bag))
                .thenExecute(() -> {
                    helper.assertTrue(lithograph.catalysts().stack(0).is(MaterialContent.LOGIC_MASK.get()),
                            "the mask stays in the lithograph");
                    feed(lithograph, "slice", stack(quartz, 1));
                }).thenWaitUntil(() -> collect(helper, lithograph, MaterialContent.OSCILLATOR.get(), 2, bag))
                // Wire, fibre and the board.
                .thenExecute(() -> feed(fabricator, "draw", new ItemStack(Items.COPPER_INGOT)))
                .thenWaitUntil(() -> collect(helper, fabricator, MaterialContent.COPPER_WIRE.get(), 3, bag))
                .thenExecute(() -> {
                    ItemStack wire = take(bag, MaterialContent.COPPER_WIRE.get());
                    bag.add(craft(helper, 3, 1, wire, take(bag, MaterialContent.COPPER_WIRE.get()),
                            new ItemStack(Items.DRIED_KELP)));
                    feed(fabricator, "draw", new ItemStack(Items.GLASS));
                }).thenWaitUntil(() -> collect(helper, fabricator, MaterialContent.GLASS_FIBRE.get(), 2, bag))
                .thenExecute(() -> feed(fabricator, "press", take(bag, MaterialContent.GLASS_FIBRE.get()),
                        take(bag, MaterialContent.GLASS_FIBRE.get()), new ItemStack(Items.SLIME_BALL)))
                .thenWaitUntil(() -> collect(helper, fabricator, MaterialContent.CIRCUIT_BOARD.get(), 1, bag))
                // The processor.
                .thenExecute(() -> feed(assembler, "", take(bag, MaterialContent.LOGIC_DIE.get()),
                        take(bag, MaterialContent.INSULATED_COPPER_WIRE.get()),
                        take(bag, MaterialContent.INSULATED_COPPER_WIRE.get()),
                        take(bag, MaterialContent.OSCILLATOR.get()), take(bag, MaterialContent.CIRCUIT_BOARD.get())))
                .thenWaitUntil(() -> collect(helper, assembler, MaterialContent.BASIC_LOGIC_PROCESSOR.get(), 1, bag))
                .thenExecute(() -> {
                    for (BlockPos pos : List.of(LITHOGRAPH, FABRICATOR, ASSEMBLER)) {
                        helper.setBlock(pos, Blocks.AIR);
                    }
                }).thenSucceed();
    }

    /**
     * Every loaded machine recipe encodes to JSON and back, and through the network codec, unchanged (compared as
     * re-encoded JSON); recipes that do not fit their machine fail to decode.
     */
    private static void roundTrip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RegistryOps<JsonElement> ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        int checked = 0;
        for (MachineKind kind : MachineKind.values()) {
            if (!kind.processes()) {
                continue;
            }
            var codec = StorageMachineRecipe.codec(kind).codec();
            StreamCodec<RegistryFriendlyByteBuf, StorageMachineRecipe> stream = StorageMachineRecipe.streamCodec(kind);
            for (RecipeHolder<StorageMachineRecipe> holder : level.recipeAccess().recipeMap()
                    .byType(MachineContent.recipeType(kind))) {
                JsonElement json = codec.encodeStart(ops, holder.value()).getOrThrow();
                StorageMachineRecipe decoded = codec.parse(ops, json).getOrThrow();
                helper.assertValueEqual(codec.encodeStart(ops, decoded).getOrThrow(), json, holder.id() + " JSON");
                RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
                stream.encode(buffer, holder.value());
                StorageMachineRecipe received = stream.decode(buffer);
                helper.assertValueEqual(codec.encodeStart(ops, received).getOrThrow(), json, holder.id() + " network");
                checked++;
            }
        }
        helper.assertTrue(checked >= 3, "machine recipes loaded: " + checked);
        var lithograph = StorageMachineRecipe.codec(MachineKind.LITHOGRAPH).codec();
        String output = "\"outputs\": [{\"item\": {\"id\": \"maxtech_storage:logic_die\"}}], \"energy_per_tick\": 1, "
                + "\"duration\": 1";
        String input = "{\"ingredient\": \"minecraft:redstone\"}";
        for (String bad : List.of("{\"mode\": \"bake\", \"inputs\": [" + input + "], " + output + "}",
                "{\"inputs\": [" + input + "], " + output + "}",
                "{\"mode\": \"dope\", \"inputs\": [" + input + ", " + input + ", " + input + "], " + output + "}")) {
            JsonElement json = com.google.gson.JsonParser.parseString(bad);
            helper.assertTrue(lithograph.parse(ops, json).isError(), "refused: " + bad);
        }
        helper.succeed();
    }

    static ProcessingMachineBlockEntity machine(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockEntity(pos, ProcessingMachineBlockEntity.class);
    }

    /** Selects {@code mode}, hands the machine {@code inputs} through its side handler and fills its buffer. */
    static void feed(ProcessingMachineBlockEntity machine, String mode, ItemStack... inputs) {
        for (int i = 0; i < machine.kind().modes().size() && !machine.mode().equals(mode); i++) {
            machine.cycleMode();
        }
        for (ItemStack input : inputs) {
            MachineGameTests.insert(machine, input);
        }
        MachineGameTests.charge(machine, 10_000);
    }

    /** Waits for {@code count} of {@code item} in the outputs, then moves them into the bag. */
    static void collect(GameTestHelper helper, ProcessingMachineBlockEntity machine, Item item, int count,
            List<ItemStack> bag) {
        int found = 0;
        for (int slot = 0; slot < machine.outputs().size(); slot++) {
            if (machine.outputs().stack(slot).is(item)) {
                found += machine.outputs().stack(slot).getCount();
            }
        }
        helper.assertTrue(found >= count, "waiting for " + count + " " + item + ", have " + found);
        for (int slot = 0; slot < machine.outputs().size(); slot++) {
            ItemStack stack = machine.outputs().stack(slot);
            if (stack.is(item)) {
                bag.add(stack.copy());
                machine.outputs().shrink(slot, stack.getCount());
            }
        }
    }

    /** Takes one {@code item} out of the bag. */
    static ItemStack take(List<ItemStack> bag, Item item) {
        for (ItemStack stack : bag) {
            if (stack.is(item) && !stack.isEmpty()) {
                return stack.split(1);
            }
        }
        throw new IllegalStateException("No " + item + " in " + bag);
    }

    static ItemStack craft(GameTestHelper helper, int width, int height, ItemStack... grid) {
        CraftingInput input = CraftingInput.of(width, height, List.of(grid));
        Optional<RecipeHolder<CraftingRecipe>> recipe = CrafterBlock.getPotentialResults(helper.getLevel(), input);
        helper.assertTrue(recipe.isPresent(), "a crafting recipe for " + List.of(grid));
        return recipe.orElseThrow().value().assemble(input);
    }

    static ItemStack smelt(GameTestHelper helper, ItemStack stack) {
        ServerLevel level = helper.getLevel();
        Optional<RecipeHolder<SmeltingRecipe>> recipe = level.recipeAccess().getRecipeFor(RecipeType.SMELTING,
                new SingleRecipeInput(stack), level);
        helper.assertTrue(recipe.isPresent(), "a furnace smelts " + stack);
        return recipe.orElseThrow().value().assemble(new SingleRecipeInput(stack));
    }

    private static ItemStack stack(Item item, int count) {
        return new ItemStack(item, count);
    }
}
