package dev.silencebound.maxtech.storage.tool.setup;

import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.tool.blueprint.Blueprint;
import dev.silencebound.maxtech.storage.tool.blueprint.Blueprints;
import dev.silencebound.maxtech.storage.tool.blueprint.SettingsCopyable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Copies the devices in a box onto a {@link SetupBlueprint}: every Storage block with a block entity (devices, drives,
 * cables, machines) by its state, with its settings and its parts' settings where the player may configure them, and a
 * conduit with its colour. Other blocks are left out; the box shrinks to what was copied. Server thread.
 */
public final class SetupCopy {
    private SetupCopy() {
    }

    /** Why a copy failed, or that it worked. */
    public enum Status {
        COPIED, TOO_LARGE, UNLOADED, EMPTY, TOO_MANY, TOO_BIG
    }

    /**
     * What {@link #copy} did.
     *
     * @param blueprint
     *            present only for {@link Status#COPIED}
     * @param withoutSettings
     *            devices and parts copied without settings, because the player may not configure them
     */
    public record Result(Status status, Optional<SetupBlueprint> blueprint, int withoutSettings) {
        static Result failed(Status status) {
            return new Result(status, Optional.empty(), 0);
        }
    }

    /** Copies the box between corners {@code a} and {@code b} (both included) for {@code player}. */
    public static Result copy(Player player, ServerLevel level, BlockPos a, BlockPos b) {
        BlockPos min = BlockPos.min(a, b);
        BlockPos max = BlockPos.max(a, b);
        if (max.getX() - min.getX() >= SetupBlueprint.MAX_SIZE || max.getY() - min.getY() >= SetupBlueprint.MAX_SIZE
                || max.getZ() - min.getZ() >= SetupBlueprint.MAX_SIZE) {
            return Result.failed(Status.TOO_LARGE);
        }
        if (!level.hasChunksAt(min, max)) {
            return Result.failed(Status.UNLOADED);
        }
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockState state = level.getBlockState(pos);
            if (state.hasBlockEntity()
                    && BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals(MaxtechStorage.MOD_ID)) {
                if (found.size() >= SetupBlueprint.MAX_ENTRIES) {
                    return Result.failed(Status.TOO_MANY);
                }
                found.add(pos.immutable());
            }
        }
        if (found.isEmpty()) {
            return Result.failed(Status.EMPTY);
        }
        BlockPos low = found.getFirst();
        BlockPos high = low;
        for (BlockPos pos : found) {
            low = BlockPos.min(low, pos);
            high = BlockPos.max(high, pos);
        }
        int withoutSettings = 0;
        List<SetupBlueprint.Entry> entries = new ArrayList<>(found.size());
        for (BlockPos pos : found) {
            Optional<Blueprint> settings = Optional.empty();
            if (level.getBlockEntity(pos) instanceof SettingsCopyable device) {
                if (NetworkAccess.canConfigure(player, device)) {
                    settings = Optional.of(Blueprints.copy(device));
                } else {
                    withoutSettings++;
                }
            }
            List<SetupBlueprint.Part> parts = new ArrayList<>();
            CableParts cable = StorageCableBlock.parts(level, pos);
            if (cable != null) {
                for (CablePart part : cable.all()) {
                    Optional<Blueprint> partSettings = Optional.empty();
                    if (part instanceof SettingsCopyable device) {
                        if (NetworkAccess.canConfigure(player, device)) {
                            partSettings = Optional.of(Blueprints.copy(device));
                        } else {
                            withoutSettings++;
                        }
                    }
                    parts.add(new SetupBlueprint.Part(part.face(), part.type().id(), partSettings));
                }
            }
            Optional<DyeColor> colour = level.getBlockEntity(pos) instanceof ConduitBlockEntity conduit
                    ? Optional.ofNullable(conduit.conduit().colour())
                    : Optional.empty();
            entries.add(new SetupBlueprint.Entry(pos.subtract(low), level.getBlockState(pos), settings, parts, colour));
        }
        Vec3i size = high.subtract(low).offset(1, 1, 1);
        SetupBlueprint blueprint = new SetupBlueprint(size, entries);
        if (blueprint.weight() > SetupBlueprint.MAX_WEIGHT
                || blueprint.encodedSize(level.registryAccess()) > SetupBlueprint.MAX_BYTES) {
            return Result.failed(Status.TOO_BIG);
        }
        return new Result(Status.COPIED, Optional.of(blueprint), withoutSettings);
    }
}
