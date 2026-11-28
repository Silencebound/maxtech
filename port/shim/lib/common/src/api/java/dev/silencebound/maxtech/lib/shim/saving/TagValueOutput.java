package dev.silencebound.maxtech.lib.shim.saving;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.DataResult.Error;
import com.mojang.serialization.DataResult.Success;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.ProblemReporter;
import org.jspecify.annotations.Nullable;
public class TagValueOutput implements ValueOutput {
    public static TagValueOutput createWithContext(ProblemReporter problemReporter, HolderLookup.Provider provider) { throw new UnsupportedOperationException("port stub"); }
    public static TagValueOutput createWithoutContext(ProblemReporter problemReporter) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public <T> void store(String name, Codec<T> codec, T value) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public <T> void storeNullable(String name, Codec<T> codec, @Nullable T value) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public <T> void store(MapCodec<T> codec, T value) { throw new UnsupportedOperationException("port stub"); }
    public void store(CompoundTag tag) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public void putBoolean(String name, boolean value) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public void putByte(String name, byte value) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public void putShort(String name, short value) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public void putInt(String name, int value) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public void putLong(String name, long value) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public void putFloat(String name, float value) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public void putDouble(String name, double value) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public void putString(String name, String value) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public void putIntArray(String name, int[] value) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public ValueOutput child(String name) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public ValueOutput.ValueOutputList childrenList(String name) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public <T> ValueOutput.TypedOutputList<T> list(String name, Codec<T> codec) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public void discard(String name) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public boolean isEmpty() { throw new UnsupportedOperationException("port stub"); }
    public CompoundTag buildResult() { throw new UnsupportedOperationException("port stub"); }
    public record EncodeToFieldFailedProblem(String name, Object value, Error<?> error) {
        public String description() { throw new UnsupportedOperationException("port stub"); }
    }
    public record EncodeToListFailedProblem(String name, Object value, Error<?> error) {
        public String description() { throw new UnsupportedOperationException("port stub"); }
    }
    public record EncodeToMapFailedProblem(Object value, Error<?> error) {
        public String description() { throw new UnsupportedOperationException("port stub"); }
    }
}
