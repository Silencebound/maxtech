package dev.silencebound.maxtech.lib.menu;

import dev.silencebound.maxtech.lib.api.menu.EnergyValue;
import dev.silencebound.maxtech.lib.api.menu.EnumValue;
import dev.silencebound.maxtech.lib.api.menu.IntValue;
import dev.silencebound.maxtech.lib.api.menu.LongValue;
import dev.silencebound.maxtech.lib.api.menu.MenuValue;
import dev.silencebound.maxtech.lib.api.menu.MenuValueView;
import dev.silencebound.maxtech.lib.api.menu.MenuValues;
import dev.silencebound.maxtech.lib.api.menu.ProgressValue;
import dev.silencebound.maxtech.lib.api.menu.TankValue;
import dev.silencebound.maxtech.lib.menu.MenuLayout.Kind;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.KindKey;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.jspecify.annotations.Nullable;

/**
 * One menu's values in primitive arrays, the pure part of {@link ToolkitMenu}'s sync. On the server
 * {@link #collect(Object)} reads every getter and marks the values that differ from the last read; {@link #encode}
 * writes the marked ones and {@link #clearMarks} clears them once sent. On the client {@link #apply} reads them back
 * into the same arrays.
 * <p>
 * Encoding: the changed mask as {@code ceil(size / 64)} var-longs (bit {@code i % 64} of word {@code i / 64} for value
 * {@code i}), then each marked value in order: ints and enum ordinals as var-ints; longs, energy and progress as
 * var-longs; a tank as its content through the codec its layout gives, then amount and capacity as var-longs. That
 * codec is the only part that knows the content's kind: {@code FluidKey.OPTIONAL_STREAM_CODEC} for fluid tanks, an
 * optional {@code KindKey.CLIENTBOUND_STREAM_CODEC} for tanks of other kinds, with the same framing around both.
 * <p>
 * A tank's change key (what its getter returns, a kept {@code Optional} or Lib's tank state) is compared every tick;
 * its content and the resource and fluid views are built only when it changed (server) or arrived (client).
 * <p>
 * Neither collecting nor reading allocates. One thread (the main thread of the menu's side).
 */
public final class MenuValueTable<H> implements MenuValueView {
    private final MenuLayout<H> layout;
    private final ToIntFunction<? super H>[] intReaders;
    private final ToLongFunction<? super H>[] longReaders;
    private final Function<? super H, ?>[] objectReaders;
    private final @Nullable Function<? super H, ?>[] objectContents;
    /** Value index of each field, to mark what changed. */
    private final int[] intOwner;
    private final int[] longOwner;
    private final int[] objectOwner;
    private final int[] ints;
    private final long[] longs;
    /** Server: the change key read last, per object field. */
    private final Object[] changeKeys;
    /** The content as sent: the server's last content, the client's last received. */
    private final Object[] objects;
    private final Optional<KindKey<?>>[] resources;
    private final @Nullable Optional<FluidKey>[] fluids;
    private final long[] mask;
    private boolean dirty;

    public MenuValueTable(MenuValues<H> values) {
        this(MenuLayout.of(values));
    }

    MenuValueTable(MenuLayout<H> layout) {
        this.layout = layout;
        intReaders = layout.intReaders;
        longReaders = layout.longReaders;
        objectReaders = layout.objectReaders;
        objectContents = layout.objectContents;
        int intCount = intReaders.length;
        int longCount = longReaders.length;
        int objectCount = objectReaders.length;
        intOwner = new int[intCount];
        longOwner = new int[longCount];
        objectOwner = new int[objectCount];
        ints = new int[intCount];
        longs = new long[longCount];
        objects = layout.objectInitials.clone();
        changeKeys = layout.objectInitials.clone();
        resources = newResources(objectCount);
        fluids = newFluids(objectCount);
        for (int f = 0; f < objectCount; f++) {
            derive(f);
        }
        int size = layout.size();
        for (int v = 0; v < size; v++) {
            int intEnd = v + 1 < size ? layout.firstInt[v + 1] : intCount;
            for (int f = layout.firstInt[v]; f < intEnd; f++) {
                intOwner[f] = v;
            }
            int longEnd = v + 1 < size ? layout.firstLong[v + 1] : longCount;
            for (int f = layout.firstLong[v]; f < longEnd; f++) {
                longOwner[f] = v;
            }
            int objectEnd = v + 1 < size ? layout.firstObject[v + 1] : objectCount;
            for (int f = layout.firstObject[v]; f < objectEnd; f++) {
                objectOwner[f] = v;
            }
        }
        mask = new long[(size + 63) / 64];
    }

