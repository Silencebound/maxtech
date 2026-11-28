package dev.silencebound.maxtech.lib.shim.saving;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Streams;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagType;
import org.jspecify.annotations.Nullable;

/**
 * 26.1's {@code TagValueInput} over 1.21.1's {@link CompoundTag}: reads with registry ops from the given holders,
 * reports type mismatches and decode errors to the problem reporter, and treats an empty child like a missing one.
 */
public class TagValueInput implements ValueInput {
    private final ProblemReporter problemReporter;
    private final Context context;
    private final CompoundTag input;

    private TagValueInput(ProblemReporter problemReporter, Context context, CompoundTag input) {
        this.problemReporter = problemReporter;
        this.context = context;
        this.input = input;
    }

    public static ValueInput create(ProblemReporter problemReporter, HolderLookup.Provider holders, CompoundTag tag) {
        return new TagValueInput(problemReporter, new Context(holders), tag);
    }

    public static ValueInput.ValueInputList create(ProblemReporter problemReporter, HolderLookup.Provider holders,
            List<CompoundTag> tags) {
        return new CompoundListWrapper(problemReporter, new Context(holders), tags);
    }

    @Override
    public <T> Optional<T> read(String name, Codec<T> codec) {
        Tag tag = input.get(name);
        if (tag == null) {
            return Optional.empty();
        }
        DataResult<T> result = codec.parse(context.ops, tag);
        if (result instanceof DataResult.Error<T> error) {
            problemReporter.report(new DecodeFromFieldFailedProblem(name, tag, error));
            return error.partialValue();
        }
        return result.result();
    }

    @Override
    public <T> Optional<T> read(MapCodec<T> codec) {
        DynamicOps<Tag> ops = context.ops;
        DataResult<T> result = ops.getMap(input).flatMap(map -> codec.decode(ops, map));
        if (result instanceof DataResult.Error<T> error) {
            problemReporter.report(new DecodeFromMapFailedProblem(error));
            return error.partialValue();
        }
        return result.result();
    }

    @SuppressWarnings("unchecked")
    private <T extends Tag> @Nullable T typed(String name, TagType<T> expected) {
        Tag tag = input.get(name);
        if (tag == null) {
            return null;
        }
        if (tag.getType() != expected) {
            problemReporter.report(new UnexpectedTypeProblem(name, expected, tag.getType()));
            return null;
        }
        return (T) tag;
    }

    private @Nullable NumericTag numeric(String name) {
        Tag tag = input.get(name);
        if (tag == null) {
            return null;
        }
        if (tag instanceof NumericTag numericTag) {
            return numericTag;
        }
        problemReporter.report(new UnexpectedNonNumberProblem(name, tag.getType()));
        return null;
    }

    @Override
    public Optional<ValueInput> child(String name) {
        CompoundTag compound = typed(name, CompoundTag.TYPE);
        return compound != null ? Optional.of(wrapChild(fieldReporter(name), context, compound)) : Optional.empty();
    }

    @Override
    public ValueInput childOrEmpty(String name) {
        CompoundTag compound = typed(name, CompoundTag.TYPE);
        return compound != null ? wrapChild(fieldReporter(name), context, compound) : context.empty;
    }

    @Override
    public ValueInput rawChildOrEmpty(String key) {
        CompoundTag compound = typed(key, CompoundTag.TYPE);
        return compound != null ? new TagValueInput(fieldReporter(key), context, compound) : context.empty;
    }

    @Override
    public Optional<ValueInput.ValueInputList> childrenList(String name) {
        ListTag list = typed(name, ListTag.TYPE);
        return list != null ? Optional.of(wrapList(name, list)) : Optional.empty();
    }

    @Override
    public ValueInput.ValueInputList childrenListOrEmpty(String name) {
        ListTag list = typed(name, ListTag.TYPE);
        return list != null ? wrapList(name, list) : Context.EMPTY_LIST;
    }

    @Override
    public <T> Optional<ValueInput.TypedInputList<T>> list(String name, Codec<T> codec) {
        ListTag list = typed(name, ListTag.TYPE);
        return list != null ? Optional.of(wrapTypedList(name, list, codec)) : Optional.empty();
    }

