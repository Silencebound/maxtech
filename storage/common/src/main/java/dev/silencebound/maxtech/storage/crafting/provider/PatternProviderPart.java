package dev.silencebound.maxtech.storage.crafting.provider;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.part.PartType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * The pattern provider as a part on a storage cable's face: the same {@link ProviderLogic} as the block, running its
 * patterns on the block beyond the face. Its patterns and setting stay in the item when it comes off. Never ticks.
 */
public final class PatternProviderPart extends CablePart implements ProviderDevice {
    private final ProviderLogic logic;

    public PatternProviderPart(PartType type, CableParts host, Direction face) {
        super(type, host, face);
        logic = new ProviderLogic(this, this::pos, this::face, host::changed);
    }

    @Override
    public Role role() {
        return Role.TRANSFER;
    }

    /** Logic can turn it off (M7L): its work pauses until it is turned on again. */
    @Override
    public boolean switchable() {
        return true;
    }

    /** As the block: one operation a second at rest. */
    @Override
    public int operationsPerSecond() {
        return 1;
    }

    @Override
    public ProviderLogic logic() {
        return logic;
    }

    @Override
    public boolean stillValid(Player player) {
        return !isRemoved() && player.isWithinBlockInteractionRange(pos(), PatternProviderBlockEntity.RANGE_BUFFER);
    }

    @Override
    protected void start(ServerLevel level) {
        logic.start(level);
    }

    /** Only the block the part faces matters; an unknown neighbour may be it. */
    @Override
    public void neighbourChanged(@Nullable BlockPos from) {
        if (from == null) {
            // A block update: the lock mode may read the redstone signal (B09 item 26).
            logic.redstoneChanged();
        }
        if (from == null || from.equals(facedPos())) {
            logic.targetChanged();
        }
    }

    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        ProviderDevice.glance(logic, sink);
    }

    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        ProviderDevice.details(logic, sink);
    }

    /** Opens the provider's GUI for players its owner lets in. */
    @Override
    public InteractionResult use(Player player) {
        if (player instanceof ServerPlayer serverPlayer && NetworkAccess.canConfigure(player, this)) {
            ProviderMenu.open(serverPlayer, this, pos(), type().item().getName(toItem()));
        }
        return InteractionResult.SUCCESS;
    }

    /** The part's item with its patterns and setting. */
    @Override
    public ItemStack toItem() {
        ItemStack stack = super.toItem();
        DataComponentMap.Builder components = DataComponentMap.builder();
        logic.write(components);
        stack.applyComponents(components.build());
        return stack;
    }

    @Override
    protected void readItem(ItemStack stack) {
        logic.read(stack);
    }

    @Override
    protected void save(ValueOutput output) {
        super.save(output);
        logic.save(output);
    }

    @Override
    protected void load(ValueInput input) {
        super.load(input);
        logic.load(input);
    }
}
