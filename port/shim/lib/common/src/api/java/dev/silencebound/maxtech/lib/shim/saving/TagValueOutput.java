package dev.silencebound.maxtech.lib.shim.saving;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.jspecify.annotations.Nullable;

/**
 * 26.1's {@code TagValueOutput} over 1.21.1's {@link CompoundTag}: writes with the given ops (registry ops with
 * context) and reports encode errors to the problem reporter, keeping a partial result where the codec gives one.
 */
public class TagValueOutput implements ValueOutput {
    private final ProblemReporter problemReporter;
    private final DynamicOps<Tag> ops;
    private final CompoundTag output;

    private TagValueOutput(ProblemReporter problemReporter, DynamicOps<Tag> ops, CompoundTag output) {
        this.problemReporter = problemReporter;
        this.ops = ops;
        this.output = output;
    }

    public static TagValueOutput createWithContext(ProblemReporter problemReporter, HolderLookup.Provider provider) {
        return wrap(problemReporter, provider, new CompoundTag());
    }

    public static TagValueOutput createWithoutContext(ProblemReporter problemReporter) {
        return new TagValueOutput(problemReporter, NbtOps.INSTANCE, new CompoundTag());
    }

    /**
     * Port only (1.21.1): an output that writes into {@code tag}, for 1.21.1's {@code saveAdditional(CompoundTag,
     * Provider)}, which hands the block entity a tag to fill instead of an output.
     */
    public static TagValueOutput wrap(ProblemReporter problemReporter, HolderLookup.Provider provider,
            CompoundTag tag) {
        return new TagValueOutput(problemReporter, provider.createSerializationContext(NbtOps.INSTANCE), tag);
    }

    @Override
    public <T> void store(String name, Codec<T> codec, T value) {
        DataResult<Tag> result = codec.encodeStart(ops, value);
        if (result instanceof DataResult.Error<Tag> error) {
            problemReporter.report(new EncodeToFieldFailedProblem(name, value, error));
        }
        result.resultOrPartial().ifPresent(tag -> output.put(name, tag));
    }

    @Override
    public <T> void storeNullable(String name, Codec<T> codec, @Nullable T value) {
        if (value != null) {
            store(name, codec, value);
        }
    }

    @Override
    public <T> void store(MapCodec<T> codec, T value) {
        DataResult<Tag> result = codec.encoder().encodeStart(ops, value);
        if (result instanceof DataResult.Error<Tag> error) {
            problemReporter.report(new EncodeToMapFailedProblem(value, error));
        }
        result.resultOrPartial().ifPresent(tag -> output.merge((CompoundTag) tag));
    }

    @Override
    public void store(CompoundTag tag) {
        for (String key : tag.getAllKeys()) {
            output.put(key, tag.get(key));
        }
    }

    @Override
    public void putBoolean(String name, boolean value) {
        output.putBoolean(name, value);
    }

    @Override
    public void putByte(String name, byte value) {
        output.putByte(name, value);
    }

    @Override
    public void putShort(String name, short value) {
        output.putShort(name, value);
    }

    @Override
    public void putInt(String name, int value) {
        output.putInt(name, value);
    }

    @Override
    public void putLong(String name, long value) {
        output.putLong(name, value);
    }

    @Override
    public void putFloat(String name, float value) {
        output.putFloat(name, value);
    }

    @Override
    public void putDouble(String name, double value) {
        output.putDouble(name, value);
    }

    @Override
    public void putString(String name, String value) {
        output.putString(name, value);
    }

    @Override
    public void putIntArray(String name, int[] value) {
        output.putIntArray(name, value);
    }

    @Override
    public ValueOutput child(String name) {
        CompoundTag child = new CompoundTag();
        output.put(name, child);
        return new TagValueOutput(problemReporter.forChild(new ProblemReporter.FieldPathElement(name)), ops, child);
    }

