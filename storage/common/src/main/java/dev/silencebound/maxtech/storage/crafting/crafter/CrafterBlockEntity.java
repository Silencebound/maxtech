package dev.silencebound.maxtech.storage.crafting.crafter;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.crafting.assembler.AssemblerCore;
import dev.silencebound.maxtech.storage.crafting.assembler.CraftingDisplay;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderDevice;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderLogic;
import dev.silencebound.maxtech.storage.crafting.provider.StuckReason;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * The crafter's block entity (B09 item 29): a pattern provider ({@link ProviderLogic} in crafter mode) whose machine is
 * its own {@link AssemblerCore}. Its nine slots take crafting, stonecutting and smithing patterns, which the network
 * registers like any provider's; a job's batch is crafted on the spot from the inputs the job hands over, the network
 * pays {@code crafting.crafterEnergyPerCraft} MU per craft in the same transaction, and the provider's output task
 * pulls the results from the buffer into the network. The patterns and any results still in the buffer drop with the
 * block, in its item. Never ticks.
 */
public final class CrafterBlockEntity extends StorageDeviceBlockEntity implements ProviderDevice, CraftingDisplay {
    /** How far beyond block reach the menu stays usable, as for vanilla containers. */
    private static final double RANGE_BUFFER = 4.0;

    private final AssemblerCore core = new AssemblerCore(this::setChanged, this::displayChanged,
            StorageServerConfig.CRAFTING_CRAFTER_ENERGY_PER_CRAFT);
    private final ProviderLogic logic = new ProviderLogic(this, this::getBlockPos, this::facing, this::setChanged,
            core);

    public CrafterBlockEntity(BlockPos pos, BlockState state) {
        super(CrafterContent.BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Role role() {
        return Role.TRANSFER;
    }

    /** Logic can turn it off (M7L): it takes no batch until it is turned on again. */
    @Override
    public boolean switchable() {
        return true;
    }

    /** One operation a second at rest; results come in bursts when a batch is crafted. */
    @Override
    public int operationsPerSecond() {
        return 1;
    }

    /** The side of the window. */
    public Direction facing() {
        return getBlockState().getValue(CrafterBlock.FACING);
    }

    @Override
    public ProviderLogic logic() {
        return logic;
    }

    /** The crafting part: the buffer, the crafts and the display. */
    public AssemblerCore core() {
        return core;
    }

    @Override
    public boolean stillValid(Player player) {
        return !isRemoved() && player.isWithinBlockInteractionRange(getBlockPos(), RANGE_BUFFER);
    }

    @Override
    public ItemStack craftingItem() {
        return level == null ? ItemStack.EMPTY : core.craftingItem(level.getGameTime());
    }

    @Override
    public int windowMask() {
        return CraftingDisplay.bit(facing());
    }

    /**
     * At a glance: what it crafts now, or its lock while that holds batches (B09 item 26), and how many of its slots
     * hold a pattern.
     */
    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        ItemStack crafting = craftingItem();
        if (logic.locked() != null) {
            ProviderDevice.glance(logic, sink);
        } else if (!crafting.isEmpty()) {
            sink.line(Component.translatable(CrafterContent.INFO_CRAFTING, crafting.getHoverName()));
        }
        int held = logic.patterns().size();
        sink.pips(Component.translatable(CrafterContent.INFO_PATTERNS, held, ProviderLogic.BASE_SLOTS), held,
                ProviderLogic.BASE_SLOTS);
    }

    /** Why it holds a craft up, if it does. */
    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        ProviderDevice.details(logic, sink);
        StuckReason stuck = logic.stuck();
        if (stuck != null) {
            sink.line(
                    Component.translatable(CrafterContent.INFO_STUCK, Component.translatable(stuck.translationKey())));
        }
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
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            logic.start(server);
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        logic.write(components);
        core.write(components, ModDataComponents.CRAFTER_BUFFER.get());
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        logic.read(components);
        core.read(components, ModDataComponents.CRAFTER_BUFFER.get());
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        logic.save(output);
        core.save(output);
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        logic.load(input);
        core.load(input);
        // A client's update carries the display; a save never does, so a loaded crafter shows nothing.
        core.loadDisplay(input);
    }
}
