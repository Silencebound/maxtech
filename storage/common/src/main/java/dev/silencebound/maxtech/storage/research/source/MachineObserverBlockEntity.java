package dev.silencebound.maxtech.storage.research.source;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import dev.silencebound.maxtech.lib.save.SaveFormat;
import dev.silencebound.maxtech.lib.save.VersionedBlockEntity;
import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A machine observer's owner and count. Every finished run of the watched machine is counted here
 * ({@link ObserverSource}); every {@code research.sources.observer.runsPerPoint} runs give the owner's team one point,
 * at most {@code research.sources.observer.dailyCap} per day of game time, both scaled by the preset. No ticker.
 */
public final class MachineObserverBlockEntity extends VersionedBlockEntity {
    /** Ticks in one in-game day; the daily cap counts days of game time, which never stops or runs back. */
    static final long DAY_TICKS = 24_000L;

    private OwnerRecord owner = OwnerRecord.UNOWNED;
    private ObserverTally tally = new ObserverTally();

    public MachineObserverBlockEntity(BlockPos pos, BlockState state) {
        super(SourceContent.OBSERVER_BLOCK_ENTITY.get(), pos, state);
    }

    public OwnerRecord ownerRecord() {
        return owner;
    }

    /** Sets the owner, normally the player who placed the observer. Server side. */
    public void setOwner(OwnerRecord owner) {
        this.owner = owner;
        setChanged();
    }

    /** Points this observer gave on the day it last counted a run. */
    public int pointsToday() {
        return tally.pointsToday();
    }

    /** Runs counted towards the next point. */
    public int runs() {
        return tally.runs();
    }

    /**
     * Counts one finished run of the watched machine and gives the owner's team a point when one is due; returns the
     * points added. An unowned observer counts for nobody.
     */
    public long machineRan(ServerLevel level) {
        if (!owner.isOwned() || !Research.active()) {
            return 0;
        }
        double scale = SourcePoints.scale();
        int point = tally.record(level.getGameTime() / DAY_TICKS,
                SourcePoints.runsPerPoint(StorageServerConfig.OBSERVER_RUNS_PER_POINT.getAsInt(), scale),
                SourcePoints.dailyCap(StorageServerConfig.OBSERVER_DAILY_CAP.getAsInt(), scale));
        setChanged();
        if (point == 0) {
            return 0;
        }
        MinecraftServer server = level.getServer();
        ResearchTeam team = Research.data(server).team(server, owner);
        if (team == null) {
            return 0;
        }
        // The preset already shortened or lengthened the runs per point, so each award is one whole point.
        Research.addPoints(server, team, point);
        return point;
    }

    /**
     * The observer as an item. Its count and day travel in vanilla's block entity data, so breaking and placing it
     * again keeps the daily cap; a fresh observer drops a plain item that stacks. Placing restores the data before
     * {@code setPlacedBy} sets the new placer as owner.
     */
    public ItemStack toItemStack(HolderLookup.Provider registries) {
        ItemStack stack = new ItemStack(getBlockState().getBlock());
        if (tally.runs() > 0 || tally.pointsToday() > 0) {
            stack.set(DataComponents.BLOCK_ENTITY_DATA,
                    TypedEntityData.<BlockEntityType<?>>of(getType(), saveCustomOnly(registries)));
        }
        return stack;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (owner.isOwned()) {
            output.store("owner", OwnerRecord.CODEC, owner);
        }
        output.putInt("runs", tally.runs());
        output.putLong("day", tally.day());
        output.putInt("points_today", tally.pointsToday());
    }

    @Override
    protected final SaveFormat saveFormat() {
        return StorageSaveFormats.BLOCK_ENTITIES;
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        owner = input.read("owner", OwnerRecord.CODEC).orElse(OwnerRecord.UNOWNED);
        tally = new ObserverTally(input.getIntOr("runs", 0), input.getLongOr("day", 0L),
                input.getIntOr("points_today", 0));
    }
}
