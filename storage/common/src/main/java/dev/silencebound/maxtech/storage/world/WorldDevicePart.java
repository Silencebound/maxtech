package dev.silencebound.maxtech.storage.world;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.part.PartType;
import dev.silencebound.maxtech.storage.tool.blueprint.DeviceSettings;
import dev.silencebound.maxtech.storage.tool.blueprint.SettingsCopyable;
import net.minecraft.core.BlockPos;
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
 * A collector, placer or breaker as a part on a storage cable's face: the same {@link WorldWorker} as the block, at the
 * cable's position. A placer or breaker works on the block beyond its face; a collector's range is centred on the
 * cable. Its settings and tool stay in its item. Never ticks.
 */
public final class WorldDevicePart extends CablePart implements SettingsCopyable {
    private final WorldWorker worker;

    public WorldDevicePart(PartType type, CableParts host, Direction face, WorldKind kind) {
        super(type, host, face);
        this.worker = WorldWorker.create(kind, new WorldWorker.Host(deviceLink(), this, () -> host().level(), this::pos,
                this::face, this::isRemoved, host()::changed));
    }

    @Override
    public Role role() {
        return Role.TRANSFER;
    }

    public WorldWorker worker() {
        return worker;
    }

    /** A part taken off its cable leaves its network: it stops waiting for research then. */
    @Override
    public void networkChanged(@Nullable StorageNetwork network) {
        if (isRemoved()) {
            worker.removedFromLevel();
        }
    }

    @Override
    protected void start(ServerLevel level) {
        worker.start(level);
    }

    /** Any change around the cable may be the faced block or the redstone signal. */
    @Override
    public void neighbourChanged(@Nullable BlockPos from) {
        worker.neighbourChanged();
    }

    /** Opens the settings for players who may configure the device (checked in WorldMenu.open). */
    @Override
    public InteractionResult use(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            WorldMenu.open(serverPlayer, worker, pos(), type().item().getName(toItem()));
        }
        return InteractionResult.SUCCESS;
    }

    /** The placer's action at a glance (B09 item 25). */
    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        worker.addGlance(request, sink);
    }

    /** The mode and the placer's action. */
    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        worker.addDetails(request, sink);
    }

    /** The part's item with its settings and tool ({@link WorldSettings}). */
    @Override
    public DeviceSettings copyableSettings() {
        return WorldSettings.copyable(worker, host()::changed);
    }

    @Override
    public ItemStack toItem() {
        ItemStack stack = super.toItem();
        WorldSettings.write(stack, worker);
        return stack;
    }

    @Override
    protected void readItem(ItemStack stack) {
        worker.apply(WorldSettings.read(stack));
    }

    @Override
    protected void save(ValueOutput output) {
        super.save(output);
        worker.save(output);
    }

    @Override
    protected void load(ValueInput input) {
        super.load(input);
        worker.load(input);
    }
}