    @Override
    public <T> ValueInput.TypedInputList<T> listOrEmpty(String name, Codec<T> codec) {
        ListTag list = typed(name, ListTag.TYPE);
        return list != null ? wrapTypedList(name, list, codec) : Context.emptyTypedList();
    }

    @Override
    public boolean getBooleanOr(String name, boolean defaultValue) {
        NumericTag tag = numeric(name);
        return tag != null ? tag.getAsByte() != 0 : defaultValue;
    }

    @Override
    public byte getByteOr(String name, byte defaultValue) {
        NumericTag tag = numeric(name);
        return tag != null ? tag.getAsByte() : defaultValue;
    }

    @Override
    public int getShortOr(String name, short defaultValue) {
        NumericTag tag = numeric(name);
        return tag != null ? tag.getAsShort() : defaultValue;
    }

    @Override
    public Optional<Integer> getInt(String name) {
        NumericTag tag = numeric(name);
        return tag != null ? Optional.of(tag.getAsInt()) : Optional.empty();
    }

    @Override
    public int getIntOr(String name, int defaultValue) {
        NumericTag tag = numeric(name);
        return tag != null ? tag.getAsInt() : defaultValue;
    }

    @Override
    public long getLongOr(String name, long defaultValue) {
        NumericTag tag = numeric(name);
        return tag != null ? tag.getAsLong() : defaultValue;
    }

    @Override
    public Optional<Long> getLong(String name) {
        NumericTag tag = numeric(name);
        return tag != null ? Optional.of(tag.getAsLong()) : Optional.empty();
    }

    @Override
    public float getFloatOr(String name, float defaultValue) {
        NumericTag tag = numeric(name);
        return tag != null ? tag.getAsFloat() : defaultValue;
    }

    @Override
    public double getDoubleOr(String name, double defaultValue) {
        NumericTag tag = numeric(name);
        return tag != null ? tag.getAsDouble() : defaultValue;
    }

    @Override
    public Optional<String> getString(String name) {
        StringTag tag = typed(name, StringTag.TYPE);
        return tag != null ? Optional.of(tag.getAsString()) : Optional.empty();
    }

    @Override
    public String getStringOr(String name, String defaultValue) {
        StringTag tag = typed(name, StringTag.TYPE);
        return tag != null ? tag.getAsString() : defaultValue;
    }

    @Override
    public Optional<int[]> getIntArray(String name) {
        IntArrayTag tag = typed(name, IntArrayTag.TYPE);
        return tag != null ? Optional.of(tag.getAsIntArray()) : Optional.empty();
    }

    @Override
    public HolderLookup.Provider lookup() {
        return context.lookup;
    }

    @Override
    public Set<String> keySet() {
        return Collections.unmodifiableSet(input.getAllKeys());
    }

    private ProblemReporter fieldReporter(String name) {
        return problemReporter.forChild(new ProblemReporter.FieldPathElement(name));
    }

    private static ValueInput wrapChild(ProblemReporter reporter, Context context, CompoundTag compound) {
        return compound.isEmpty() ? context.empty : new TagValueInput(reporter, context, compound);
    }

    private ValueInput.ValueInputList wrapList(String name, ListTag list) {
        return list.isEmpty() ? Context.EMPTY_LIST : new ListWrapper(problemReporter, name, context, list);
    }

    private <T> ValueInput.TypedInputList<T> wrapTypedList(String name, ListTag list, Codec<T> codec) {
        return list.isEmpty() ? Context.emptyTypedList()
                : new TypedListWrapper<>(problemReporter, name, context, codec, list);
    }

    /**
     * An element of a list that held more than one tag type; {@link TagValueOutput} wraps those as {@code {"": tag}},
     * like 1.21.1's NbtOps, since a 1.21.1 ListTag holds one type only.
     */
    static Tag unwrap(Tag tag) {
        if (tag instanceof CompoundTag compound && compound.size() == 1 && compound.contains("")) {
            return Objects.requireNonNull(compound.get(""));
        }
        return tag;
    }

