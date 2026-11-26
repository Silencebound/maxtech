package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import org.jspecify.annotations.Nullable;

/**
 * GameTest-only content for the catalyst and byproduct tests (M7C item 3): an item machine with nine slots on every
 * side. Slots 0 to 6 take inputs, slot 7 holds the main output and slot 8 a second output, so a pull pass meets the
 * main output before the second one. It never ticks; a test calls {@link ItemMachine#process} to run its crafts.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class CatalystTestContent {
    private static final RegistryHelper.Registrar<Block> BLOCKS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.BLOCK);
    private static final RegistryHelper.Registrar<BlockEntityType<?>> BLOCK_ENTITIES = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE);

    static final RegistryEntry<Block, ItemMachineBlock> ITEM_MACHINE = BLOCKS.register("test_item_machine",
            id -> new ItemMachineBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id))));
    static final RegistryEntry<BlockEntityType<?>, BlockEntityType<ItemMachine>> ITEM_MACHINE_TYPE = BLOCK_ENTITIES
            .register("test_item_machine", () -> new BlockEntityType<>(ItemMachine::new, Set.of(ITEM_MACHINE.get())));

    static final int MAIN_SLOT = 7;
    static final int SECOND_SLOT = 8;

    private CatalystTestContent() {
    }

    /** Loads the class during mod construction so everything above is queued in time. */
    static void init() {
    }

    @SubscribeEvent
    static void capabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, ITEM_MACHINE_TYPE.get(), (machine, side) -> machine.slots);
    }

    static final class ItemMachineBlock extends Block implements EntityBlock {
        ItemMachineBlock(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return simpleCodec(ItemMachineBlock::new);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new ItemMachine(pos, state);
        }
    }

    static final class ItemMachine extends BlockEntity {
        final ItemStacksResourceHandler slots = new ItemStacksResourceHandler(9);

        ItemMachine(BlockPos pos, BlockState state) {
            super(ITEM_MACHINE_TYPE.get(), pos, state);
        }

        /** How many of {@code item} the slots hold. */
        int count(Item item) {
            int total = 0;
            for (int i = 0; i < slots.size(); i++) {
                if (slots.getResource(i).is(item)) {
                    total += slots.getAmountAsInt(i);
                }
            }
            return total;
        }

        /** Whether every slot is empty. */
        boolean empty() {
            for (int i = 0; i < slots.size(); i++) {
                if (!slots.getResource(i).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Runs every craft the input slots allow: each takes one {@code input}, and one {@code catalyst} unless it is
         * null; it adds one {@code main} to slot 7 and, unless {@code second} is null, one {@code second} to slot 8.
         * With {@code consumeCatalyst} false the catalyst stays in its input slot, as a mould does.
         */
        void process(Item input, @Nullable Item catalyst, boolean consumeCatalyst, Item main, @Nullable Item second) {
            while (take(input, catalyst, consumeCatalyst)) {
                add(MAIN_SLOT, main);
                if (second != null) {
                    add(SECOND_SLOT, second);
                }
            }
            setChanged();
        }

        /** Adds one {@code item} to the second output slot, as another source would. */
        void addSecond(Item item) {
            add(SECOND_SLOT, item);
            setChanged();
        }

        private boolean take(Item input, @Nullable Item catalyst, boolean consumeCatalyst) {
            int inputSlot = find(input);
            int catalystSlot = catalyst == null ? -1 : find(catalyst);
            if (inputSlot < 0 || (catalyst != null && catalystSlot < 0)) {
                return false;
            }
            shrink(inputSlot);
            if (catalyst != null && consumeCatalyst) {
                shrink(catalystSlot);
            }
            return true;
        }

        private int find(Item item) {
            for (int i = 0; i < MAIN_SLOT; i++) {
                if (slots.getResource(i).is(item) && slots.getAmountAsInt(i) > 0) {
                    return i;
                }
            }
            return -1;
        }

        private void shrink(int slot) {
            int left = slots.getAmountAsInt(slot) - 1;
            slots.set(slot, left == 0 ? ItemResource.EMPTY : slots.getResource(slot), left);
        }

        private void add(int slot, Item item) {
            slots.set(slot, ItemResource.of(item), slots.getAmountAsInt(slot) + 1);
        }
    }
}
