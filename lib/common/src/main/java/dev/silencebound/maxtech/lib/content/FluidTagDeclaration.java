package dev.silencebound.maxtech.lib.content;

import java.util.function.Supplier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;

/** A fluid of ours in a fluid tag; the fluid resolves only in datagen, after registration. */
public record FluidTagDeclaration(TagKey<Fluid> tag, Supplier<? extends Fluid> fluid) {
}
