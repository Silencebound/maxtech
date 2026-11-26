package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.CellData;
import dev.silencebound.maxtech.storage.cell.CellFilter;
import dev.silencebound.maxtech.storage.cell.CellInventory;
import dev.silencebound.maxtech.storage.cell.StorageCellItem;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The cell blacklist (B09 item 10): a partition filter in deny mode takes everything except what its entries match
 * (exact, fuzzy and tag entries alike), void overflow never destroys a denied key, the network routes a denied key past
 * a deny cell of higher priority, and the mode survives saving the cell.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class CellDenyGameTests {
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey STONE = ItemKey.of(Items.STONE);
    private static final long POWER = 10_000;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> NAMES = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        test("cell_deny_matcher", CellDenyGameTests::matcher);
        test("cell_deny_partition_and_void", CellDenyGameTests::partitionAndVoid);
        test("cell_deny_routing", CellDenyGameTests::routing);
        test("cell_deny_saved", CellDenyGameTests::saved);
    }

    private CellDenyGameTests() {
    }

    private static void test(String name, Consumer<GameTestHelper> function) {
        NAMES.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(function));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("cell_deny"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, StorageTests.SMALL, 200, 0, true)));
        }
    }

    /** Allow and deny lists against exact, fuzzy and tag entries, and a blank list in both modes. */
    private static void matcher(GameTestHelper helper) {
        ItemKey named = CellGameTests.named("a");
        ItemKey diamond = ItemKey.of(Items.DIAMOND);
        ItemKey birch = ItemKey.of(Items.BIRCH_PLANKS);
        CellFilter exact = new CellFilter(List.of(CellFilter.Entry.of(named)), false);
        CellFilter fuzzy = exact.withFuzzy(true);
        CellFilter tag = new CellFilter(
                List.of(new CellFilter.Entry(ItemKey.of(Items.OAK_PLANKS), Optional.of(ItemTags.PLANKS.location()))),
                false);

        CellFilter.Matcher allowExact = exact.matcher();
        CellFilter.Matcher denyExact = exact.withDeny(true).matcher();
        check(helper, allowExact.accepts(named), true, "allow exact: the entry");
        check(helper, allowExact.accepts(diamond), false, "allow exact: another variant");
        check(helper, denyExact.accepts(named), false, "deny exact: the entry");
        check(helper, denyExact.accepts(diamond), true, "deny exact: another variant");
        check(helper, denyExact.accepts(STONE), true, "deny exact: another item");

        CellFilter.Matcher allowFuzzy = fuzzy.matcher();
        CellFilter.Matcher denyFuzzy = fuzzy.withDeny(true).matcher();
        check(helper, allowFuzzy.accepts(diamond), true, "allow fuzzy: a variant");
        check(helper, allowFuzzy.accepts(STONE), false, "allow fuzzy: another item");
        check(helper, denyFuzzy.accepts(diamond), false, "deny fuzzy: a variant");
        check(helper, denyFuzzy.accepts(CellGameTests.named("b")), false, "deny fuzzy: another name");
        check(helper, denyFuzzy.accepts(STONE), true, "deny fuzzy: another item");

        CellFilter.Matcher allowTag = tag.matcher();
        CellFilter.Matcher denyTag = tag.withDeny(true).matcher();
        check(helper, allowTag.accepts(birch), true, "allow tag: planks");
        check(helper, allowTag.accepts(COBBLE), false, "allow tag: not planks");
        check(helper, denyTag.accepts(birch), false, "deny tag: planks");
        check(helper, denyTag.accepts(COBBLE), true, "deny tag: not planks");
        check(helper, denyTag.voids(birch), false, "deny tag voids planks");
        check(helper, denyTag.voids(COBBLE), true, "deny tag voids other items");

        CellFilter.Matcher blankDeny = CellFilter.EMPTY.withDeny(true).matcher();
        check(helper, blankDeny.accepts(COBBLE), true, "blank deny list takes");
        check(helper, blankDeny.voids(COBBLE), false, "blank deny list voids");
        helper.succeed();
    }

    /**
     * A deny cell with partition and void overflow refuses the denied key outright, also once full, and takes and voids
     * every other key; without the partition add-on it stores the denied key but never voids it.
     */
    private static void partitionAndVoid(GameTestHelper helper) {
        CellFilter deny = new CellFilter(List.of(CellFilter.Entry.of(COBBLE)), false, true);
        List<Item> memory = List.of(CellContent.MEMORY_MODULE_1.get());
        DriveBlockEntity drive = CellGameTests.drive(helper, new BlockPos(1, 1, 1));
        drive.insertCell(CellPartsGameTests.cell(CellContent.SMALL_STORAGE_CELL.get(), memory,
                List.of(CellContent.PARTITION_ADDON.get(), CellContent.VOID_OVERFLOW_ADDON.get()), deny));
        drive.insertCell(CellPartsGameTests.cell(CellContent.SMALL_STORAGE_CELL.get(), memory,
                List.of(CellContent.VOID_OVERFLOW_ADDON.get()), deny));
        helper.startSequence().thenWaitUntil(() -> CellGameTests.mounted(helper, drive, 1)).thenExecute(() -> {
            CellInventory both = CellGameTests.mounted(helper, drive, 0);
            helper.assertValueEqual(CellGameTests.insert(both, COBBLE, 10), 0L, "denied key");
            helper.assertValueEqual(CellGameTests.insert(both, STONE, 100_000), 100_000L, "other key, voided over");
            helper.assertValueEqual(CellGameTests.insert(both, COBBLE, 10), 0L, "denied key into a full cell");
            helper.assertValueEqual(both.amount(COBBLE), 0L, "denied key stored");

            CellInventory voidOnly = CellGameTests.mounted(helper, drive, 1);
            long fits = CellGameTests.insert(voidOnly, COBBLE, 100_000);
            helper.assertTrue(fits > 0 && fits < 100_000, "denied key without a partition: " + fits);
            helper.assertValueEqual(voidOnly.amount(COBBLE), fits, "all of the denied key stored, none voided");
        }).thenSucceed();
    }

    /**
     * Two drives on one network: a deny cell (cobblestone denied) at the higher priority takes stone, and cobblestone
     * goes past it to the plain cell at the lower priority.
     */
    private static void routing(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        DriveBlockEntity low = CellGameTests.drive(helper, NetworkGameTests.at(2));
        DriveBlockEntity high = CellGameTests.drive(helper, NetworkGameTests.at(3));
        low.setPriority(0);
        high.setPriority(10);
        high.insertCell(CellPartsGameTests.cell(CellContent.SMALL_STORAGE_CELL.get(),
                List.of(CellContent.MEMORY_MODULE_1.get()), List.of(CellContent.PARTITION_ADDON.get()),
                new CellFilter(List.of(CellFilter.Entry.of(COBBLE)), false, true)));
        low.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        helper.startSequence().thenWaitUntil(() -> {
            CellGameTests.mounted(helper, low, 0);
            CellGameTests.mounted(helper, high, 0);
            helper.assertTrue(low.network() != null && high.network() != null, "not in a network");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, POWER);
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 10), 10L, "cobblestone stored");
            helper.assertValueEqual(CellGameTests.mounted(helper, high, 0).amount(COBBLE), 0L,
                    "cobblestone in the deny cell");
            helper.assertValueEqual(CellGameTests.mounted(helper, low, 0).amount(COBBLE), 10L,
                    "cobblestone in the plain cell");
            helper.assertValueEqual(NetworkGameTests.insert(network, STONE, 10), 10L, "stone stored");
            helper.assertValueEqual(CellGameTests.mounted(helper, high, 0).amount(STONE), 10L,
                    "stone in the deny cell");
        }).thenSucceed();
    }

    /**
     * The deny flag survives the cell item's save and load and still refuses the denied key once mounted; a cell saved
     * before deny mode loads as an allow list; the tooltip counts a deny list's entries.
     */
    private static void saved(GameTestHelper helper) {
        ItemStack cell = CellPartsGameTests.cell(CellContent.SMALL_STORAGE_CELL.get(),
                List.of(CellContent.MEMORY_MODULE_1.get()), List.of(CellContent.PARTITION_ADDON.get()),
                new CellFilter(List.of(CellFilter.Entry.of(COBBLE)), false, true));
        Tag saved = ItemStack.CODEC.encodeStart(CellGameTests.ops(helper), cell).getOrThrow();
        ItemStack loaded = ItemStack.CODEC.parse(CellGameTests.ops(helper), saved).getOrThrow();
        CellData data = CellGameTests.data(helper, loaded);
        helper.assertTrue(data.filter().deny(), "deny flag after a reload");
        helper.assertValueEqual(tooltipArgs(helper, loaded, StorageCellItem.DENY_KEY), List.<Object>of(1),
                "deny tooltip");

        CellFilter old = CellFilter.CODEC
                .parse(helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE),
                        JsonParser.parseString("""
                                {"entries": [{"key": {"kind": "item", "id": "minecraft:cobblestone"}}], "fuzzy": false}
                                """))
                .getOrThrow();
        helper.assertTrue(!old.deny() && old.entries().size() == 1, "old filter loads as an allow list: " + old);

        DriveBlockEntity drive = CellGameTests.drive(helper, new BlockPos(1, 1, 1));
        drive.insertCell(loaded);
        helper.startSequence().thenWaitUntil(() -> CellGameTests.mounted(helper, drive, 0)).thenExecute(() -> {
            CellInventory mounted = CellGameTests.mounted(helper, drive, 0);
            helper.assertValueEqual(CellGameTests.insert(mounted, COBBLE, 5), 0L, "denied key after a reload");
            helper.assertValueEqual(CellGameTests.insert(mounted, STONE, 5), 5L, "other key after a reload");
        }).thenSucceed();
    }

    private static void check(GameTestHelper helper, boolean actual, boolean expected, String what) {
        helper.assertValueEqual(actual, expected, what);
    }

    @SuppressWarnings("deprecation") // the override hook is deprecated for callers; tests call it like the tooltip
    private static List<Object> tooltipArgs(GameTestHelper helper, ItemStack stack, String key) {
        List<Component> lines = new ArrayList<>();
        stack.getItem().appendHoverText(stack, Item.TooltipContext.EMPTY, TooltipDisplay.DEFAULT, lines::add,
                TooltipFlag.NORMAL);
        for (Component line : lines) {
            if (line.getContents() instanceof TranslatableContents contents && contents.getKey().equals(key)) {
                return List.of(contents.getArgs());
            }
        }
        throw helper.assertionException(Component.literal("No tooltip line " + key + " in " + lines));
    }
}
