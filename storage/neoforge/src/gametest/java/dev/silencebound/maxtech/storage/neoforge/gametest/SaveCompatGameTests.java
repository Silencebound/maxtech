package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.save.SaveFormat;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.cell.CellCasing;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.CellData;
import dev.silencebound.maxtech.storage.cell.CellInventory;
import dev.silencebound.maxtech.storage.cell.CellStorage;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.condition.ConditionContent;
import dev.silencebound.maxtech.storage.condition.ConditionSwitchBlockEntity;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * Save compatibility (PRD §8.2): Storage block entities, cable parts, cell items and cell storage save their format
 * version, data saved before versions existed (pre-1.0) loads and saves again unchanged, and data from a newer build
 * loads with nothing lost and keeps its newer stamp.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class SaveCompatGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_7x2x3");
    private static final List<String> NAMES = new ArrayList<>();
    private static final DeviceLabel LABEL = DeviceLabel.of("Furnaces", DyeColor.ORANGE);

    static {
        StorageTestEnvironment.init();
        register("save_compat_unversioned_block_entity", SaveCompatGameTests::unversioned);
        register("save_compat_newer_block_entity", SaveCompatGameTests::newer);
        register("save_compat_unversioned_cell_item", SaveCompatGameTests::cellItem);
        register("save_compat_unversioned_cell_storage", SaveCompatGameTests::cellStorage);
        register("save_compat_unversioned_cable_part", SaveCompatGameTests::cablePart);
        register("save_compat_cable_without_render_value", SaveCompatGameTests::cableWithoutRenderValue);
    }

    private SaveCompatGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("save_compat"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 100);
        }
    }

    /** A labelled condition switch saved the way the chunk saves it. */
    private static CompoundTag saved(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, ConditionContent.SWITCH.get());
        ConditionSwitchBlockEntity entity = helper.getBlockEntity(pos, ConditionSwitchBlockEntity.class);
        entity.setLabel(LABEL);
        return entity.saveWithFullMetadata(helper.getLevel().registryAccess());
    }

    /** Loads {@code tag} into a new block entity at {@code pos}, as a chunk load does, outside the level. */
    private static ConditionSwitchBlockEntity load(GameTestHelper helper, BlockPos pos, CompoundTag tag) {
        BlockPos abs = helper.absolutePos(pos);
        BlockEntity loaded = BlockEntity.loadStatic(abs, helper.getLevel().getBlockState(abs), tag,
                helper.getLevel().registryAccess());
        if (!(loaded instanceof ConditionSwitchBlockEntity entity)) {
            throw helper.assertionException(Component.literal("the switch did not load"));
        }
        return entity;
    }

    /**
     * Data without a version (everything saved before 1.0) loads as version 1 and saves again exactly as it was, plus
     * the version.
     */
    private static void unversioned(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        CompoundTag tag = saved(helper, pos);
        helper.assertValueEqual(StorageSaveFormats.BLOCK_ENTITIES.currentVersion(),
                tag.getIntOr(SaveFormat.VERSION_KEY, 0), "saved version");
        CompoundTag old = tag.copy();
        old.remove(SaveFormat.VERSION_KEY);

        ConditionSwitchBlockEntity loaded = load(helper, pos, old);
        helper.assertValueEqual(LABEL, loaded.label(), "label after loading pre-1.0 data");
        CompoundTag again = loaded.saveWithFullMetadata(helper.getLevel().registryAccess());
        helper.assertValueEqual(tag, again, "data saved again after loading pre-1.0 data");
        helper.assertTrue(!old.contains(SaveFormat.VERSION_KEY), "loading changed the saved tag");
        helper.succeed();
    }

    /** Data from a newer build (a downgraded mod) loads unchanged instead of being dropped. */
    private static void newer(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        CompoundTag tag = saved(helper, pos);
        tag.putInt(SaveFormat.VERSION_KEY, StorageSaveFormats.BLOCK_ENTITIES.currentVersion() + 5);
        ConditionSwitchBlockEntity loaded = load(helper, pos, tag);
        helper.assertValueEqual(LABEL, loaded.label(), "label after loading newer data");
        CompoundTag again = loaded.saveWithFullMetadata(helper.getLevel().registryAccess());
        helper.assertValueEqual(StorageSaveFormats.BLOCK_ENTITIES.currentVersion() + 5,
                again.getIntOr(SaveFormat.VERSION_KEY, 0), "stamp after saving newer data again");
        helper.succeed();
    }

    /** Removes every version key in {@code tag} and below; returns how many there were. */
    private static int stripVersions(Tag tag) {
        int removed = 0;
        if (tag instanceof CompoundTag compound) {
            if (compound.contains(SaveFormat.VERSION_KEY)) {
                compound.remove(SaveFormat.VERSION_KEY);
                removed++;
            }
            for (String key : compound.keySet()) {
                Tag child = compound.get(key);
                if (child != null) {
                    removed += stripVersions(child);
                }
            }
        } else if (tag instanceof ListTag list) {
            for (Tag element : list) {
                removed += stripVersions(element);
            }
        }
        return removed;
    }

    /** Encodes {@code value}, strips the versions from a copy, decodes that and checks it encodes as before. */
    private static <T> T reload(GameTestHelper helper, Codec<T> codec, T value, String what) {
        RegistryOps<Tag> ops = CellGameTests.ops(helper);
        Tag saved = codec.encodeStart(ops, value).getOrThrow();
        Tag old = saved.copy();
        helper.assertTrue(stripVersions(old) > 0, what + " saved no version");
        T loaded = codec.parse(ops, old).getOrThrow();
        helper.assertValueEqual(saved, codec.encodeStart(ops, loaded).getOrThrow(), what + " saved again");
        return loaded;
    }

    /** A cell item saved without a version (pre-1.0) loads as the same stack. */
    private static void cellItem(GameTestHelper helper) {
        ItemStack cell = CellGameTests.cell(CellContent.ITEM_TYPE.id(), 2);
        ItemStack loaded = reload(helper, ItemStack.CODEC, cell, "cell item");
        helper.assertTrue(ItemStack.matches(cell, loaded), "cell item after loading pre-1.0 data");
        helper.succeed();
    }

    /** Cell storage saved without a version (pre-1.0) loads with the same contents. */
    private static void cellStorage(GameTestHelper helper) {
        CellStorage storage = new CellStorage();
        CellData data = CellData.of(CellContent.ITEM_TYPE.id(),
                Collections.nCopies(1, CellContent.MEMORY_MODULE_1.get()));
        long id = storage.newId();
        CellInventory cell = storage.mount(id, CellContent.ITEM_TYPE.get(), CellCasing.SMALL, data, () -> {
        });
        if (cell == null) {
            throw helper.assertionException(Component.literal("cell not mounted"));
        }
        try (Transaction transaction = Transaction.openRoot()) {
            cell.insert(ItemKey.of(Items.COBBLESTONE), 42, transaction);
            transaction.commit();
        }
        CellStorage loaded = reload(helper, CellStorage.TYPE.codecFactory().create(helper.getLevel()), storage,
                "cell storage");
        helper.assertValueEqual(42L, loaded.amount(id, ItemKey.of(Items.COBBLESTONE)),
                "cobblestone after loading pre-1.0 data");
        helper.succeed();
    }

    /** A cable with a labelled part saved without part versions (pre-1.0) loads and saves again unchanged. */
    private static void cablePart(GameTestHelper helper) {
        BlockPos abs = helper.absolutePos(new BlockPos(1, 1, 1));
        ConduitBlock.placeConduit(helper.getLevel(), abs, CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        CableParts parts = StorageCableBlock.parts(helper.getLevel(), abs);
        if (parts == null) {
            throw helper.assertionException(Component.literal("no cable parts"));
        }
        ItemStack stack = new ItemStack(ConditionContent.SWITCH_PART_ITEM.get());
        stack.set(ModDataComponents.DEVICE_LABEL.get(), LABEL);
        helper.assertTrue(parts.place(Direction.EAST, ConditionContent.SWITCH_PART, null, stack), "not placed");
        BlockEntity cable = helper.getLevel().getBlockEntity(abs);
        if (cable == null) {
            throw helper.assertionException(Component.literal("no cable"));
        }
        CompoundTag tag = cable.saveWithFullMetadata(helper.getLevel().registryAccess());
        CompoundTag old = tag.copy();
        helper.assertTrue(stripVersions(old) > 0, "the part saved no version");
        BlockEntity loaded = BlockEntity.loadStatic(abs, helper.getLevel().getBlockState(abs), old,
                helper.getLevel().registryAccess());
        CablePart part = loaded == null ? null : eastPart(loaded);
        if (loaded == null || part == null) {
            throw helper.assertionException(Component.literal("no part after loading"));
        }
        helper.assertValueEqual(LABEL, part.label(), "part label after loading pre-1.0 data");
        helper.assertValueEqual(tag, loaded.saveWithFullMetadata(helper.getLevel().registryAccess()),
                "cable saved again after loading pre-1.0 data");
        helper.succeed();
    }

    /**
     * Cables never set Lib's render value or contents, so neither their saved data nor their update tag carries those
     * keys: their saves and packets stay as they were before conduits had them.
     */
    private static void cableWithoutRenderValue(GameTestHelper helper) {
        BlockPos abs = helper.absolutePos(new BlockPos(1, 1, 1));
        ConduitBlock.placeConduit(helper.getLevel(), abs, CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        BlockEntity cable = helper.getLevel().getBlockEntity(abs);
        if (cable == null) {
            throw helper.assertionException(Component.literal("no cable"));
        }
        CompoundTag saved = cable.saveWithFullMetadata(helper.getLevel().registryAccess());
        CompoundTag update = cable.getUpdateTag(helper.getLevel().registryAccess());
        helper.assertTrue(saved.contains("face_modes") && update.contains("face_modes"), "not a conduit's data");
        helper.assertFalse(saved.contains("render_value"), "the cable saved a render value");
        helper.assertFalse(update.contains("render_value"), "the cable synced a render value");
        helper.assertFalse(saved.contains("contents"), "the cable saved conduit contents");
        helper.assertFalse(update.contains("contents"), "the cable synced conduit contents");
        helper.succeed();
    }

    private static @Nullable CablePart eastPart(BlockEntity cable) {
        return cable instanceof ConduitBlockEntity conduit && conduit.conduit().state() instanceof CableParts parts
                ? parts.part(Direction.EAST)
                : null;
    }
}
