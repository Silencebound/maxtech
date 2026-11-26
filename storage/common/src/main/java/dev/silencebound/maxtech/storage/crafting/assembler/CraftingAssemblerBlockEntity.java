package dev.silencebound.maxtech.storage.crafting.assembler;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.save.SaveFormat;
import dev.silencebound.maxtech.lib.save.VersionedBlockEntity;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.DeviceLink;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: the result buffer is a NeoForge item handler, crafted into inside the provider's transaction (PRD
// 7.2: transfers are transactions).
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * The crafting assembler (M7 C1): a pattern provider next to it hands it crafting patterns, and its
 * {@link AssemblerCore} crafts the whole batch at once, as a crafting table, stonecutter or smithing table would (B09
 * item 16), into a nine-slot result buffer. The provider pulls everything in the buffer into the network; hoppers can
 * take from it too. The buffer drops with the block, in its item. Behind its glass the item being crafted turns while
 * it crafts (B09 item 29): the core's display fields reach clients in the block entity's update. Never ticks; server
 * thread.
 */
public final class CraftingAssemblerBlockEntity extends VersionedBlockEntity implements CraftingDisplay {
    public static final int BUFFER_SLOTS = AssemblerCore.BUFFER_SLOTS;

    private final AssemblerCore core = new AssemblerCore(this::setChanged, this::displayChanged,
            StorageServerConfig.CRAFTING_ASSEMBLER_ENERGY_PER_CRAFT);

    public CraftingAssemblerBlockEntity(BlockPos pos, BlockState state) {
        super(AssemblerContent.BLOCK_ENTITY.get(), pos, state);
    }

    /** The crafting part: the buffer, the crafts and the display. */
    public AssemblerCore core() {
        return core;
    }

    /** What neighbours see: the result buffer, extract only. */
    public ResourceHandler<ItemResource> output() {
        return core.output();
    }

    /** See {@link AssemblerCore#recipeChanged()}. */
    public boolean recipeChanged() {
        return core.recipeChanged();
    }

    /** Whether the buffer has an empty slot, so a batch may fit. */
    public boolean hasRoom() {
        return core.hasRoom();
    }

    /** See {@link AssemblerCore#craft}; the network pays {@code crafting.assemblerEnergyPerCraft} MU per craft. */
    public boolean craft(ServerLevel level, CraftingPattern pattern, int crafts, Object2LongMap<StackKey> inputs,
            NetworkState network, DeviceLink payer) {
        return core.craft(level, pattern, crafts, inputs, network, payer);
    }

    /** The buffer's contents, for tests and the tooltip. */
    public List<ItemStack> contents() {
        return core.contents();
    }

    @Override
    public ItemStack craftingItem() {
        return level == null ? ItemStack.EMPTY : core.craftingItem(level.getGameTime());
    }

    /** The four sides are glass; top and bottom are not. */
    @Override
    public int windowMask() {
        return CraftingDisplay.HORIZONTAL;
    }

    private void displayChanged() {
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        core.saveDisplay(output, level == null ? 0 : level.getGameTime());
        return output.buildResult();
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        core.save(output);
    }

    @Override
    protected final SaveFormat saveFormat() {
        return StorageSaveFormats.BLOCK_ENTITIES;
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        core.load(input);
        // A client's update carries the display; a save never does, so a loaded block shows nothing.
        core.loadDisplay(input);
    }

    /** The buffer goes into the dropped item as vanilla's container component. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        core.write(components, DataComponents.CONTAINER);
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        core.read(components, DataComponents.CONTAINER);
    }
}
