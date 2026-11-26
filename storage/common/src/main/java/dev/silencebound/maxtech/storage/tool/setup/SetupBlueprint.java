package dev.silencebound.maxtech.storage.tool.setup;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.tool.blueprint.Blueprint;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Vec3i;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A group of devices copied onto a setup blueprint (M7C item 21): the size of the copied box and one entry per device
 * block in it, each with its offset from the box's lowest corner, its block state, its settings (a {@link Blueprint},
 * the same as the single-device blueprint card holds), the parts on it when it is a storage cable and its colour when
 * it is a dyed conduit. Never holds contents. The saved form carries a {@code version}; a newer version than this code
 * knows fails to load instead of being misread, so MaxTech Rooms templates can reuse the format and extend it.
 * Immutable; any thread.
 *
 * @param size
 *            the box's size, each axis 1 to {@link #MAX_SIZE}
 * @param entries
 *            at most {@link #MAX_ENTRIES}, each offset inside the box
 */
public record SetupBlueprint(Vec3i size, List<Entry> entries) {
    /** The format this code writes and the newest it reads. */
    public static final int VERSION = 1;
    /** The longest side of a copied box. */
    public static final int MAX_SIZE = 32;
    /** The most device blocks one blueprint holds. */
    public static final int MAX_ENTRIES = 256;
    /**
     * The most entries, parts and setting components together; bounds the saved form, which has no registry to measure
     * bytes with.
     */
    public static final int MAX_WEIGHT = 2048;
    /** The most bytes of the network form, which is what the item syncs and saves; checked on copy and on decode. */
    public static final int MAX_BYTES = 128 * 1024;

    /**
     * A part on a cable entry's face.
     *
     * @param face
     *            the face the part sits on, as copied (before any paste rotation)
     * @param type
     *            the part type's id ({@code PartTypes.byId})
     * @param settings
     *            the part's settings; empty when it has none or the copier could not configure it
     */
    public record Part(Direction face, Identifier type, Optional<Blueprint> settings) {
        public static final Codec<Part> CODEC = RecordCodecBuilder
                .create(i -> i
                        .group(Direction.CODEC.fieldOf("face").forGetter(Part::face),
                                Identifier.CODEC.fieldOf("type").forGetter(Part::type),
                                Blueprint.CODEC.optionalFieldOf("settings").forGetter(Part::settings))
                        .apply(i, Part::new));
        public static final StreamCodec<RegistryFriendlyByteBuf, Part> STREAM_CODEC = StreamCodec.composite(
                Direction.STREAM_CODEC, Part::face, Identifier.STREAM_CODEC, Part::type,
                ByteBufCodecs.optional(Blueprint.STREAM_CODEC), Part::settings, Part::new);
    }

    /**
     * One device block.
     *
     * @param offset
     *            from the box's lowest corner
     * @param state
     *            the block state as copied (before any paste rotation)
     * @param settings
     *            the device's settings; empty when it has none or the copier could not configure it
     * @param parts
     *            the parts on it, one per face at most
     * @param colour
     *            the conduit's dye colour; empty for an uncoloured conduit, any other block, and blueprints copied
     *            before colours were kept (the saved field is optional, so those load unchanged)
     */
    public record Entry(BlockPos offset, BlockState state, Optional<Blueprint> settings, List<Part> parts,
            Optional<DyeColor> colour) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i
                .group(BlockPos.CODEC.fieldOf("offset").forGetter(Entry::offset),
                        BlockState.CODEC.fieldOf("state").forGetter(Entry::state),
                        Blueprint.CODEC.optionalFieldOf("settings").forGetter(Entry::settings),
                        Part.CODEC.listOf(0, Direction.values().length).optionalFieldOf("parts", List.of()).forGetter(
                                Entry::parts),
                        DyeColor.CODEC.optionalFieldOf("colour").forGetter(Entry::colour))
                .apply(i, Entry::new));
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Entry::offset, ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY), Entry::state,
                ByteBufCodecs.optional(Blueprint.STREAM_CODEC), Entry::settings,
                Part.STREAM_CODEC.apply(ByteBufCodecs.list(Direction.values().length)), Entry::parts,
                ByteBufCodecs.optional(DyeColor.STREAM_CODEC), Entry::colour, Entry::new);

        public Entry {
            parts = List.copyOf(parts);
        }

        /** An entry with no colour. */
        public Entry(BlockPos offset, BlockState state, Optional<Blueprint> settings, List<Part> parts) {
            this(offset, state, settings, parts, Optional.empty());
        }

        /** The part copied on {@code face}, or empty. */
        public Optional<Part> part(Direction face) {
            for (Part part : parts) {
                if (part.face() == face) {
                    return Optional.of(part);
                }
            }
            return Optional.empty();
        }
    }

    public static final Codec<SetupBlueprint> CODEC = RecordCodecBuilder
            .<SetupBlueprint>create(i -> i
                    .group(Codec.intRange(1, VERSION).fieldOf("version").forGetter(blueprint -> VERSION),
                            Vec3i.CODEC.fieldOf("size").forGetter(SetupBlueprint::size),
                            Entry.CODEC.listOf(0, MAX_ENTRIES).fieldOf("entries").forGetter(SetupBlueprint::entries))
                    .apply(i, (version, size, entries) -> new SetupBlueprint(size, entries)))
            .validate(SetupBlueprint::check);
    private static final StreamCodec<RegistryFriendlyByteBuf, SetupBlueprint> INNER = StreamCodec.composite(
            Vec3i.STREAM_CODEC, SetupBlueprint::size, Entry.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ENTRIES)),
            SetupBlueprint::entries, SetupBlueprint::new);
    public static final StreamCodec<RegistryFriendlyByteBuf, SetupBlueprint> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SetupBlueprint decode(RegistryFriendlyByteBuf buf) {
            int start = buf.readerIndex();
            SetupBlueprint blueprint = INNER.decode(buf);
            if (buf.readerIndex() - start > MAX_BYTES) {
                throw new IllegalArgumentException("Setup blueprint over " + MAX_BYTES + " bytes");
            }
            return checked(blueprint);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, SetupBlueprint blueprint) {
            INNER.encode(buf, blueprint);
        }
    };

    public SetupBlueprint {
        entries = List.copyOf(entries);
    }

    /** Whether the size and every offset are in range. */
    public boolean valid() {
        if (size.getX() < 1 || size.getY() < 1 || size.getZ() < 1 || size.getX() > MAX_SIZE || size.getY() > MAX_SIZE
                || size.getZ() > MAX_SIZE || entries.size() > MAX_ENTRIES || weight() > MAX_WEIGHT) {
            return false;
        }
        for (Entry entry : entries) {
            BlockPos offset = entry.offset();
            if (offset.getX() < 0 || offset.getY() < 0 || offset.getZ() < 0 || offset.getX() >= size.getX()
                    || offset.getY() >= size.getY() || offset.getZ() >= size.getZ()) {
                return false;
            }
        }
        return true;
    }

    /** Entries, parts and setting components together; see {@link #MAX_WEIGHT}. */
    public int weight() {
        int weight = 0;
        for (Entry entry : entries) {
            weight += 1 + entry.settings().map(settings -> settings.settings().size()).orElse(0);
            for (Part part : entry.parts()) {
                weight += 1 + part.settings().map(settings -> settings.settings().size()).orElse(0);
            }
        }
        return weight;
    }

    /** The bytes of the network form, which is what the item syncs and saves. Server thread. */
    public int encodedSize(RegistryAccess access) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), access);
        try {
            INNER.encode(buf, this);
            return buf.readableBytes();
        } finally {
            buf.release();
        }
    }

    private static DataResult<SetupBlueprint> check(SetupBlueprint blueprint) {
        return blueprint.valid()
                ? DataResult.success(blueprint)
                : DataResult.error(() -> "Setup blueprint out of range: size " + blueprint.size());
    }

    private static SetupBlueprint checked(SetupBlueprint blueprint) {
        if (!blueprint.valid()) {
            throw new IllegalArgumentException("Setup blueprint out of range: size " + blueprint.size());
        }
        return blueprint;
    }

    /** The entry at {@code offset}, or empty. */
    public Optional<Entry> at(BlockPos offset) {
        for (Entry entry : entries) {
            if (entry.offset().equals(offset)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /**
     * Where an entry at {@code offset} lands when the blueprint is pasted with its lowest corner (after turning by
     * {@code rotation} around the vertical axis) at {@code origin}.
     */
    public BlockPos place(BlockPos origin, Rotation rotation, BlockPos offset) {
        return origin.offset(offset.rotate(rotation)).offset(shift(rotation));
    }

    /** The inverse of {@link #place}: the offset that lands on {@code pos}, which may lie outside the box. */
    public BlockPos offsetOf(BlockPos origin, Rotation rotation, BlockPos pos) {
        return pos.subtract(origin).subtract(shift(rotation)).rotate(inverse(rotation));
    }

    /** The size after turning by {@code rotation}. */
    public Vec3i rotatedSize(Rotation rotation) {
        return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90
                ? new Vec3i(size.getZ(), size.getY(), size.getX())
                : size;
    }

    /** What moves the turned box back so its lowest corner is at the origin. */
    private BlockPos shift(Rotation rotation) {
        BlockPos far = new BlockPos(size.getX() - 1, 0, size.getZ() - 1).rotate(rotation);
        return new BlockPos(Math.max(0, -far.getX()), 0, Math.max(0, -far.getZ()));
    }

    /** The rotation that undoes {@code rotation}. */
    public static Rotation inverse(Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
            case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            default -> rotation;
        };
    }
}