    /** Server: reads every getter from {@code host} and marks the values that changed. True if any is marked. */
    public boolean collect(H host) {
        for (int i = 0; i < ints.length; i++) {
            int current = intReaders[i].applyAsInt(host);
            if (current != ints[i]) {
                ints[i] = current;
                mark(intOwner[i]);
            }
        }
        for (int i = 0; i < longs.length; i++) {
            long current = longReaders[i].applyAsLong(host);
            if (current != longs[i]) {
                longs[i] = current;
                mark(longOwner[i]);
            }
        }
        for (int i = 0; i < objects.length; i++) {
            Object current = Objects.requireNonNull(objectReaders[i].apply(host), "menu value getter returned null");
            if (!current.equals(changeKeys[i])) {
                changeKeys[i] = current;
                Function<? super H, ?> content = objectContents[i];
                objects[i] = content == null
                        ? current
                        : Objects.requireNonNull(content.apply(host), "menu value getter returned null");
                derive(i);
                mark(objectOwner[i]);
            }
        }
        return dirty;
    }

    /** Builds the resource and fluid views of object field {@code field} from its content. */
    private void derive(int field) {
        Object content = objects[field];
        resources[field] = layout.toResource[field].apply(content);
        Function<Object, Optional<FluidKey>> toFluid = layout.toFluid[field];
        fluids[field] = toFluid == null ? null : toFluid.apply(content);
    }

    @SuppressWarnings("unchecked") // an array of one generic type, filled by derive
    private static Optional<KindKey<?>>[] newResources(int size) {
        return new Optional[size];
    }

    @SuppressWarnings("unchecked") // an array of one generic type, filled by derive
    private static @Nullable Optional<FluidKey>[] newFluids(int size) {
        return new Optional[size];
    }

    /** Marks every value, so the next {@link #encode} sends all of them (a menu that just opened or resyncs). */
    public void markAll() {
        for (int v = 0; v < layout.size(); v++) {
            mark(v);
        }
    }

    /** True while some value is marked and not yet encoded. */
    public boolean dirty() {
        return dirty;
    }

    /** Writes the marked values to {@code buf}; the marks stay until {@link #clearMarks()}. */
    public void encode(RegistryFriendlyByteBuf buf) {
        for (long word : mask) {
            buf.writeVarLong(word);
        }
        for (int v = 0; v < layout.size(); v++) {
            if ((mask[v >>> 6] & (1L << (v & 63))) == 0) {
                continue;
            }
            switch (layout.kinds[v]) {
                case INT, ENUM -> buf.writeVarInt(ints[layout.firstInt[v]]);
                case LONG -> buf.writeVarLong(longs[layout.firstLong[v]]);
                case ENERGY, PROGRESS -> {
                    buf.writeVarLong(longs[layout.firstLong[v]]);
                    buf.writeVarLong(longs[layout.firstLong[v] + 1]);
                }
                case TANK -> {
                    int field = layout.firstObject[v];
                    layout.objectCodecs[field].encode(buf, objects[field]);
                    buf.writeVarLong(longs[layout.firstLong[v]]);
                    buf.writeVarLong(longs[layout.firstLong[v] + 1]);
                }
            }
        }
    }

    /** Clears the marks once what {@link #encode} wrote has been sent. */
    public void clearMarks() {
        Arrays.fill(mask, 0);
        dirty = false;
    }

