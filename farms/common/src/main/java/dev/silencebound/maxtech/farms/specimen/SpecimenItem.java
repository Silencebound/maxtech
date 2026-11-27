package dev.silencebound.maxtech.farms.specimen;

import dev.silencebound.maxtech.farms.registry.FarmsContent;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import org.jspecify.annotations.Nullable;

/** A specimen of a mob (later also crops, saplings, animals, minerals); its data is {@link Specimen}. */
public class SpecimenItem extends Item {
    public static final int MAX_STACK = 16;

    public SpecimenItem(Item.Properties properties) {
        super(properties);
    }

    /** A specimen item carrying {@code specimen}. */
    public static ItemStack of(Specimen specimen) {
        ItemStack stack = new ItemStack(FarmsContent.SPECIMEN.get());
        stack.set(FarmsContent.SPECIMEN_DATA.get(), specimen);
        return stack;
    }

    /** The specimen data on {@code stack}, or null for a blank specimen item. */
    public static @Nullable Specimen data(ItemStack stack) {
        return stack.get(FarmsContent.SPECIMEN_DATA.get());
    }

    /** The sampled thing's name: the mob's name for a mob, otherwise its id. */
    public static Component targetName(Specimen specimen) {
        if (specimen.kind() == SpecimenKind.MOB) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(specimen.target()).orElse(null);
            if (type != null) {
                return type.getDescription();
            }
        }
        return Component.literal(specimen.target().toString());
    }

    @Override
    public Component getName(ItemStack stack) {
        Specimen specimen = data(stack);
        return specimen == null
                ? super.getName(stack)
                : Component.translatable("item.maxtech_farms.specimen.named", targetName(specimen));
    }

    @Override
    @SuppressWarnings("deprecation") // overriding is intended; only calling it is deprecated
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
            Consumer<Component> builder, TooltipFlag flag) {
        Specimen specimen = data(stack);
        if (specimen == null) {
            return;
        }
        builder.accept(Component
                .translatable("tooltip.maxtech_farms.specimen.kind",
                        Component.translatable(specimen.kind().translationKey()), targetName(specimen))
                .withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("tooltip.maxtech_farms.specimen.generation", specimen.generation())
                .withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("tooltip.maxtech_farms.specimen.values", specimen.growth(),
                specimen.gain(), specimen.strength()).withStyle(ChatFormatting.GRAY));
        Component traits = specimen.traits().isEmpty()
                ? Component.translatable("tooltip.maxtech_farms.specimen.no_traits")
                : ComponentUtils.formatList(specimen.traits(),
                        (SpecimenTrait trait) -> Component.translatable(trait.translationKey()));
        builder.accept(
                Component.translatable("tooltip.maxtech_farms.specimen.traits", traits).withStyle(ChatFormatting.GRAY));
    }
}
