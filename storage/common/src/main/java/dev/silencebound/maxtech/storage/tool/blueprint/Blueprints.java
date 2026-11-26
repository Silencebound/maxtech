package dev.silencebound.maxtech.storage.tool.blueprint;

import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The blueprint card's work on devices: finding the device a click hit, copying, pasting, and pasting along a row.
 * Server thread.
 */
public final class Blueprints {
    private static final Direction[] SIDES = Direction.values();

    private Blueprints() {
    }

    /** The device {@code hit} landed on: a part on a storage cable, else the block's own device; null for neither. */
    public static @Nullable SettingsCopyable at(BlockGetter level, BlockPos pos, BlockHitResult hit) {
        CablePart part = StorageCableBlock.partAt(level, pos, hit);
        if (part != null) {
            return part instanceof SettingsCopyable device ? device : null;
        }
        return level.getBlockEntity(pos) instanceof SettingsCopyable device ? device : null;
    }

    /** {@code device}'s settings as a card holds them, with its label (M7L) when it has one. */
    public static Blueprint copy(SettingsCopyable device) {
        DeviceSettings settings = device.copyableSettings();
        DataComponentPatch.Builder patch = DataComponentPatch.builder().set(settings.copy().get());
        if (device instanceof NetworkDevice labelled && !labelled.label().isEmpty()) {
            patch.set(ModDataComponents.DEVICE_LABEL.get(), labelled.label());
        }
        return new Blueprint(settings.kind(), patch.build());
    }

    /** Whether {@code blueprint} fits {@code device}: both are the same kind. */
    public static boolean fits(SettingsCopyable device, Blueprint blueprint) {
        return device.copyableSettings().kind().equals(blueprint.kind());
    }

    /**
     * Pastes {@code blueprint} onto {@code device}; false, changing nothing, when it is another kind. A card with a
     * label labels the device the same; a card without one leaves the device's label alone.
     */
    public static boolean paste(SettingsCopyable device, Blueprint blueprint) {
        DeviceSettings settings = device.copyableSettings();
        if (!settings.kind().equals(blueprint.kind())) {
            return false;
        }
        DataComponentMap components = blueprint.components();
        settings.paste().accept(components);
        DeviceLabel label = components.get(ModDataComponents.DEVICE_LABEL.get());
        if (label != null && device instanceof NetworkDevice labelled) {
            labelled.setLabel(label);
        }
        return true;
    }

    /**
     * Pastes {@code blueprint} onto {@code start} at {@code pos} and onto the devices of its kind in a straight line
     * through it, at most {@code limit} in all. The line runs across the clicked face ({@code face}; a part's own face
     * for a part): along the axis where a neighbour of the same kind sits, or, with neighbours on both axes, the one
     * the player looks along more. Devices the player may not configure are skipped but do not end the line. Parts line
     * up on the same face of neighbouring cables.
     */
    public static Result pasteRow(Player player, BlockGetter level, BlockPos pos, Direction face,
            SettingsCopyable start, Blueprint blueprint, int limit) {
        Direction across = start instanceof CablePart part ? part.face() : face;
        Direction.Axis axis = rowAxis(player.getViewVector(1.0F), level, pos, start, across.getAxis(), blueprint);
        int pasted = 0;
        int denied = 0;
        int visited = 1;
        if (pasteOne(player, start, blueprint)) {
            pasted++;
        } else {
            denied++;
        }
        if (axis == null) {
            return new Result(pasted, denied);
        }
        Direction positive = Direction.get(Direction.AxisDirection.POSITIVE, axis);
        boolean forward = true;
        boolean back = true;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int step = 1; (forward || back) && visited < limit; step++) {
            for (int sign = 1; sign >= -1 && visited < limit; sign -= 2) {
                if (sign > 0 ? !forward : !back) {
                    continue;
                }
                cursor.set(pos).move(positive, step * sign);
                SettingsCopyable next = neighbour(level, cursor, start);
                if (next == null || !fits(next, blueprint)) {
                    if (sign > 0) {
                        forward = false;
                    } else {
                        back = false;
                    }
                    continue;
                }
                visited++;
                if (pasteOne(player, next, blueprint)) {
                    pasted++;
                } else {
                    denied++;
                }
            }
        }
        return new Result(pasted, denied);
    }

    private static boolean pasteOne(Player player, SettingsCopyable device, Blueprint blueprint) {
        return NetworkAccess.canConfigure(player, device) && paste(device, blueprint);
    }

    /** The axis across {@code excluded} with a fitting neighbour, the one nearer the look when both have one. */
    private static Direction.@Nullable Axis rowAxis(Vec3 look, BlockGetter level, BlockPos pos, SettingsCopyable start,
            Direction.Axis excluded, Blueprint blueprint) {
        Direction.Axis best = null;
        double bestLook = -1;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction side : SIDES) {
            if (side.getAxis() == excluded) {
                continue;
            }
            cursor.setWithOffset(pos, side);
            SettingsCopyable next = neighbour(level, cursor, start);
            if (next == null || !fits(next, blueprint)) {
                continue;
            }
            double along = Math.abs(side.getAxis().choose(look.x, look.y, look.z));
            if (along > bestLook) {
                bestLook = along;
                best = side.getAxis();
            }
        }
        return best;
    }

    /** The device at {@code pos} that continues a row of {@code start}: a part on the same face, or a block device. */
    private static @Nullable SettingsCopyable neighbour(BlockGetter level, BlockPos pos, SettingsCopyable start) {
        if (start instanceof CablePart part) {
            CableParts parts = StorageCableBlock.parts(level, pos);
            CablePart next = parts == null ? null : parts.part(part.face());
            return next instanceof SettingsCopyable device ? device : null;
        }
        return level.getBlockEntity(pos) instanceof SettingsCopyable device ? device : null;
    }

    /** What a row paste did: devices pasted onto, and devices of the kind skipped for lack of access. */
    public record Result(int pasted, int denied) {
    }
}
