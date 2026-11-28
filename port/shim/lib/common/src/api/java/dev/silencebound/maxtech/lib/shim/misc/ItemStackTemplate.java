package dev.silencebound.maxtech.lib.shim.misc;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 26.1's {@code net.minecraft.world.item.ItemStackTemplate} for 1.21.1: an item, a count and a component patch that
 * make stacks on demand and never hold one. Same JSON ({@code {"id", "count", "components"}}, or a bare item id) and
 * network form as 26.1. The count is 1 to 99, the item is never air.
 *
 * @param item
 *            the item, not air
 * @param count
 *            how many items {@link #create()} makes
 * @param components
 *            changes to the item's default components
 */
public record ItemStackTemplate(Holder<Item> item, int count, DataComponentPatch components) {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final MapCodec<ItemStackTemplate> MAP_CODEC = RecordCodecBuilder
            .mapCodec(i -> i.group(ItemStack.ITEM_NON_AIR_CODEC.fieldOf("id").forGetter(ItemStackTemplate::item),
                    ExtraCodecs.intRange(1, 99).optionalFieldOf("count", 1).forGetter(ItemStackTemplate::count),
                    DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY)
                            .forGetter(ItemStackTemplate::components))
                    .apply(i, ItemStackTemplate::new));
    public static final Codec<ItemStackTemplate> CODEC = Codec.withAlternative(MAP_CODEC.codec(),
            ItemStack.ITEM_NON_AIR_CODEC, item -> new ItemStackTemplate(item.value()));
    public static final StreamCodec<RegistryFriendlyByteBuf, ItemStackTemplate> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.holderRegistry(Registries.ITEM), ItemStackTemplate::item, ByteBufCodecs.VAR_INT,
            ItemStackTemplate::count, DataComponentPatch.STREAM_CODEC, ItemStackTemplate::components,
            ItemStackTemplate::new);

    /** Throws {@link IllegalStateException} for air or a count of 0. */
    public ItemStackTemplate {
        if (count == 0 || item.is(Items.AIR.builtInRegistryHolder())) {
            throw new IllegalStateException("Item must be non-empty");
        }
    }

    public ItemStackTemplate(Item item) {
        this(item.builtInRegistryHolder(), 1, DataComponentPatch.EMPTY);
    }

    public ItemStackTemplate(Item item, int count) {
        this(item.builtInRegistryHolder(), count, DataComponentPatch.EMPTY);
    }

    public ItemStackTemplate(Item item, DataComponentPatch patch) {
        this(item.builtInRegistryHolder(), 1, patch);
    }

    public ItemStackTemplate(Item item, int count, DataComponentPatch patch) {
        this(item.builtInRegistryHolder(), count, patch);
    }

    public ItemStackTemplate(Holder<Item> item) {
        this(item, 1, DataComponentPatch.EMPTY);
    }

    public ItemStackTemplate(Holder<Item> item, int count) {
        this(item, count, DataComponentPatch.EMPTY);
    }

    public ItemStackTemplate(Holder<Item> item, DataComponentPatch patch) {
        this(item, 1, patch);
    }

    /** Throws {@link IllegalStateException} for an empty stack. */
    public static ItemStackTemplate fromNonEmptyStack(ItemStack stack) {
        if (stack.isEmpty()) {
            throw new IllegalStateException("Stack must be non-empty");
        }
        return new ItemStackTemplate(stack.getItemHolder(), stack.getCount(), stack.getComponentsPatch());
    }

    public ItemStackTemplate withCount(int count) {
        return this.count == count ? this : new ItemStackTemplate(item, count, components);
    }

    /** A new stack; {@link ItemStack#EMPTY} when its components do not validate. */
    public ItemStack create() {
        return validated(new ItemStack(item, count, components));
    }

    /** A new stack with {@code additionalPatch} applied first and this template's components over it. */
    public ItemStack apply(DataComponentPatch additionalPatch) {
        return apply(count, additionalPatch);
    }

    public ItemStack apply(int count, DataComponentPatch additionalPatch) {
        ItemStack result = new ItemStack(item, count, additionalPatch);
        result.applyComponents(components);
        return validated(result);
    }

    public Holder<Item> typeHolder() {
        return item;
    }

    /** The value of {@code type} on the stack this makes, or null. */
    @SuppressWarnings("unchecked") // the patch stores a T for the type it is keyed by
    public <T> @Nullable T get(DataComponentType<? extends T> type) {
        Optional<? extends T> patched = components.get(type);
        return patched != null ? patched.orElse(null) : (T) item.value().components().get(type);
    }

    private ItemStack validated(ItemStack stack) {
        String problem = ItemStack.validateComponents(stack.getComponents()).error().map(e -> e.message())
                .orElse(stack.getCount() > stack.getMaxStackSize() ? "stack size above the maximum" : null);
        if (problem != null) {
            LOGGER.warn("Can't create item stack with properties {}, error: {}", this, problem);
            return ItemStack.EMPTY;
        }
        return stack;
    }
}
