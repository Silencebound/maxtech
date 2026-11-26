package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.recipe.ItemInput;
import dev.silencebound.maxtech.lib.api.recipe.ItemOutput;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternContent;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternEncoder;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTransfer;
import dev.silencebound.maxtech.storage.iface.InterfaceContent;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.MachineExport;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.machine.ProcessingMachineBlockEntity;
import dev.silencebound.maxtech.storage.machine.StorageMachineMenu;
import dev.silencebound.maxtech.storage.machine.StorageMachineRecipe;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The export button of Storage's machines (M7c): the pattern it writes equals what the pattern terminal encodes for the
 * same recipe, the button takes one blank and gives the pattern, it exports the recipe of the last run, and it follows
 * the access rules for a machine on a network (block or part) and one on none.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class MachineExportGameTests {
    private static final String[] NAMES = {"machine_export_matches_terminal", "machine_export_button",
            "machine_export_last_run", "machine_export_owner_access", "machine_export_network_access"};
    private static final BlockPos MACHINE = new BlockPos(2, 1, 1);

    static {
        TestStorage.init();
        register(NAMES[0], MachineExportGameTests::matchesTerminal);
        register(NAMES[1], MachineExportGameTests::button);
        register(NAMES[2], MachineExportGameTests::lastRun);
        register(NAMES[3], MachineExportGameTests::ownerAccess);
        register(NAMES[4], MachineExportGameTests::networkAccess);
    }

    private MachineExportGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("machine_export"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 400);
        }
    }

    private static ProcessingMachineBlockEntity machine(GameTestHelper helper, MachineKind kind, BlockPos pos) {
        helper.setBlock(pos, MachineContent.block(kind));
        return helper.getBlockEntity(pos, ProcessingMachineBlockEntity.class);
    }

    private static CraftingPattern exported(GameTestHelper helper, RecipeHolder<StorageMachineRecipe> holder) {
        CraftingPattern pattern = MachineExport.pattern(holder);
        if (pattern == null) {
            throw helper.assertionException(Component.literal(holder.id() + " does not export"));
        }
        return pattern;
    }

    private static RecipeHolder<StorageMachineRecipe> recipe(GameTestHelper helper,
            ProcessingMachineBlockEntity machine) {
        RecipeHolder<StorageMachineRecipe> holder = machine.exportRecipe();
        if (holder == null) {
            throw helper.assertionException(Component.literal("the machine has no recipe to export"));
        }
        return holder;
    }

    private static CraftingPattern patternOn(GameTestHelper helper, ItemStack stack) {
        CraftingPattern pattern = PatternItem.pattern(stack);
        if (pattern == null) {
            throw helper.assertionException(Component.literal("no pattern on " + stack));
        }
        return pattern;
    }

    private static int count(ServerPlayer player, Item item) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * What a recipe viewer's transfer would send for {@code recipe}, turned into a pattern by the terminal's own route
     * ({@link PatternEncoder#receiveProcessing}): each input as its first item with the tag it came from, each output
     * with chance 1.
     */
    private static CraftingPattern terminalPattern(ServerLevel level, RecipeHolder<StorageMachineRecipe> holder) {
        StorageMachineRecipe recipe = holder.value();
        List<PatternTransfer.Entry> inputs = new ArrayList<>();
        for (ItemInput input : recipe.inputs()) {
            Item first = input.ingredient().items().findFirst().orElseThrow().value();
            Optional<Identifier> tag = Optional.empty();
            if (input.ingredient().display() instanceof SlotDisplay.TagSlotDisplay(TagKey<Item> key)) {
                tag = Optional.of(key.location());
            }
            inputs.add(new PatternTransfer.Entry(ItemKey.of(first), input.count(), tag, 1f));
        }
        List<PatternTransfer.Entry> outputs = new ArrayList<>();
        for (ItemOutput output : recipe.outputs()) {
            ItemStack stack = output.item().create();
            outputs.add(new PatternTransfer.Entry(ItemKey.of(stack.copyWithCount(1)), stack.getCount(),
                    Optional.empty(), 1f));
        }
        PatternEncoder encoder = new PatternEncoder(() -> {
        });
        encoder.receiveProcessing(level,
                new PatternTransfer(0, Optional.ofNullable(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType())),
                        Optional.of(holder.id().identifier()), inputs, outputs));
        return encoder.draft().toPattern().orElseThrow();
    }

    /** The export's pattern with catalysts and chances put back to what a viewer transfer carries. */
    private static CraftingPattern asTransfer(CraftingPattern pattern) {
        List<PatternInput> inputs = new ArrayList<>();
        for (PatternInput input : pattern.inputs()) {
            inputs.add(new PatternInput(input.ingredient(), input.amount(), input.face(), false));
        }
        List<PatternOutput> outputs = new ArrayList<>();
        for (PatternOutput output : pattern.outputs()) {
            outputs.add(new PatternOutput(output.key(), output.amount(), 1f));
        }
        return new CraftingPattern(pattern.kind(), inputs, outputs, pattern.recipeType(), pattern.recipe());
    }

    /**
     * Every recipe of every processing machine exports to what the terminal encodes for it, apart from the two things
     * the export knows better: a catalyst is returned and a chance output keeps its chance. A hand-made recipe with
     * both shows those.
     */
    private static void matchesTerminal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int compared = 0;
        int catalysts = 0;
        for (MachineKind kind : MachineKind.values()) {
            if (!kind.processes()) {
                continue;
            }
            int ofKind = 0;
            for (RecipeHolder<StorageMachineRecipe> holder : level.recipeAccess().recipeMap()
                    .byType(MachineContent.recipeType(kind))) {
                CraftingPattern exported = exported(helper, holder);
                helper.assertValueEqual(asTransfer(exported), terminalPattern(helper.getLevel(), holder),
                        holder.id() + " as the terminal");
                helper.assertTrue(exported.kind() == CraftingPattern.Kind.PROCESSING, "processing");
                helper.assertValueEqual(exported.recipe(), Optional.of(holder.id().identifier()), "recipe id");
                for (int i = 0; i < holder.value().inputs().size(); i++) {
                    boolean catalyst = !holder.value().inputs().get(i).consumed();
                    helper.assertValueEqual(exported.inputs().get(i).returned(), catalyst, holder.id() + " input " + i);
                    catalysts += catalyst ? 1 : 0;
                }
                ofKind++;
            }
            helper.assertTrue(ofKind > 0, kind.id() + " has recipes");
            compared += ofKind;
        }
        helper.assertTrue(compared > 10, "recipes compared: " + compared);
        helper.assertTrue(catalysts > 0, "a recipe with a mask catalyst");

        StorageMachineRecipe made = new StorageMachineRecipe(MachineKind.LITHOGRAPH, Optional.of("print"),
                List.of(new ItemInput(Ingredient.of(Items.IRON_INGOT), 3),
                        new ItemInput(Ingredient.of(Items.SHEARS), 1, false)),
                List.of(new ItemOutput(new ItemStackTemplate(Items.GOLD_INGOT, 2), ItemOutput.ALWAYS),
                        new ItemOutput(new ItemStackTemplate(Items.COAL), 2500)),
                1, 10, Optional.empty());
        RecipeHolder<StorageMachineRecipe> holder = new RecipeHolder<>(
                ResourceKey.create(Registries.RECIPE, MaxtechStorage.id("export_test")), made);
        CraftingPattern pattern = exported(helper, holder);
        helper.assertValueEqual(pattern.inputs().get(0).amount(), 3L, "iron amount");
        helper.assertTrue(!pattern.inputs().get(0).returned() && pattern.inputs().get(1).returned(), "shears returned");
        helper.assertValueEqual(pattern.mainOutput(), new PatternOutput(ItemKey.of(Items.GOLD_INGOT), 2, 1f),
                "main output first");
        helper.assertValueEqual(pattern.outputs().get(1).chance(), 0.25f, "byproduct chance");

        // An input that matches no item cannot be written: no pattern, so the button reports no recipe.
        HolderSet.Named<Item> emptyTag = null;
        for (HolderSet.Named<Item> tag : (Iterable<HolderSet.Named<Item>>) BuiltInRegistries.ITEM.getTags()
                .map(named -> named)::iterator) {
            if (tag.size() == 0) {
                emptyTag = tag;
                break;
            }
        }
        if (emptyTag == null) {
            throw helper.assertionException(Component.literal("no empty item tag is bound"));
        }
        StorageMachineRecipe empty = new StorageMachineRecipe(MachineKind.ASSEMBLER, Optional.empty(),
                List.of(new ItemInput(Ingredient.of(emptyTag), 1)),
                List.of(new ItemOutput(new ItemStackTemplate(Items.COAL), ItemOutput.ALWAYS)), 1, 10, Optional.empty());
        helper.assertTrue(
                MachineExport.pattern(new RecipeHolder<>(
                        ResourceKey.create(Registries.RECIPE, MaxtechStorage.id("export_test_empty")), empty)) == null,
                "an empty tag gives no pattern");
        helper.succeed();
    }

    /**
     * The button on a machine whose slots select a recipe: exportable, takes exactly one blank and hands over the
     * pattern the export builds; with no blank it takes nothing.
     */
    private static void button(GameTestHelper helper) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "export_button");
        // The menu is valid only within reach of the machine.
        player.setPos(helper.absolutePos(MACHINE).getCenter());
        ProcessingMachineBlockEntity assembler = machine(helper, MachineKind.ASSEMBLER, MACHINE);
        StorageMachineMenu menu = StorageMachineMenu.forMachine(1, player.getInventory(), assembler);
        helper.assertTrue(!menu.exportable(), "an empty machine has nothing to export");
        helper.assertTrue(!menu.clickMenuButton(player, StorageMachineMenu.EXPORT_PATTERN), "no recipe, no export");
        helper.assertValueEqual(MachineExport.export(player, assembler), MachineExport.Result.NO_RECIPE, "no recipe");
        MachineGameTests.insert(assembler, MachineGameTests.stack(MaterialContent.LOGIC_DIE.get(), 1));
        MachineGameTests.insert(assembler, MachineGameTests.stack(MaterialContent.INSULATED_COPPER_WIRE.get(), 2));
        MachineGameTests.insert(assembler, MachineGameTests.stack(MaterialContent.OSCILLATOR.get(), 1));
        MachineGameTests.insert(assembler, MachineGameTests.stack(MaterialContent.CIRCUIT_BOARD.get(), 1));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(assembler.exportRecipe() != null, "recipe matched"))
                .thenExecute(() -> {
                    helper.assertTrue(menu.exportable(), "exportable once matched");
                    helper.assertValueEqual(MachineExport.export(player, assembler), MachineExport.Result.NO_BLANK,
                            "no blank pattern");
                    helper.assertValueEqual(count(player, PatternContent.ENCODED_PATTERN.get()), 0, "nothing given");
                    player.getInventory().add(new ItemStack(PatternContent.BLANK_PATTERN.get(), 2));
                    helper.assertTrue(menu.clickMenuButton(player, StorageMachineMenu.EXPORT_PATTERN), "the button");
                    helper.assertValueEqual(count(player, PatternContent.BLANK_PATTERN.get()), 1, "one blank used");
                    ItemStack encoded = player.getInventory().getItem(findEncoded(player));
                    CraftingPattern pattern = patternOn(helper, encoded);
                    helper.assertValueEqual(pattern.mainOutput().key(),
                            (StackKey) ItemKey.of(MaterialContent.BASIC_LOGIC_PROCESSOR.get()), "main output");
                    helper.assertValueEqual(pattern.recipeType(),
                            Optional.ofNullable(
                                    BuiltInRegistries.RECIPE_TYPE.getKey(recipe(helper, assembler).value().getType())),
                            "recipe type");
                    helper.assertValueEqual(pattern, exported(helper, recipe(helper, assembler)),
                            "the export's pattern");
                    helper.assertTrue(!menu.clickMenuButton(player, 7), "an unknown button");
                }).thenSucceed();
    }

    private static int findEncoded(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(PatternContent.ENCODED_PATTERN.get())) {
                return i;
            }
        }
        return 0;
    }

    /** After a run used up its inputs the machine still exports the recipe it ran. */
    private static void lastRun(GameTestHelper helper) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "export_last_run");
        player.getInventory().add(new ItemStack(PatternContent.BLANK_PATTERN.get()));
        ProcessingMachineBlockEntity assembler = machine(helper, MachineKind.ASSEMBLER, MACHINE);
        MachineGameTests.insert(assembler, MachineGameTests.stack(MaterialContent.LOGIC_DIE.get(), 1));
        MachineGameTests.insert(assembler, MachineGameTests.stack(MaterialContent.INSULATED_COPPER_WIRE.get(), 2));
        MachineGameTests.insert(assembler, MachineGameTests.stack(MaterialContent.OSCILLATOR.get(), 1));
        MachineGameTests.insert(assembler, MachineGameTests.stack(MaterialContent.CIRCUIT_BOARD.get(), 1));
        MachineGameTests.charge(assembler, 20_000);
        helper.startSequence()
                .thenWaitUntil(
                        () -> helper.assertValueEqual(assembler.outputs().stack(0).getCount(), 1, "one processor"))
                .thenWaitUntil(() -> helper.assertTrue(assembler.currentRecipe() == null, "nothing selected now"))
                .thenExecute(() -> {
                    helper.assertValueEqual(MachineExport.export(player, assembler), MachineExport.Result.EXPORTED,
                            "exports the last run");
                    helper.assertValueEqual(
                            patternOn(helper, player.getInventory().getItem(findEncoded(player))).mainOutput().key(),
                            (StackKey) ItemKey.of(MaterialContent.BASIC_LOGIC_PROCESSOR.get()),
                            "the last run's output");
                }).thenSucceed();
    }

    /**
     * A machine on no network follows its owner: a stranger is refused, the owner is not, and a refusal costs nothing.
     */
    private static void ownerAccess(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = NetworkGameTests.player(level, "export_owner");
        ServerPlayer stranger = NetworkGameTests.player(level, "export_stranger");
        ProcessingMachineBlockEntity assembler = machine(helper, MachineKind.ASSEMBLER, MACHINE);
        assembler.setOwner(OwnerRecord.of(owner));
        MachineGameTests.insert(assembler, MachineGameTests.stack(MaterialContent.LOGIC_DIE.get(), 1));
        MachineGameTests.insert(assembler, MachineGameTests.stack(MaterialContent.INSULATED_COPPER_WIRE.get(), 2));
        MachineGameTests.insert(assembler, MachineGameTests.stack(MaterialContent.OSCILLATOR.get(), 1));
        MachineGameTests.insert(assembler, MachineGameTests.stack(MaterialContent.CIRCUIT_BOARD.get(), 1));
        stranger.getInventory().add(new ItemStack(PatternContent.BLANK_PATTERN.get()));
        owner.getInventory().add(new ItemStack(PatternContent.BLANK_PATTERN.get()));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(assembler.exportRecipe() != null, "matched"))
                .thenExecute(() -> {
                    helper.assertValueEqual(MachineExport.export(stranger, assembler), MachineExport.Result.DENIED,
                            "a stranger");
                    helper.assertValueEqual(count(stranger, PatternContent.BLANK_PATTERN.get()), 1, "blank kept");
                    helper.assertValueEqual(MachineExport.export(owner, assembler), MachineExport.Result.EXPORTED,
                            "the owner");
                }).thenSucceed();
    }

    /**
     * A machine touching a network (an interface block, or an interface part on a cable) takes the network's rules:
     * craft access exports, deposit access does not, and a network's rules override the machine's owner. Without a
     * security module the network's owner rule applies.
     */
    private static void networkAccess(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = NetworkGameTests.player(level, "export_net_owner");
        ServerPlayer depositor = NetworkGameTests.player(level, "export_depositor");
        ServerPlayer crafter = NetworkGameTests.player(level, "export_crafter");
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        // A cable with an interface part facing up at x = 2, an interface block at x = 3; a machine above each.
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        var parts = PartGameTests.parts(helper, 2);
        helper.assertTrue(parts.place(Direction.UP, InterfaceContent.INTERFACE_PART, null,
                new ItemStack(InterfaceContent.INTERFACE_PART_ITEM.get())), "part placed");
        helper.setBlock(NetworkGameTests.at(3), InterfaceContent.INTERFACE.get());
        ProcessingMachineBlockEntity partMachine = machine(helper, MachineKind.ASSEMBLER,
                NetworkGameTests.at(2).above());
        ProcessingMachineBlockEntity assembler = machine(helper, MachineKind.ASSEMBLER, NetworkGameTests.at(3).above());
        for (ProcessingMachineBlockEntity machine : List.of(assembler, partMachine)) {
            machine.setOwner(OwnerRecord.of(depositor));
            MachineGameTests.insert(machine, MachineGameTests.stack(MaterialContent.LOGIC_DIE.get(), 1));
            MachineGameTests.insert(machine, MachineGameTests.stack(MaterialContent.INSULATED_COPPER_WIRE.get(), 2));
            MachineGameTests.insert(machine, MachineGameTests.stack(MaterialContent.OSCILLATOR.get(), 1));
            MachineGameTests.insert(machine, MachineGameTests.stack(MaterialContent.CIRCUIT_BOARD.get(), 1));
        }
        for (ServerPlayer player : List.of(depositor, crafter, owner)) {
            player.getInventory().add(new ItemStack(PatternContent.BLANK_PATTERN.get(), 3));
        }
        BlockPos module = core.above();
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(assembler.exportRecipe() != null && partMachine.exportRecipe() != null, "matched");
            helper.assertTrue(NetworkGameTests.network(helper, NetworkGameTests.at(3)) != null, "interface joined");
        }).thenExecute(() -> {
            helper.assertValueEqual(MachineExport.export(depositor, assembler), MachineExport.Result.DENIED,
                    "the machine's owner is not the network's owner");
            helper.assertValueEqual(MachineExport.export(owner, assembler), MachineExport.Result.EXPORTED,
                    "the network's owner");
            ControllerClusterGameTests.expansion(helper, module, ExpansionContent.SECURITY_MODULE);
            ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
            controller.setSecurity(SecuritySettings.DEFAULT
                    .withPlayer(depositor.getUUID(), depositor.getGameProfile().name(), AccessLevel.DEPOSIT)
                    .withPlayer(crafter.getUUID(), crafter.getGameProfile().name(), AccessLevel.CRAFT));
        }).thenWaitUntil(
                () -> helper.assertTrue(NetworkGameTests.controllerEntity(helper, core).secured(), "module active"))
                .thenExecute(() -> {
                    helper.assertValueEqual(MachineExport.export(depositor, assembler), MachineExport.Result.DENIED,
                            "deposit access is not enough");
                    helper.assertValueEqual(MachineExport.export(crafter, assembler), MachineExport.Result.EXPORTED,
                            "craft access");
                    helper.assertValueEqual(MachineExport.export(depositor, partMachine), MachineExport.Result.DENIED,
                            "deposit access at a part");
                    helper.assertValueEqual(MachineExport.export(crafter, partMachine), MachineExport.Result.EXPORTED,
                            "craft access at a part");
                    helper.assertValueEqual(count(depositor, PatternContent.BLANK_PATTERN.get()), 3,
                            "a refusal keeps the blank");
                }).thenSucceed();
    }
}
