package dev.silencebound.maxtech.storage.world;

import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.part.PartType;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * The network chest as a part on a storage cable's face: the same {@link NetworkChestView} as the block, seen by the
 * hopper or pipe on the block beyond the face, with its settings kept in its item. Never ticks.
 */
public final class NetworkChestPart extends CablePart {
    private final NetworkChestView view = new NetworkChestView(deviceLink(), this, () -> host().level(), this::pos,
            this::isRemoved, host()::changed);

    public NetworkChestPart(PartType type, CableParts host, Direction face) {
        super(type, host, face);
    }

    @Override
    public Role role() {
        return Role.ACCESS;
    }

    public NetworkChestView view() {
        return view;
    }

    @Override
    public void networkChanged(@Nullable StorageNetwork network) {
        view.networkChanged();
    }

    /** Starts the view; neighbours re-read the cable's item handler, which now answers on this face. */
    @Override
    protected void start(ServerLevel level) {
        view.start();
        // PLATFORM-LEAK: NeoForge's ILevelExtension; a pipe or hopper caching the cable's handlers sees the part.
        level.invalidateCapabilities(pos());
    }

    /** Opens the settings, whose second button opens the chest. */
    @Override
    public InteractionResult use(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            WorldMenu.open(serverPlayer, view, pos(), type().item().getName(toItem()));
        }
        return InteractionResult.SUCCESS;
    }

    /** The part's item with its settings ({@link WorldSettings}). */
    @Override
    public ItemStack toItem() {
        ItemStack stack = super.toItem();
        WorldSettings.write(stack, view);
        return stack;
    }

    @Override
    protected void readItem(ItemStack stack) {
        view.apply(WorldSettings.read(stack));
    }

    @Override
    protected void save(ValueOutput output) {
        super.save(output);
        view.save(output);
    }

    @Override
    protected void load(ValueInput input) {
        super.load(input);
        view.load(input);
    }
}
