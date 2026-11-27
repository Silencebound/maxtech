package dev.silencebound.maxtech.lib.menu;

import dev.silencebound.maxtech.lib.api.menu.EnergyValue;
import dev.silencebound.maxtech.lib.api.menu.EnumValue;
import dev.silencebound.maxtech.lib.api.menu.IntValue;
import dev.silencebound.maxtech.lib.api.menu.LongValue;
import dev.silencebound.maxtech.lib.api.menu.MenuValue;
import dev.silencebound.maxtech.lib.api.menu.MenuValueView;
import dev.silencebound.maxtech.lib.api.menu.MenuValues;
import dev.silencebound.maxtech.lib.api.menu.MenuValues.Kind;
import dev.silencebound.maxtech.lib.api.menu.ProgressValue;
import dev.silencebound.maxtech.lib.api.menu.TankValue;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * One menu's values in primitive arrays, the pure part of {@link ToolkitMenu}'s sync. On the server
 * {@link #collect(Object)} reads every getter and marks the values that differ from the last read; {@link #encode}
 * writes the marked ones. On the client {@link #apply} reads them back into the same arrays.
 * <p>
 * Encoding: the changed mask as {@code ceil(size / 64)} var-longs (bit {@code i % 64} of word {@code i / 64} for value
 * {@code i}), then each marked value in order: ints and enum ordinals as var-ints; longs, energy and progress as
 * var-longs; a tank as its content through the codec its declaration gives, then amount and capacity as var-longs. That
 * codec is the only part that knows the content is a fluid, so another resource kind can take its place without
 * changing the framing.
 * <p>
 * Neither collecting nor reading allocates. One thread (the main thread of the menu's side).
 */
public final class MenuValueTable<H> implements MenuValueView {
    private final MenuValues<H> values;
    private final ToIntFunction<? super H>[] intReaders;
    private final ToLongFunction<? super H>[] longReaders;
    private final Function<? super H, ?>[] objectReaders;
    /** Value index of each field, to mark what changed. */
    private final int[] intOwner;
    private final int[] longOwner;
    private final int[] objectOwner;
    private final int[] ints;
    private final long[] longs;
    private final Object[] objects;
    private final long[] mask;
    private boolean dirty;

    @SuppressWarnings("unchecked") // generic arrays of the declaration's own readers
    public MenuValueTable(MenuValues<H> values) {
        this.values = values;
        int intCount = values.intFields();
        int longCount = values.longFields();
        int objectCount = values.objectFields();
        intReaders = new ToIntFunction[intCount];
        longReaders = new ToLongFunction[longCount];
        objectReaders = new Function[objectCount];
        intOwner = new int[intCount];
        longOwner = new int[longCount];
        objectOwner = new int[objectCount];
        ints = new int[intCount];
        longs = new long[longCount];
        objects = new Object[objectCount];
        for (int i = 0; i < intCount; i++) {
            intReaders[i] = values.intReader(i);
        }
        for (int i = 0; i < longCount; i++) {
            longReaders[i] = values.longReader(i);
        }
        for (int i = 0; i < objectCount; i++) {
            objectReaders[i] = values.objectReader(i);
            objects[i] = values.objectInitial(i);
        }
        for (int v = 0; v < values.size(); v++) {
            int intEnd = v + 1 < values.size() ? values.firstInt(v + 1) : intCount;
            for (int f = values.firstInt(v); f < intEnd; f++) {
                intOwner[f] = v;
            }
            int longEnd = v + 1 < values.size() ? values.firstLong(v + 1) : longCount;
            for (int f = values.firstLong(v); f < longEnd; f++) {
                longOwner[f] = v;
            }
            int objectEnd = v + 1 < values.size() ? values.firstObject(v + 1) : objectCount;
            for (int f = values.firstObject(v); f < objectEnd; f++) {
                objectOwner[f] = v;
            }
        }
        mask = new long[(values.size() + 63) / 64];
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
            if (!current.equals(objects[i])) {
                objects[i] = current;
                mark(objectOwner[i]);
            }
        }
        return dirty;
    }

    /** Marks every value, so the next {@link #encode} sends all of them (a menu that just opened or resyncs). */
    public void markAll() {
        for (int v = 0; v < values.size(); v++) {
            mark(v);
        }
    }

    /** True while some value is marked and not yet encoded. */
    public boolean dirty() {
        return dirty;
    }

    /** Writes the marked values to {@code buf} and clears the marks. */
    public void encode(RegistryFriendlyByteBuf buf) {
        for (long word : mask) {
            buf.writeVarLong(word);
        }
        for (int v = 0; v < values.size(); v++) {
            if ((mask[v >>> 6] & (1L << v)) == 0) {
                continue;
            }
            switch (values.kind(v)) {
                case INT, ENUM -> buf.writeVarInt(ints[values.firstInt(v)]);
                case LONG -> buf.writeVarLong(longs[values.firstLong(v)]);
                case ENERGY, PROGRESS -> {
                    buf.writeVarLong(longs[values.firstLong(v)]);
                    buf.writeVarLong(longs[values.firstLong(v) + 1]);
                }
                case TANK -> {
                    int field = values.firstObject(v);
                    values.objectCodec(field).encode(buf, objects[field]);
                    buf.writeVarLong(longs[values.firstLong(v)]);
                    buf.writeVarLong(longs[values.firstLong(v) + 1]);
                }
            }
        }
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
        for (int v = 0; v < values.size(); v++) {
            if ((mask[v >>> 6] & (1L << v)) == 0) {
                continue;
            }
            switch (values.kind(v)) {
                case INT -> ints[values.firstInt(v)] = buf.readVarInt();
                case ENUM -> ints[values.firstInt(v)] = Math.clamp(buf.readVarInt(), 0, values.enumCount(v) - 1);
                case LONG -> longs[values.firstLong(v)] = buf.readVarLong();
                case ENERGY, PROGRESS -> {
                    longs[values.firstLong(v)] = buf.readVarLong();
                    longs[values.firstLong(v) + 1] = buf.readVarLong();
                }
                case TANK -> {
                    int field = values.firstObject(v);
                    objects[field] = values.objectCodec(field).decode(buf);
                    longs[values.firstLong(v)] = buf.readVarLong();
                    longs[values.firstLong(v) + 1] = buf.readVarLong();
                }
            }
        }
        Arrays.fill(mask, 0);
    }

    private void mark(int value) {
        mask[value >>> 6] |= 1L << value;
        dirty = true;
    }

    private int index(MenuValue value, Kind kind) {
        int index = value.index();
        if (index >= values.size() || values.kind(index) != kind) {
            throw new IllegalArgumentException("Menu value " + index + " is not a " + kind + " of this menu");
        }
        return index;
    }

    @Override
    public int get(IntValue value) {
        return ints[values.firstInt(index(value, Kind.INT))];
    }

    @Override
    public long get(LongValue value) {
        return longs[values.firstLong(index(value, Kind.LONG))];
    }

    @Override
    @SuppressWarnings("unchecked") // the declaration made this handle with constants of E
    public <E extends Enum<E>> E get(EnumValue<E> value) {
        int index = index(value, Kind.ENUM);
        return (E) values.enumConstant(index, ints[values.firstInt(index)]);
    }

    @Override
    public long stored(EnergyValue value) {
        return longs[values.firstLong(index(value, Kind.ENERGY))];
    }

    @Override
    public long capacity(EnergyValue value) {
        return longs[values.firstLong(index(value, Kind.ENERGY)) + 1];
    }

    @Override
    public long current(ProgressValue value) {
        return longs[values.firstLong(index(value, Kind.PROGRESS))];
    }

    @Override
    public long total(ProgressValue value) {
        return longs[values.firstLong(index(value, Kind.PROGRESS)) + 1];
    }

    @Override
    @SuppressWarnings("unchecked") // tanks declared through the api hold Optional<FluidKey>
    public Optional<FluidKey> fluid(TankValue value) {
        return (Optional<FluidKey>) objects[values.firstObject(index(value, Kind.TANK))];
    }

    /** The content of a tank declared with its own codec (tests); what {@link #fluid} returns for api tanks. */
    public Object content(TankValue value) {
        return objects[values.firstObject(index(value, Kind.TANK))];
    }

    @Override
    public long amount(TankValue value) {
        return longs[values.firstLong(index(value, Kind.TANK))];
    }

    @Override
    public long capacity(TankValue value) {
        return longs[values.firstLong(index(value, Kind.TANK)) + 1];
    }
}