    /**
     * Client: reads values written by {@link #encode} into this table. An enum ordinal outside the enum (a server of
     * another build) is clamped to its range.
     */
    public void apply(RegistryFriendlyByteBuf buf) {
        for (int w = 0; w < mask.length; w++) {
            mask[w] = buf.readVarLong();
        }
        for (int v = 0; v < layout.size(); v++) {
            if ((mask[v >>> 6] & (1L << (v & 63))) == 0) {
                continue;
            }
            switch (layout.kinds[v]) {
                case INT -> ints[layout.firstInt[v]] = buf.readVarInt();
                case ENUM ->
                    ints[layout.firstInt[v]] = Math.clamp(buf.readVarInt(), 0, layout.enumConstants[v].length - 1);
                case LONG -> longs[layout.firstLong[v]] = buf.readVarLong();
                case ENERGY, PROGRESS -> {
                    longs[layout.firstLong[v]] = buf.readVarLong();
                    longs[layout.firstLong[v] + 1] = buf.readVarLong();
                }
                case TANK -> {
                    int field = layout.firstObject[v];
                    objects[field] = layout.objectCodecs[field].decode(buf);
                    derive(field);
                    longs[layout.firstLong[v]] = buf.readVarLong();
                    longs[layout.firstLong[v] + 1] = buf.readVarLong();
                }
            }
        }
        Arrays.fill(mask, 0);
    }

    private void mark(int value) {
        mask[value >>> 6] |= 1L << (value & 63);
        dirty = true;
    }

    private int index(MenuValue value, Kind kind) {
        int index = value.index();
        if (index >= layout.size() || layout.kinds[index] != kind) {
            throw new IllegalArgumentException("Menu value " + index + " is not a " + kind + " of this menu");
        }
        return index;
    }

    @Override
    public int get(IntValue value) {
        return ints[layout.firstInt[index(value, Kind.INT)]];
    }

    @Override
    public long get(LongValue value) {
        return longs[layout.firstLong[index(value, Kind.LONG)]];
    }

    @Override
    @SuppressWarnings("unchecked") // the declaration made this handle with constants of E
    public <E extends Enum<E>> E get(EnumValue<E> value) {
        int index = index(value, Kind.ENUM);
        return (E) layout.enumConstants[index][ints[layout.firstInt[index]]];
    }

    @Override
    public long stored(EnergyValue value) {
        return longs[layout.firstLong[index(value, Kind.ENERGY)]];
    }

    @Override
    public long capacity(EnergyValue value) {
        return longs[layout.firstLong[index(value, Kind.ENERGY)] + 1];
    }

    @Override
    public long current(ProgressValue value) {
        return longs[layout.firstLong[index(value, Kind.PROGRESS)]];
    }

    @Override
    public long total(ProgressValue value) {
        return longs[layout.firstLong[index(value, Kind.PROGRESS)] + 1];
    }

    @Override
    public Optional<FluidKey> fluid(TankValue value) {
        int index = index(value, Kind.TANK);
        Optional<FluidKey> fluid = fluids[layout.firstObject[index]];
        if (fluid == null) {
            throw new IllegalArgumentException("Menu value " + index + " is not a fluid tank of this menu");
        }
        return fluid;
    }

    @Override
    public Optional<KindKey<?>> resource(TankValue value) {
        return resources[layout.firstObject[index(value, Kind.TANK)]];
    }

    /**
     * The content of any tank, also one laid out with its own codec (tests); what {@link #fluid} returns for fluids.
     */
    public Object content(TankValue value) {
        return objects[layout.firstObject[index(value, Kind.TANK)]];
    }

    @Override
    public long amount(TankValue value) {
        return longs[layout.firstLong[index(value, Kind.TANK)]];
    }

    @Override
    public long capacity(TankValue value) {
        return longs[layout.firstLong[index(value, Kind.TANK)] + 1];
    }
}
