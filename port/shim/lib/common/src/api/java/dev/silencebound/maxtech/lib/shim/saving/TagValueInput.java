package dev.silencebound.maxtech.lib.shim.saving;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Streams;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.DataResult.Error;
import com.mojang.serialization.DataResult.Success;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Optional;
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
import net.minecraft.util.ProblemReporter;
import org.jspecify.annotations.Nullable;
public class TagValueInput implements ValueInput {
    public static ValueInput create(ProblemReporter problemReporter, HolderLookup.Provider holders, CompoundTag tag) { throw new UnsupportedOperationException("port stub"); }
    public static ValueInput.ValueInputList create(ProblemReporter problemReporter, HolderLookup.Provider holders, List<CompoundTag> tags) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public <T> Optional<T> read(String name, Codec<T> codec) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public <T> Optional<T> read(MapCodec<T> codec) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public Optional<ValueInput> child(String name) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public ValueInput childOrEmpty(String name) { throw new UnsupportedOperationException("port stub"); }
    public ValueInput rawChildOrEmpty(String key) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public Optional<ValueInput.ValueInputList> childrenList(String name) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public ValueInput.ValueInputList childrenListOrEmpty(String name) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public <T> Optional<ValueInput.TypedInputList<T>> list(String name, Codec<T> codec) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public <T> ValueInput.TypedInputList<T> listOrEmpty(String name, Codec<T> codec) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public boolean getBooleanOr(String name, boolean defaultValue) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public byte getByteOr(String name, byte defaultValue) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public int getShortOr(String name, short defaultValue) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public Optional<Integer> getInt(String name) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public int getIntOr(String name, int defaultValue) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public long getLongOr(String name, long defaultValue) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public Optional<Long> getLong(String name) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public float getFloatOr(String name, float defaultValue) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public double getDoubleOr(String name, double defaultValue) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public Optional<String> getString(String name) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public String getStringOr(String name, String defaultValue) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public Optional<int[]> getIntArray(String name) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public HolderLookup.Provider lookup() { throw new UnsupportedOperationException("port stub"); }
    public java.util.Set<String> keySet() { throw new UnsupportedOperationException("port stub"); }
    public record DecodeFromFieldFailedProblem(String name, Tag tag, Error<?> error) {
        public String description() { throw new UnsupportedOperationException("port stub"); }
    }
    public record DecodeFromListFailedProblem(String name, int index, Tag tag, Error<?> error) {
        public String description() { throw new UnsupportedOperationException("port stub"); }
    }
    public record DecodeFromMapFailedProblem(Error<?> error) {
        public String description() { throw new UnsupportedOperationException("port stub"); }
    }
    public record UnexpectedListElementTypeProblem(String name, int index, TagType<?> expected, TagType<?> actual) {
        public String description() { throw new UnsupportedOperationException("port stub"); }
    }
    public record UnexpectedNonNumberProblem(String name, TagType<?> actual) {
        public String description() { throw new UnsupportedOperationException("port stub"); }
    }
    public record UnexpectedTypeProblem(String name, TagType<?> expected, TagType<?> actual) {
        public String description() { throw new UnsupportedOperationException("port stub"); }
    }
}
