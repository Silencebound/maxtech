package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.bulk.BulkContent;
import dev.silencebound.maxtech.storage.bulk.BulkContents;
import dev.silencebound.maxtech.storage.bulk.BulkDriveBlockEntity;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternCodecs;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternContent;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlock;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderLogic;
import dev.silencebound.maxtech.storage.iface.FluidStock;
import dev.silencebound.maxtech.storage.iface.InterfaceBlockEntity;
import dev.silencebound.maxtech.storage.iface.InterfaceContent;
import dev.silencebound.maxtech.storage.iface.InterfaceItems;
import dev.silencebound.maxtech.storage.iface.InterfaceWorker;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.save.KeptValue;
import dev.silencebound.maxtech.storage.cell.StorageCellItem;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * Item components naming an item or fluid of a removed mod (#327): the encoded pattern, the bulk drive's contents and
 * the interface's fluid tanks keep such data as saved instead of failing the whole stack, write it back unchanged, and
 * a kept pattern never crafts. A placed interface keeps such stock slots and tanks in its own save (#332).
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class KeptComponentsGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    /** An item and a fluid id no mod registers. */
    private static final String MISSING = "maxtech_missing:gizmo";
    private static final String MISSING_FLUID = "maxtech_missing:goo";
    private static final StackKey RAW_IRON = ItemKey.of(Items.RAW_IRON);
    private static final StackKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final StackKey RAW_GOLD = ItemKey.of(Items.RAW_GOLD);
    private static final StackKey GOLD = ItemKey.of(Items.GOLD_INGOT);

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> NAMES = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        test("kept_components_in_container", KeptComponentsGameTests::inContainer);
        test("kept_components_placed", KeptComponentsGameTests::placed);
        test("kept_pattern_never_crafts", KeptComponentsGameTests::patternNeverCrafts);
        test("kept_interface_stock_saved", KeptComponentsGameTests::interfaceStockSaved);
        test("kept_components_dropped", KeptComponentsGameTests::dropped);
        test("kept_interface_stock_comes_back", KeptComponentsGameTests::stockComesBack);
        test("kept_interface_old_kept_fluids", KeptComponentsGameTests::oldKeptFluids);
    }

    private KeptComponentsGameTests() {
    }

    private static void test(String name, Consumer<GameTestHelper> function) {
        NAMES.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(function));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("kept_components"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 200, 0, true)));
        }
    }

    private static CraftingPattern processing(StackKey input, StackKey output) {
        return new CraftingPattern(CraftingPattern.Kind.PROCESSING,
                List.of(new PatternInput(new PatternIngredient.Exact(input), 1, Optional.empty(), false)),
                List.of(new PatternOutput(output, 1, 1f)), Optional.empty(), Optional.empty());
    }

    /** Raw gold into gold, saved and then its raw gold renamed to an item no mod registers. */
    private static Tag unknownPatternTag(RegistryOps<Tag> ops) {
        Tag saved = PatternCodecs.PATTERN.encodeStart(ops, processing(RAW_GOLD, GOLD)).getOrThrow();
        return rename(saved, "minecraft:raw_gold", MISSING);
    }

    /** An encoded pattern whose input names an item of a removed mod, as loading it gives. */
    private static ItemStack unknownPattern(GameTestHelper helper, RegistryOps<Tag> ops) {
        KeptValue<CraftingPattern> read = PatternCodecs.KEPT_PATTERN.parse(ops, unknownPatternTag(ops)).getOrThrow();
        helper.assertTrue(read.isKept(), "a pattern with an unknown input was read");
        ItemStack stack = new ItemStack(PatternContent.ENCODED_PATTERN.get());
        stack.set(ModDataComponents.ENCODED_PATTERN.get(), read);
        return stack;
    }

    private static CompoundTag unknownBulkTag() {
        CompoundTag key = new CompoundTag();
        key.putString("id", MISSING);
        CompoundTag contents = new CompoundTag();
        contents.put("key", key);
        contents.putLong("count", 500);
        return contents;
    }

    /** Three tanks: water, a fluid of a removed mod, empty. */
    private static Tag tanksTag(RegistryOps<Tag> ops) {
        Tag water = FluidStock.CODEC.encodeStart(ops, new FluidStock(FluidResource.of(Fluids.WATER), 1000))
                .getOrThrow();
        Tag lava = FluidStock.CODEC.encodeStart(ops, new FluidStock(FluidResource.of(Fluids.LAVA), 2000)).getOrThrow();
        ListTag tanks = new ListTag();
        tanks.add(water);
        tanks.add(rename(lava, "minecraft:lava", MISSING_FLUID));
        tanks.add(FluidStock.CODEC.encodeStart(ops, FluidStock.EMPTY).getOrThrow());
        return tanks;
    }

    /**
     * An encoded pattern, a bulk drive and an interface item naming entries of a removed mod sit in a chest; saved and
     * loaded, the chest still holds them with the unknown parts kept, and saving again writes identical items. The
     * readable parts read as before: the interface's water tank, a plain pattern next to them.
     */
    private static void inContainer(GameTestHelper helper) {
        RegistryOps<Tag> ops = CellGameTests.ops(helper);
        ItemStack pattern = unknownPattern(helper, ops);
        ItemStack bulk = new ItemStack(BulkContent.BULK_DRIVE.get());
        bulk.set(BulkContent.CONTENTS.get(), BulkContents.KEPT_CODEC.parse(ops, unknownBulkTag()).getOrThrow());
        ItemStack iface = new ItemStack(InterfaceContent.INTERFACE.get());
        iface.set(ModDataComponents.INTERFACE_FLUIDS.get(),
                FluidStock.LIST_CODEC.parse(ops, tanksTag(ops)).getOrThrow());
        CraftingPattern plain = processing(RAW_IRON, IRON);

        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(pos, ChestBlockEntity.class);
        chest.setItem(0, pattern);
        chest.setItem(1, bulk);
        chest.setItem(2, iface);
        chest.setItem(3, PatternItem.encode(plain));
        CompoundTag saved = chest.saveWithoutMetadata(helper.getLevel().registryAccess());

        BlockPos other = new BlockPos(3, 1, 1);
        helper.setBlock(other, Blocks.CHEST);
        ChestBlockEntity loaded = helper.getBlockEntity(other, ChestBlockEntity.class);
        load(helper, loaded, saved);
        ItemStack patternBack = loaded.getItem(0);
        helper.assertTrue(patternBack.is(PatternContent.ENCODED_PATTERN.get()), "the kept pattern is in the chest");
        helper.assertTrue(PatternItem.unreadable(patternBack), "the pattern is unreadable");
        helper.assertTrue(PatternItem.pattern(patternBack) == null, "an unreadable pattern offers a pattern");
        KeptValue<CraftingPattern> keptPattern = patternBack.get(ModDataComponents.ENCODED_PATTERN.get());
        same(helper, keptPattern == null ? null : keptPattern.kept(), unknownPatternTag(ops),
                "the pattern's kept data");
        KeptValue<BulkContents> keptBulk = loaded.getItem(1).get(BulkContent.CONTENTS.get());
        same(helper, keptBulk == null ? null : keptBulk.kept(), unknownBulkTag(), "the bulk drive's kept contents");
        List<KeptValue<FluidStock>> tanks = loaded.getItem(2).get(ModDataComponents.INTERFACE_FLUIDS.get());
        if (tanks == null || tanks.size() != 3) {
            throw helper.assertionException(Component.literal("the interface's tanks: " + tanks));
        }
        same(helper, tanks.get(0).value(), new FluidStock(FluidResource.of(Fluids.WATER), 1000), "the readable tank");
        helper.assertTrue(tanks.get(1).isKept(), "the unknown tank is kept");
        same(helper, PatternItem.pattern(loaded.getItem(3)), plain, "the plain pattern");

        CompoundTag again = loaded.saveWithoutMetadata(helper.getLevel().registryAccess());
        helper.assertValueEqual(again.getListOrEmpty("Items"), saved.getListOrEmpty("Items"),
                "items saved back unchanged");
        helper.succeed();
    }

    /**
     * Placed from items with unknown parts, a bulk drive and an interface keep them: the drive takes nothing in, the
     * kept tank takes no fluid, and both write the parts back, through a save and load and onto their items.
     */
    private static void placed(GameTestHelper helper) {
        RegistryOps<Tag> ops = CellGameTests.ops(helper);
        ItemStack bulk = new ItemStack(BulkContent.BULK_DRIVE.get());
        bulk.set(BulkContent.CONTENTS.get(), BulkContents.KEPT_CODEC.parse(ops, unknownBulkTag()).getOrThrow());
        BlockPos drivePos = new BlockPos(1, 1, 1);
        helper.setBlock(drivePos, BulkContent.BULK_DRIVE.get());
        BulkDriveBlockEntity drive = helper.getBlockEntity(drivePos, BulkDriveBlockEntity.class);
        drive.applyComponentsFromItemStack(bulk);
        helper.assertValueEqual(CellGameTests.insert(drive.store(), RAW_IRON, 5), 0L, "inserted into a kept drive");
        helper.assertValueEqual(
                drive.saveWithoutMetadata(helper.getLevel().registryAccess()).getCompoundOrEmpty("contents"),
                unknownBulkTag(), "the drive's saved contents");

        ItemStack item = new ItemStack(InterfaceContent.INTERFACE.get());
        Tag tanksTag = tanksTag(ops);
        item.set(ModDataComponents.INTERFACE_FLUIDS.get(), FluidStock.LIST_CODEC.parse(ops, tanksTag).getOrThrow());
        BlockPos pos = new BlockPos(3, 1, 1);
        helper.setBlock(pos, InterfaceContent.INTERFACE.get());
        InterfaceBlockEntity iface = helper.getBlockEntity(pos, InterfaceBlockEntity.class);
        iface.applyComponentsFromItemStack(item);
        InterfaceWorker worker = iface.worker();
        helper.assertValueEqual(worker.fluidStock().getAmountAsLong(0), 1000L, "the readable tank");
        helper.assertValueEqual(worker.fluidStock().getAmountAsLong(1), 0L, "the kept tank holds nothing");
        worker.config().setItem(InterfaceWorker.ITEM_SLOTS + 1, new ItemStack(Items.WATER_BUCKET, 2));
        worker.config().setItem(InterfaceWorker.ITEM_SLOTS + 2, new ItemStack(Items.WATER_BUCKET, 2));
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(worker.fluidStock().insert(1, FluidResource.of(Fluids.WATER), 1000, transaction), 0,
                    "water into the kept tank");
            helper.assertValueEqual(worker.fluidStock().insert(2, FluidResource.of(Fluids.WATER), 1000, transaction),
                    1000, "water into a free tank");
        }

        CompoundTag saved = iface.saveWithoutMetadata(helper.getLevel().registryAccess());
        BlockPos other = new BlockPos(5, 1, 1);
        helper.setBlock(other, InterfaceContent.INTERFACE.get());
        InterfaceBlockEntity loaded = helper.getBlockEntity(other, InterfaceBlockEntity.class);
        load(helper, loaded, saved);
        helper.assertValueEqual(loaded.worker().fluidStock().getAmountAsLong(0), 1000L, "the readable tank, loaded");
        List<KeptValue<FluidStock>> written = loaded.collectComponents().get(ModDataComponents.INTERFACE_FLUIDS.get());
        if (written == null) {
            throw helper.assertionException(Component.literal("the loaded interface wrote no tanks"));
        }
        helper.assertValueEqual(FluidStock.LIST_CODEC.encodeStart(ops, written).getOrThrow(), tanksTag,
                "the tanks written back onto the item");
        helper.succeed();
    }

    /**
     * A pattern provider holding a pattern whose input names an item of a removed mod never offers it: its output is
     * not craftable, while the plain pattern next to it is. The pattern item does not go into a pattern slot, and the
     * provider saves it back unchanged.
     */
    private static void patternNeverCrafts(GameTestHelper helper) {
        RegistryOps<Tag> ops = CellGameTests.ops(helper);
        ItemStack kept = unknownPattern(helper, ops);
        helper.assertTrue(!ProviderLogic.accepts(kept, false), "a pattern slot takes an unreadable pattern");
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        BlockPos pos = NetworkGameTests.at(3);
        helper.setBlock(pos, ProviderContent.PROVIDER.get().defaultBlockState().setValue(PatternProviderBlock.FACING,
                Direction.EAST));
        PatternProviderBlockEntity provider = helper.getBlockEntity(pos, PatternProviderBlockEntity.class);
        provider.logic().slots().setItem(0, PatternItem.encode(processing(RAW_IRON, IRON)));
        provider.logic().slots().setItem(1, kept);
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = provider.network();
            helper.assertTrue(network != null && network.patterns().isCraftable(IRON), "the plain pattern joined");
        }).thenExecute(() -> {
            NetworkState network = provider.network();
            helper.assertTrue(network != null && !network.patterns().isCraftable(GOLD),
                    "the unreadable pattern's output is craftable");
            helper.assertTrue(network != null && network.patterns().allPatternsFor(GOLD).isEmpty(),
                    "the unreadable pattern is offered");
            CompoundTag saved = provider.saveWithoutMetadata(helper.getLevel().registryAccess());
            PatternProviderBlockEntity copy = new PatternProviderBlockEntity(provider.getBlockPos(),
                    provider.getBlockState());
            load(helper, copy, saved);
            KeptValue<CraftingPattern> back = copy.logic().slots().getItem(1)
                    .get(ModDataComponents.ENCODED_PATTERN.get());
            same(helper, back == null ? null : back.kept(), unknownPatternTag(ops),
                    "the unreadable pattern after a reload");
        }).thenSucceed();
    }

    /**
     * A placed interface's saved stock slots as NeoForge's handler writes them: iron, then raw gold renamed to an item
     * no mod registers, then empty slots.
     */
    private static ListTag stockItemsTag(RegistryOps<Tag> ops) {
        ListTag stacks = new ListTag();
        stacks.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops, new ItemStack(Items.IRON_INGOT, 5)).getOrThrow());
        stacks.add(rename(ItemStack.OPTIONAL_CODEC.encodeStart(ops, new ItemStack(Items.RAW_GOLD, 3)).getOrThrow(),
                "minecraft:raw_gold", MISSING));
        for (int i = 2; i < InterfaceWorker.ITEM_SLOTS; i++) {
            stacks.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops, ItemStack.EMPTY).getOrThrow());
        }
        return stacks;
    }

    /** The saved tanks the same way: water, lava renamed to a fluid no mod registers, empty. */
    private static ListTag stockFluidsTag(RegistryOps<Tag> ops) {
        ListTag tanks = new ListTag();
        tanks.add(FluidStack.OPTIONAL_CODEC.encodeStart(ops, new FluidStack(Fluids.WATER, 1000)).getOrThrow());
        tanks.add(rename(FluidStack.OPTIONAL_CODEC.encodeStart(ops, new FluidStack(Fluids.LAVA, 2000)).getOrThrow(),
                "minecraft:lava", MISSING_FLUID));
        tanks.add(FluidStack.OPTIONAL_CODEC.encodeStart(ops, FluidStack.EMPTY).getOrThrow());
        return tanks;
    }

    /**
     * An interface at {@code pos} loaded from a save whose stock names a removed mod's item and fluid; its ghost slots
     * ask for iron and raw gold.
     */
    private static InterfaceBlockEntity unknownStockInterface(GameTestHelper helper, BlockPos pos) {
        RegistryOps<Tag> ops = CellGameTests.ops(helper);
        helper.setBlock(pos, InterfaceContent.INTERFACE.get());
        InterfaceBlockEntity iface = helper.getBlockEntity(pos, InterfaceBlockEntity.class);
        iface.worker().config().setItem(0, new ItemStack(Items.IRON_INGOT, 16));
        iface.worker().config().setItem(1, new ItemStack(Items.RAW_GOLD, 16));
        CompoundTag saved = iface.saveWithoutMetadata(helper.getLevel().registryAccess());
        saved.put("items", stacksTag(stockItemsTag(ops)));
        saved.put("fluids", stacksTag(stockFluidsTag(ops)));
        load(helper, iface, saved);
        return iface;
    }

    private static CompoundTag stacksTag(ListTag stacks) {
        CompoundTag child = new CompoundTag();
        child.put("stacks", stacks);
        return child;
    }

    /**
     * A placed interface whose saved stock holds one slot and one tank of a removed mod loads the known slots and
     * tanks, keeps the unknown ones empty and refusing, and saves both back unchanged, also after another load.
     */
    private static void interfaceStockSaved(GameTestHelper helper) {
        RegistryOps<Tag> ops = CellGameTests.ops(helper);
        InterfaceBlockEntity iface = unknownStockInterface(helper, new BlockPos(1, 1, 1));
        InterfaceWorker worker = iface.worker();
        helper.assertValueEqual(worker.itemStock().getAmountAsLong(0), 5L, "the iron slot");
        helper.assertValueEqual(worker.itemStock().getAmountAsLong(1), 0L, "the kept slot holds nothing");
        helper.assertValueEqual(worker.fluidStock().getAmountAsLong(0), 1000L, "the water tank");
        helper.assertValueEqual(worker.fluidStock().getAmountAsLong(1), 0L, "the kept tank holds nothing");
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(worker.itemStock().insert(1, ItemResource.of(Items.RAW_GOLD), 3, transaction), 0,
                    "raw gold into the kept slot");
        }

        CompoundTag saved = iface.saveWithoutMetadata(helper.getLevel().registryAccess());
        helper.assertValueEqual(saved.getCompoundOrEmpty("items").getListOrEmpty("stacks"), stockItemsTag(ops),
                "saved slots");
        helper.assertValueEqual(saved.getCompoundOrEmpty("fluids").getListOrEmpty("stacks"), stockFluidsTag(ops),
                "saved tanks");
        BlockPos other = new BlockPos(3, 1, 1);
        helper.setBlock(other, InterfaceContent.INTERFACE.get());
        InterfaceBlockEntity loaded = helper.getBlockEntity(other, InterfaceBlockEntity.class);
        load(helper, loaded, saved);
        helper.assertValueEqual(loaded.worker().itemStock().getAmountAsLong(0), 5L, "the iron slot, loaded again");
        CompoundTag again = loaded.saveWithoutMetadata(helper.getLevel().registryAccess());
        helper.assertValueEqual(again.getCompoundOrEmpty("items"), saved.getCompoundOrEmpty("items"),
                "slots saved again");
        helper.assertValueEqual(again.getCompoundOrEmpty("fluids"), saved.getCompoundOrEmpty("fluids"),
                "tanks saved again");
        helper.succeed();
    }

    /**
     * A save from the builds before #332 kept every tank in {@code kept_fluids}, beside a {@code fluids} stock without
     * the unknown one (#2). It loads like a current save: the water tank, the removed mod's tank kept empty and
     * refusing fluid. Saved again, it is written in the current format, and {@code kept_fluids} is gone.
     */
    private static void oldKeptFluids(GameTestHelper helper) {
        RegistryOps<Tag> ops = CellGameTests.ops(helper);
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, InterfaceContent.INTERFACE.get());
        InterfaceBlockEntity iface = helper.getBlockEntity(pos, InterfaceBlockEntity.class);
        CompoundTag saved = iface.saveWithoutMetadata(helper.getLevel().registryAccess());
        ListTag readable = new ListTag();
        readable.add(FluidStack.OPTIONAL_CODEC.encodeStart(ops, new FluidStack(Fluids.WATER, 1000)).getOrThrow());
        readable.add(FluidStack.OPTIONAL_CODEC.encodeStart(ops, FluidStack.EMPTY).getOrThrow());
        readable.add(FluidStack.OPTIONAL_CODEC.encodeStart(ops, FluidStack.EMPTY).getOrThrow());
        saved.put("fluids", stacksTag(readable));
        saved.put("kept_fluids", tanksTag(ops));
        load(helper, iface, saved);
        InterfaceWorker worker = iface.worker();
        helper.assertValueEqual(worker.fluidStock().getAmountAsLong(0), 1000L, "the water tank");
        helper.assertValueEqual(worker.fluidStock().getAmountAsLong(1), 0L, "the kept tank holds nothing");
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(worker.fluidStock().insert(1, FluidResource.of(Fluids.WATER), 100, transaction), 0,
                    "water into the kept tank");
        }
        CompoundTag again = iface.saveWithoutMetadata(helper.getLevel().registryAccess());
        helper.assertValueEqual(again.getCompoundOrEmpty("fluids").getListOrEmpty("stacks"), stockFluidsTag(ops),
                "tanks saved in the current format");
        helper.assertTrue(!again.contains("kept_fluids"), "kept_fluids is written again");
        helper.succeed();
    }

    /**
     * Broken, a bulk drive and an interface holding a removed mod's entries drop items that still carry them: the
     * drive's kept contents, the interface's kept slot and tank beside its readable stock.
     */
    private static void dropped(GameTestHelper helper) {
        RegistryOps<Tag> ops = CellGameTests.ops(helper);
        ItemStack bulk = new ItemStack(BulkContent.BULK_DRIVE.get());
        bulk.set(BulkContent.CONTENTS.get(), BulkContents.KEPT_CODEC.parse(ops, unknownBulkTag()).getOrThrow());
        BlockPos drivePos = new BlockPos(1, 1, 1);
        helper.setBlock(drivePos, BulkContent.BULK_DRIVE.get());
        helper.getBlockEntity(drivePos, BulkDriveBlockEntity.class).applyComponentsFromItemStack(bulk);
        helper.getLevel().destroyBlock(helper.absolutePos(drivePos), true);
        // A drive keeps unread contents in its block entity data on the item, as when it loaded them itself (#288).
        ItemStack droppedDrive = drop(helper, drivePos, BulkContent.BULK_DRIVE.item().get());
        TypedEntityData<BlockEntityType<?>> data = droppedDrive.get(DataComponents.BLOCK_ENTITY_DATA);
        same(helper, data == null ? null : data.copyTagWithoutId().getCompoundOrEmpty("contents"), unknownBulkTag(),
                "the dropped drive's kept contents");
        // Its tooltip says so, as a drive item that carries them as a component does (#2).
        List<Component> lines = new ArrayList<>();
        droppedDrive.getItem().appendHoverText(droppedDrive, Item.TooltipContext.EMPTY, TooltipDisplay.DEFAULT,
                lines::add, TooltipFlag.NORMAL);
        helper.assertTrue(
                lines.stream()
                        .anyMatch(line -> line.getContents() instanceof TranslatableContents text
                                && text.getKey().equals(StorageCellItem.KEPT_KEY)),
                "no kept line on the dropped drive: " + lines);

        BlockPos pos = new BlockPos(5, 1, 1);
        unknownStockInterface(helper, pos);
        helper.getLevel().destroyBlock(helper.absolutePos(pos), true);
        ItemStack item = drop(helper, pos, InterfaceContent.INTERFACE.item().get());
        ItemContainerContents held = item.get(DataComponents.CONTAINER);
        NonNullList<ItemStack> stacks = NonNullList.withSize(InterfaceWorker.ITEM_SLOTS, ItemStack.EMPTY);
        if (held != null) {
            held.copyInto(stacks);
        }
        helper.assertTrue(ItemStack.matches(stacks.get(0), new ItemStack(Items.IRON_INGOT, 5)), "the dropped iron");
        List<KeptValue<ItemStack>> keptItems = item.get(ModDataComponents.INTERFACE_KEPT_ITEMS.get());
        if (keptItems == null || keptItems.size() != InterfaceWorker.ITEM_SLOTS) {
            throw helper.assertionException(Component.literal("the dropped interface's kept slots: " + keptItems));
        }
        same(helper, keptItems.get(1).kept(), stockItemsTag(ops).get(1), "the dropped kept slot");
        helper.assertTrue(!keptItems.get(0).isKept(), "a readable slot is kept");
        List<KeptValue<FluidStock>> tanks = item.get(ModDataComponents.INTERFACE_FLUIDS.get());
        if (tanks == null || tanks.size() != 3) {
            throw helper.assertionException(Component.literal("the dropped interface's tanks: " + tanks));
        }
        same(helper, tanks.get(0).value(), new FluidStock(FluidResource.of(Fluids.WATER), 1000), "the dropped water");
        helper.assertTrue(tanks.get(1).isKept(), "the dropped unknown tank is kept");
        helper.succeed();
    }

    /**
     * Once the removed mod is back (the saved ids renamed to registered ones), a kept slot and tank read again with
     * their stock: from the block's own save and from the interface's item.
     */
    private static void stockComesBack(GameTestHelper helper) {
        RegistryOps<Tag> ops = CellGameTests.ops(helper);
        InterfaceBlockEntity iface = unknownStockInterface(helper, new BlockPos(1, 1, 1));
        CompoundTag saved = (CompoundTag) back(iface.saveWithoutMetadata(helper.getLevel().registryAccess()));
        BlockPos other = new BlockPos(3, 1, 1);
        helper.setBlock(other, InterfaceContent.INTERFACE.get());
        InterfaceBlockEntity loaded = helper.getBlockEntity(other, InterfaceBlockEntity.class);
        load(helper, loaded, saved);
        stockBack(helper, loaded.worker(), "from the block's save");

        ItemStack item = new ItemStack(InterfaceContent.INTERFACE.get());
        item.applyComponents(iface.collectComponents());
        List<KeptValue<ItemStack>> keptItems = item.get(ModDataComponents.INTERFACE_KEPT_ITEMS.get());
        List<KeptValue<FluidStock>> tanks = item.get(ModDataComponents.INTERFACE_FLUIDS.get());
        if (keptItems == null || tanks == null) {
            throw helper.assertionException(Component.literal("the interface wrote no kept stock"));
        }
        item.set(ModDataComponents.INTERFACE_KEPT_ITEMS.get(),
                InterfaceItems.KEPT_ITEMS_CODEC
                        .parse(ops, back(InterfaceItems.KEPT_ITEMS_CODEC.encodeStart(ops, keptItems).getOrThrow()))
                        .getOrThrow());
        item.set(ModDataComponents.INTERFACE_FLUIDS.get(), FluidStock.LIST_CODEC
                .parse(ops, back(FluidStock.LIST_CODEC.encodeStart(ops, tanks).getOrThrow())).getOrThrow());
        BlockPos placed = new BlockPos(5, 1, 1);
        helper.setBlock(placed, InterfaceContent.INTERFACE.get());
        InterfaceBlockEntity fromItem = helper.getBlockEntity(placed, InterfaceBlockEntity.class);
        fromItem.applyComponentsFromItemStack(item);
        stockBack(helper, fromItem.worker(), "from the item");
        helper.succeed();
    }

    /** {@code tag} as if the removed mod were back: its item and fluid ids renamed to raw gold and lava. */
    private static Tag back(Tag tag) {
        return rename(rename(tag, MISSING, "minecraft:raw_gold"), MISSING_FLUID, "minecraft:lava");
    }

    private static void stockBack(GameTestHelper helper, InterfaceWorker worker, String where) {
        helper.assertValueEqual(worker.itemStock().getResource(1), ItemResource.of(Items.RAW_GOLD),
                "the slot's item " + where);
        helper.assertValueEqual(worker.itemStock().getAmountAsLong(1), 3L, "the slot's count " + where);
        helper.assertValueEqual(worker.fluidStock().getResource(1), FluidResource.of(Fluids.LAVA),
                "the tank's fluid " + where);
        helper.assertValueEqual(worker.fluidStock().getAmountAsLong(1), 2000L, "the tank's amount " + where);
        helper.assertValueEqual(worker.itemStock().getAmountAsLong(0), 5L, "the iron slot " + where);
    }

    /** The one dropped {@code item} near {@code pos}; fails on none or more than one. Discards the entities. */
    private static ItemStack drop(GameTestHelper helper, BlockPos pos, Item item) {
        List<ItemStack> found = new ArrayList<>();
        for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(helper.absolutePos(pos)).inflate(1))) {
            if (entity.getItem().is(item)) {
                found.add(entity.getItem().copy());
            }
            entity.discard();
        }
        helper.assertValueEqual(found.size(), 1, "dropped " + item);
        return found.get(0);
    }

    private static void same(GameTestHelper helper, @Nullable Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw helper.assertionException(Component.literal(what + ": expected " + expected + ", got " + actual));
        }
    }

    private static void load(GameTestHelper helper, BlockEntity entity, CompoundTag saved) {
        entity.loadWithComponents(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
    }

    /** {@code tag} with every string {@code from} replaced by {@code to}, at any depth. */
    static Tag rename(Tag tag, String from, String to) {
        Tag copy = tag.copy();
        replace(copy, from, to);
        if (copy instanceof StringTag(String value) && value.equals(from)) {
            return StringTag.valueOf(to);
        }
        return copy;
    }

    private static void replace(@Nullable Tag tag, String from, String to) {
        if (tag instanceof CompoundTag compound) {
            for (String key : List.copyOf(compound.keySet())) {
                Tag child = compound.get(key);
                if (child instanceof StringTag(String value) && value.equals(from)) {
                    compound.putString(key, to);
                } else {
                    replace(child, from, to);
                }
            }
        } else if (tag instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                Tag child = list.get(i);
                if (child instanceof StringTag(String value) && value.equals(from)) {
                    list.set(i, StringTag.valueOf(to));
                } else {
                    replace(child, from, to);
                }
            }
        }
    }
}