    @Override
    public ValueOutput.ValueOutputList childrenList(String name) {
        ListTag list = new ListTag();
        output.put(name, list);
        return new ListWrapper(name, problemReporter, ops, list);
    }

    @Override
    public <T> ValueOutput.TypedOutputList<T> list(String name, Codec<T> codec) {
        ListTag list = new ListTag();
        output.put(name, list);
        return new TypedListWrapper<>(problemReporter, name, ops, codec, list);
    }

    @Override
    public void discard(String name) {
        output.remove(name);
    }

    @Override
    public boolean isEmpty() {
        return output.isEmpty();
    }

    public CompoundTag buildResult() {
        return output;
    }

    /**
     * Adds {@code tag} to {@code list} and returns whether the list is now wrapped. A 1.21.1 ListTag holds one tag type,
     * a 26.1 one any mix; on the first element of another type every element is wrapped as {@code {"": tag}} (1.21.1
     * NbtOps' format for mixed lists, which {@link TagValueInput} and NbtOps unwrap), so nothing is lost.
     */
    static boolean append(ListTag list, Tag tag, boolean wrapped) {
        if (!wrapped && (list.isEmpty() || list.getElementType() == tag.getId())) {
            list.add(tag);
            return false;
        }
        if (!wrapped) {
            ListTag plain = list.copy();
            list.clear();
            for (Tag element : plain) {
                list.add(wrap(element));
            }
        }
        list.add(wrap(tag));
        return true;
    }

    private static CompoundTag wrap(Tag tag) {
        CompoundTag compound = new CompoundTag();
        compound.put("", tag);
        return compound;
    }

    public record EncodeToFieldFailedProblem(String name, Object value, DataResult.Error<?> error)
            implements ProblemReporter.Problem {
        @Override
        public String description() {
            return "Failed to encode value '" + value + "' to field '" + name + "': " + error.message();
        }
    }

    public record EncodeToListFailedProblem(String name, Object value, DataResult.Error<?> error)
            implements ProblemReporter.Problem {
        @Override
        public String description() {
            return "Failed to append value '" + value + "' to list '" + name + "': " + error.message();
        }
    }

    public record EncodeToMapFailedProblem(Object value, DataResult.Error<?> error) implements ProblemReporter.Problem {
        @Override
        public String description() {
            return "Failed to merge value '" + value + "' to an object: " + error.message();
        }
    }

    private record ListWrapper(String fieldName, ProblemReporter problemReporter, DynamicOps<Tag> ops, ListTag output)
            implements ValueOutput.ValueOutputList {
        @Override
        public ValueOutput addChild() {
            int index = output.size();
            CompoundTag child = new CompoundTag();
            output.add(child);
            return new TagValueOutput(
                    problemReporter.forChild(new ProblemReporter.IndexedFieldPathElement(fieldName, index)), ops,
                    child);
        }

        @Override
        public void discardLast() {
            output.remove(output.size() - 1);
        }

        @Override
        public boolean isEmpty() {
            return output.isEmpty();
        }
    }

    private static final class TypedListWrapper<T> implements ValueOutput.TypedOutputList<T> {
        private final ProblemReporter problemReporter;
        private final String name;
        private final DynamicOps<Tag> ops;
        private final Codec<T> codec;
        private final ListTag output;
        private boolean wrapped;

        TypedListWrapper(ProblemReporter problemReporter, String name, DynamicOps<Tag> ops, Codec<T> codec,
                ListTag output) {
            this.problemReporter = problemReporter;
            this.name = name;
            this.ops = ops;
            this.codec = codec;
            this.output = output;
        }

        @Override
        public void add(T value) {
            DataResult<Tag> result = codec.encodeStart(ops, value);
            if (result instanceof DataResult.Error<Tag> error) {
                problemReporter.report(new EncodeToListFailedProblem(name, value, error));
            }
            result.resultOrPartial().ifPresent(tag -> wrapped = append(output, tag, wrapped));
        }

        @Override
        public boolean isEmpty() {
            return output.isEmpty();
        }
    }
}