    /** The holders, their registry ops and the shared empty views (26.1's {@code ValueInputContextHelper}). */
    private static final class Context {
        static final ValueInput.ValueInputList EMPTY_LIST = new ValueInput.ValueInputList() {
            @Override
            public boolean isEmpty() {
                return true;
            }

            @Override
            public Stream<ValueInput> stream() {
                return Stream.empty();
            }

            @Override
            public Iterator<ValueInput> iterator() {
                return Collections.emptyIterator();
            }
        };
        static final ValueInput.TypedInputList<Object> EMPTY_TYPED_LIST = new ValueInput.TypedInputList<>() {
            @Override
            public boolean isEmpty() {
                return true;
            }

            @Override
            public Stream<Object> stream() {
                return Stream.empty();
            }

            @Override
            public Iterator<Object> iterator() {
                return Collections.emptyIterator();
            }
        };

        final HolderLookup.Provider lookup;
        final DynamicOps<Tag> ops;
        final ValueInput empty = new EmptyInput(this);

        Context(HolderLookup.Provider lookup) {
            this.lookup = lookup;
            this.ops = lookup.createSerializationContext(NbtOps.INSTANCE);
        }

        @SuppressWarnings("unchecked")
        static <T> ValueInput.TypedInputList<T> emptyTypedList() {
            return (ValueInput.TypedInputList<T>) EMPTY_TYPED_LIST;
        }
    }

    /** Reads nothing: every value is missing. */
    private record EmptyInput(Context context) implements ValueInput {
        @Override
        public <T> Optional<T> read(String name, Codec<T> codec) {
            return Optional.empty();
        }

        @Override
        public <T> Optional<T> read(MapCodec<T> codec) {
            return Optional.empty();
        }

        @Override
        public Optional<ValueInput> child(String name) {
            return Optional.empty();
        }

        @Override
        public ValueInput childOrEmpty(String name) {
            return this;
        }

        @Override
        public Optional<ValueInput.ValueInputList> childrenList(String name) {
            return Optional.empty();
        }

        @Override
        public ValueInput.ValueInputList childrenListOrEmpty(String name) {
            return Context.EMPTY_LIST;
        }

        @Override
        public <T> Optional<ValueInput.TypedInputList<T>> list(String name, Codec<T> codec) {
            return Optional.empty();
        }

        @Override
        public <T> ValueInput.TypedInputList<T> listOrEmpty(String name, Codec<T> codec) {
            return Context.emptyTypedList();
        }

        @Override
        public boolean getBooleanOr(String name, boolean defaultValue) {
            return defaultValue;
        }

        @Override
        public byte getByteOr(String name, byte defaultValue) {
            return defaultValue;
        }

        @Override
        public int getShortOr(String name, short defaultValue) {
            return defaultValue;
        }

        @Override
        public Optional<Integer> getInt(String name) {
            return Optional.empty();
        }

        @Override
        public int getIntOr(String name, int defaultValue) {
            return defaultValue;
        }

        @Override
        public long getLongOr(String name, long defaultValue) {
            return defaultValue;
        }

        @Override
        public Optional<Long> getLong(String name) {
            return Optional.empty();
        }

        @Override
        public float getFloatOr(String name, float defaultValue) {
            return defaultValue;
        }

        @Override
        public double getDoubleOr(String name, double defaultValue) {
            return defaultValue;
        }

        @Override
        public Optional<String> getString(String name) {
            return Optional.empty();
        }

        @Override
        public String getStringOr(String name, String defaultValue) {
            return defaultValue;
        }

        @Override
        public Optional<int[]> getIntArray(String name) {
            return Optional.empty();
        }

        @Override
        public HolderLookup.Provider lookup() {
            return context.lookup;
        }

        @Override
        public Set<String> keySet() {
            return Set.of();
        }
    }

    /** A plain list of compounds (the {@code create(..., List)} factory). */
    private record CompoundListWrapper(ProblemReporter problemReporter, Context context, List<CompoundTag> list)
            implements ValueInput.ValueInputList {
        private ValueInput child(int index, CompoundTag tag) {
            return wrapChild(problemReporter.forChild(new ProblemReporter.IndexedPathElement(index)), context, tag);
        }

        @Override
        public boolean isEmpty() {
            return list.isEmpty();
        }

        @Override
        public Stream<ValueInput> stream() {
            return Streams.mapWithIndex(list.stream(), (tag, index) -> child((int) index, tag));
        }

        @Override
        public Iterator<ValueInput> iterator() {
            return stream().iterator();
        }
    }

