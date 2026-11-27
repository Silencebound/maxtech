package dev.silencebound.maxtech.lib.info;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceModes;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.info.BlockInfoProvider;
import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.conduit.ConduitData;
import java.util.Locale;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import org.jspecify.annotations.Nullable;

/**
 * Describes a conduit block: the conduit's type, tier and colour, what the type's own {@link BlockInfoProvider} adds
 * and, in {@link BlockInfoRequest.Detail#FULL}, its face modes. Stateless: one instance serves every conduit block.
 */
public final class ConduitInfo implements BlockInfoProvider {
    public static final ConduitInfo INSTANCE = new ConduitInfo();

    public static final String CONDUIT_KEY = "info.maxtech.conduit";
    public static final String UNCOLOURED_KEY = "info.maxtech.conduit.uncoloured";
    public static final String FACE_KEY = "info.maxtech.conduit.face";
    private static final String FACE_MODE_KEY_PREFIX = "info.maxtech.face_mode.";
    private static final String SIDE_KEY_PREFIX = "info.maxtech.side.";

    private ConduitInfo() {
    }

    @Override
    public void addInfo(BlockInfoRequest request, BlockInfoSink sink) {
        if (!(request.level().getBlockEntity(request.pos()) instanceof ConduitBlockEntity conduitEntity)) {
            return;
        }
        ConduitData conduit = conduitEntity.conduit();
        ConduitType type = conduit.type();
        boolean full = request.detail() == BlockInfoRequest.Detail.FULL;
        Component name = Component.translatable(CONDUIT_KEY,
                Component.translatable(type.item().asItem().getDescriptionId()), Component.literal(type.tier()),
                colourName(conduit.colour()));
        if (type instanceof BlockInfoProvider own) {
            // The type adds its own entries (Storage's cables), which carry the glance; this provider is registered
            // for every conduit block, so the type's own provider could not be reached otherwise. The overlay's title
            // already names the block, so type, tier and colour wait for the details.
            own.addInfo(request, sink);
            if (full) {
                sink.line(name);
            }
        } else {
            sink.line(name);
        }
        if (full) {
            describeFaces(conduit, sink);
        }
    }

    /** One line per face that draws something (a link, a block face or a stub), plus every disabled face. */
    private static void describeFaces(ConduitData conduit, BlockInfoSink sink) {
        int listed = conduit.renderMask() | ConduitFaceModes.sidesWith(conduit.faceModes(), ConduitFaceMode.DISABLED);
        for (Direction side : Direction.values()) {
            if (ConduitConnections.isConnected(listed, side)) {
                sink.line(Component.translatable(FACE_KEY, Component.translatable(sideKey(side)),
                        Component.translatable(faceModeKey(conduit.faceMode(side)))));
            }
        }
    }

    /** Lang key of a face mode's name, e.g. {@code info.maxtech.face_mode.insert}. */
    public static String faceModeKey(ConduitFaceMode mode) {
        return FACE_MODE_KEY_PREFIX + mode.name().toLowerCase(Locale.ROOT);
    }

    /** Lang key of a side's name, e.g. {@code info.maxtech.side.north}. */
    public static String sideKey(Direction side) {
        return SIDE_KEY_PREFIX + side.getName();
    }

    private static Component colourName(@Nullable DyeColor colour) {
        // Vanilla has a key per dye colour, e.g. "color.minecraft.red".
        return colour == null
                ? Component.translatable(UNCOLOURED_KEY)
                : Component.translatable("color.minecraft." + colour.getName());
    }
}
