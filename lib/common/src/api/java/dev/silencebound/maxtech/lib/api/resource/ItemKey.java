package dev.silencebound.maxtech.lib.api.resource;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * An item with its component patch; see {@link StackKey}. Never air. Immutable; any thread.
 */
@SuppressWarnings("ReferenceEquality") // items are registry singletons and do not override equals
public final class ItemKey implements StackKey {
    /** {@code id} and optional {@code components}; air fails to decode. */
    public static final MapCodec<ItemKey> MAP_CODEC = RecordCodecBuilder
            .<ItemKey>mapCodec(i -> i.group(BuiltInRegistries.ITEM.byNameCodec().fieldOf("id").forGetter(ItemKey::item),
                    DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY)
                            .forGetter(ItemKey::components))
                    .apply(i, ItemKey::new))
            .validate(
                    key -> key.item == Items.AIR ? DataResult.error(() -> "Item key of air") : DataResult.success(key));
    /** The item's registry id, then the patch; air fails to decode. */
    public static final StreamCodec<RegistryFriendlyByteBuf, ItemKey> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.registry(Registries.ITEM), ItemKey::item, DataComponentPatch.STREAM_CODEC,
            ItemKey::components, ItemKey::decoded);

    private final Item item;
    private final DataComponentPatch components;
    private final int hash;

    private ItemKey(Item item, DataComponentPatch components) {
        this.item = item;
        this.components = components;
        this.hash = 31 * System.identityHashCode(item) + components.hashCode();
    }

    /** The stream codec's constructor: air from the network fails decoding instead of throwing a plain exception. */
    private static ItemKey decoded(Item item, DataComponentPatch components) {
        if (item == Items.AIR) {
            throw new DecoderException("Item key of air");
        }
        return new ItemKey(item, components);
    }

    /** The key of {@code item} without components. Throws {@link IllegalArgumentException} for air. */
    public static ItemKey of(Item item) {
        return of(item, DataComponentPatch.EMPTY);
    }

    /** The key of {@code item} with {@code components}. Throws {@link IllegalArgumentException} for air. */
    public static ItemKey of(Item item, DataComponentPatch components) {
        if (item == Items.AIR) {
            throw new IllegalArgumentException("Item key of air");
        }
        return new ItemKey(item, components);
    }

    /** The key of a stack's item and components; the count is ignored. Throws for an empty stack. */
    public static ItemKey of(ItemStack stack) {
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("Item key of an empty stack");
        }
        return new ItemKey(stack.getItem(), stack.getComponentsPatch());
    }

    /** The item, never air. */
    public Item item() {
        return item;
    }

    @Override
    public DataComponentPatch components() {
        return components;
    }

    @Override
    public Kind kind() {
        return Kind.ITEM;
    }

    /** The {@link Item}. */
    @Override
    public Object base() {
        return item;
    }

    /** A new stack of {@code count} of this item with these components. */
    public ItemStack toStack(int count) {
        return new ItemStack(item.builtInRegistryHolder(), count, components);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof ItemKey key && hash == key.hash && item == key.item
                && components.equals(key.components));
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return "ItemKey[" + BuiltInRegistries.ITEM.getKey(item) + (components.isEmpty() ? "" : " " + components) + "]";
    }
}
