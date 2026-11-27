package dev.silencebound.maxtech.lib.api.client.gui;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.ApiStatus;

/**
 * How a fluid looks in a screen: its still sprite, the tint that goes over it and its name, from the fluid's own client
 * data, so fluids from any mod draw as they do in the world. Lib's loader glue installs the implementation at client
 * start; {@link AccentTank} draws with it. The sprite lives on the block atlas, not the GUI atlas, and may be animated.
 * Client thread only; {@link #get()} throws {@link IllegalStateException} before Lib installed one (never the case once
 * a screen can open).
 */
@ApiStatus.Experimental
public interface FluidLook {
    /** The fluid's still sprite; the missing sprite when the fluid has no model. */
    TextureAtlasSprite sprite(FluidKey fluid);

    /** The ARGB tint to multiply the sprite with, opaque; white for an untinted fluid. */
    int tint(FluidKey fluid);

    /** The fluid's display name, with its components taken into account. */
    Component name(FluidKey fluid);

    /** {@link #sprite(FluidKey)} of {@code fluid} without components; throws for the empty fluid. */
    default TextureAtlasSprite sprite(Fluid fluid) {
        return sprite(FluidKey.of(fluid));
    }

    /** {@link #tint(FluidKey)} of {@code fluid} without components; throws for the empty fluid. */
    default int tint(Fluid fluid) {
        return tint(FluidKey.of(fluid));
    }

    /** {@link #name(FluidKey)} of {@code fluid} without components; throws for the empty fluid. */
    default Component name(Fluid fluid) {
        return name(FluidKey.of(fluid));
    }

    /** The installed look. */
    static FluidLook get() {
        FluidLook look = FluidLookSource.look;
        if (look == null) {
            throw new IllegalStateException("No fluid look installed yet");
        }
        return look;
    }

    /** Sets the look {@link #get()} returns; Lib calls it once at client start. */
    @ApiStatus.Internal
    static void install(FluidLook look) {
        FluidLookSource.look = look;
    }
}
