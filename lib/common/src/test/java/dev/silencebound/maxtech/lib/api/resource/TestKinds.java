package dev.silencebound.maxtech.lib.api.resource;

import com.mojang.serialization.Codec;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;

/** Resource kinds for JUnit tests in other packages, in a table of their own (the built-in kinds need registries). */
public final class TestKinds {
    private static final KindTable TABLE = new KindTable();
    /** Keys are strings, saved under {@code value}. */
    public static final ResourceKind<String> TEXT = TABLE.register(Identifier.fromNamespaceAndPath("test", "text"),
            Codec.STRING, ByteBufCodecs.STRING_UTF8.cast(), key -> key, AmountUnit.ITEMS);
    /** Keys are ints, saved under {@code value}. */
    public static final ResourceKind<Integer> NUMBER = TABLE.register(Identifier.fromNamespaceAndPath("test", "number"),
            Codec.INT, ByteBufCodecs.VAR_INT.cast(), key -> key, AmountUnit.MILLIBUCKETS);

    private TestKinds() {
    }

    /** The kind registered in the test table under {@code id}. */
    public static Optional<ResourceKind<?>> byId(Identifier id) {
        return TABLE.byId(id);
    }
}