    /** A ListTag of compounds; other elements are reported and skipped. */
    private record ListWrapper(ProblemReporter problemReporter, String name, Context context, ListTag list)
            implements ValueInput.ValueInputList {
        @Override
        public boolean isEmpty() {
            return list.isEmpty();
        }

        private @Nullable ValueInput child(int index, Tag element) {
            Tag tag = unwrap(element);
            if (tag instanceof CompoundTag compound) {
                return wrapChild(problemReporter.forChild(new ProblemReporter.IndexedFieldPathElement(name, index)),
                        context, compound);
            }
            problemReporter.report(new UnexpectedListElementTypeProblem(name, index, CompoundTag.TYPE, tag.getType()));
            return null;
        }

        @Override
        public Stream<ValueInput> stream() {
            return Streams.mapWithIndex(list.stream(), (tag, index) -> child((int) index, tag)).filter(Objects::nonNull);
        }

        @Override
        public Iterator<ValueInput> iterator() {
            Iterator<Tag> tags = list.iterator();
            return new AbstractIterator<>() {
                private int index;

                @Override
                protected @Nullable ValueInput computeNext() {
                    while (tags.hasNext()) {
                        ValueInput child = child(index++, tags.next());
                        if (child != null) {
                            return child;
                        }
                    }
                    return endOfData();
                }
            };
        }
    }

    /** A ListTag read element by element with a codec; elements that fail are reported and skipped. */
    private record TypedListWrapper<T>(ProblemReporter problemReporter, String name, Context context, Codec<T> codec,
            ListTag list) implements ValueInput.TypedInputList<T> {
        @Override
        public boolean isEmpty() {
            return list.isEmpty();
        }

        private @Nullable T element(int index, Tag element) {
            Tag tag = unwrap(element);
            DataResult<T> result = codec.parse(context.ops, tag);
            if (result instanceof DataResult.Error<T> error) {
                problemReporter.report(new DecodeFromListFailedProblem(name, index, tag, error));
                return error.partialValue().orElse(null);
            }
            return result.result().orElse(null);
        }

        @Override
        public Stream<T> stream() {
            return Streams.mapWithIndex(list.stream(), (tag, index) -> element((int) index, tag))
                    .filter(Objects::nonNull);
        }

        @Override
        public Iterator<T> iterator() {
            Iterator<Tag> tags = list.iterator();
            return new AbstractIterator<>() {
                private int index;

                @Override
                protected @Nullable T computeNext() {
                    while (tags.hasNext()) {
                        T value = element(index++, tags.next());
                        if (value != null) {
                            return value;
                        }
                    }
                    return endOfData();
                }
            };
        }
    }

    public record DecodeFromFieldFailedProblem(String name, Tag tag, DataResult.Error<?> error)
            implements ProblemReporter.Problem {
        @Override
        public String description() {
            return "Failed to decode value '" + tag + "' from field '" + name + "': " + error.message();
        }
    }

    public record DecodeFromListFailedProblem(String name, int index, Tag tag, DataResult.Error<?> error)
            implements ProblemReporter.Problem {
        @Override
        public String description() {
            return "Failed to decode value '" + tag + "' from field '" + name + "' at index " + index + "': "
                    + error.message();
        }
    }

    public record DecodeFromMapFailedProblem(DataResult.Error<?> error) implements ProblemReporter.Problem {
        @Override
        public String description() {
            return "Failed to decode from map: " + error.message();
        }
    }

    public record UnexpectedListElementTypeProblem(String name, int index, TagType<?> expected, TagType<?> actual)
            implements ProblemReporter.Problem {
        @Override
        public String description() {
            return "Expected list '" + name + "' to contain at index " + index + " value of type " + expected.getName()
                    + ", but got " + actual.getName();
        }
    }

    public record UnexpectedNonNumberProblem(String name, TagType<?> actual) implements ProblemReporter.Problem {
        @Override
        public String description() {
            return "Expected field '" + name + "' to contain number, but got " + actual.getName();
        }
    }

    public record UnexpectedTypeProblem(String name, TagType<?> expected, TagType<?> actual)
            implements ProblemReporter.Problem {
        @Override
        public String description() {
            return "Expected field '" + name + "' to contain value of type " + expected.getName() + ", but got "
                    + actual.getName();
        }
    }
}
