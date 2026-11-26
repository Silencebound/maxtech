package dev.silencebound.maxtech.lib.neoforge.compat.kubejs;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import dev.latvian.mods.kubejs.plugin.builtin.wrapper.ItemWrapper;
import dev.latvian.mods.kubejs.plugin.builtin.wrapper.SizedIngredientWrapper;
import dev.latvian.mods.kubejs.recipe.RecipeScriptContext;
import dev.latvian.mods.kubejs.recipe.component.RecipeComponent;
import dev.latvian.mods.kubejs.recipe.component.RecipeComponentType;
import dev.latvian.mods.kubejs.recipe.component.UniqueIdBuilder;
import dev.latvian.mods.kubejs.recipe.filter.RecipeMatchContext;
import dev.latvian.mods.kubejs.recipe.match.ItemMatch;
import dev.latvian.mods.kubejs.recipe.match.ReplacementMatchInfo;
import dev.latvian.mods.kubejs.util.JsonUtils;
import dev.latvian.mods.rhino.type.TypeInfo;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.recipe.ItemInput;
import dev.silencebound.maxtech.lib.api.recipe.ItemOutput;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import org.jspecify.annotations.Nullable;

/**
 * KubeJS recipe components for Lib's recipe parts. Each one reads and writes the part's own codec, so a script recipe
 * becomes the same JSON as a datapack recipe and the mod's serializer validates it. Scripts can write a part as its
 * JSON object or, for items, in KubeJS's usual short forms: {@code '2x #c:ingots/copper'} for a consumed input,
 * {@code '4x minecraft:stone'} for an output that always drops. A catalyst or a chance output needs the object form,
 * {@code {ingredient: '#c:lenses', consumed: false}} or {@code {item: 'minecraft:stone', chance: 2500}}.
 */
public final class MachineRecipeComponents {
    /** An item input: the JSON object of {@link ItemInput#CODEC} or a (sized) ingredient. */
    public static final RecipeComponent<ItemInput> ITEM_INPUT = new ItemInputComponent(type("item_input"));
    /** An item output: the JSON object of {@link ItemOutput#CODEC} or an item stack. */
    public static final RecipeComponent<ItemOutput> ITEM_OUTPUT = new ItemOutputComponent(type("item_output"));

    private MachineRecipeComponents() {
    }

    private static ResourceKey<RecipeComponentType<?>> type(String name) {
        return RecipeComponentType.key(MaxtechLib.id(name));
    }

    /** {@code from} parsed with {@code codec} when it is a script object holding {@code field}; null otherwise. */
    private static <T> @Nullable T fromObject(RecipeScriptContext cx, @Nullable Object from, Codec<T> codec,
            String field) {
        Map<String, Object> map = cx.cx().optionalMapOf(from);
        if (map == null || !map.containsKey(field)) {
            return null;
        }
        JsonElement json = JsonUtils.of(cx.cx(), from);
        return codec.parse(cx.ops().json(), json).getOrThrow();
    }

    private record ItemInputComponent(ResourceKey<RecipeComponentType<?>> type) implements RecipeComponent<ItemInput> {
        @Override
        public Codec<ItemInput> codec() {
            return ItemInput.CODEC;
        }

        @Override
        public TypeInfo typeInfo() {
            return SizedIngredientWrapper.TYPE_INFO;
        }

        @Override
        public ItemInput wrap(RecipeScriptContext cx, @Nullable Object from) {
            if (from instanceof ItemInput input) {
                return input;
            }
            ItemInput parsed = fromObject(cx, from, ItemInput.CODEC, "ingredient");
            if (parsed != null) {
                return parsed;
            }
            SizedIngredient sized = (SizedIngredient) cx.cx().jsToJava(from, SizedIngredientWrapper.TYPE_INFO);
            return new ItemInput(sized.ingredient(), sized.count());
        }

        @Override
        public boolean matches(RecipeMatchContext cx, ItemInput value, ReplacementMatchInfo match) {
            return match.match() instanceof ItemMatch m && m.matches(cx, value.ingredient(), match.exact());
        }

        @Override
        public String toString() {
            return type.identifier().toString();
        }
    }

    private record ItemOutputComponent(
            ResourceKey<RecipeComponentType<?>> type) implements RecipeComponent<ItemOutput> {
        @Override
        public Codec<ItemOutput> codec() {
            return ItemOutput.CODEC;
        }

        @Override
        public TypeInfo typeInfo() {
            return ItemWrapper.TYPE_INFO;
        }

        @Override
        public ItemOutput wrap(RecipeScriptContext cx, @Nullable Object from) {
            if (from instanceof ItemOutput output) {
                return output;
            }
            ItemOutput parsed = fromObject(cx, from, ItemOutput.CODEC, "item");
            if (parsed != null) {
                return parsed;
            }
            ItemStack stack = (ItemStack) cx.cx().jsToJava(from, ItemWrapper.TYPE_INFO);
            if (stack.isEmpty()) {
                throw new IllegalArgumentException("A recipe output can't be empty: " + from);
            }
            return new ItemOutput(ItemStackTemplate.fromNonEmptyStack(stack), ItemOutput.ALWAYS);
        }

        @Override
        public boolean matches(RecipeMatchContext cx, ItemOutput value, ReplacementMatchInfo match) {
            return match.match() instanceof ItemMatch m && m.matches(cx, value.item().create(), match.exact());
        }

        @Override
        public void buildUniqueId(UniqueIdBuilder builder, ItemOutput value) {
            builder.append(BuiltInRegistries.ITEM.getKey(value.item().create().getItem()));
        }

        @Override
        public String toString() {
            return type.identifier().toString();
        }
    }
}
