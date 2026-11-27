package dev.silencebound.maxtech.lib.api.client.gui;

import dev.silencebound.maxtech.lib.api.client.resource.ResourceLook;
import dev.silencebound.maxtech.lib.api.client.resource.ResourceLooks;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import java.util.Optional;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.ApiStatus;

/**
 * The fluid kind's {@link ResourceLook}: still and flowing sprite, tint, name and light from the fluid's own client
 * data, so fluids from any mod draw as they do in the world. Lib registers it for {@link ResourceKinds#FLUID} at client
 * start; {@link AccentTank} and conduit contents draw with it. Client thread only.
 */
@ApiStatus.Experimental
public interface FluidLook extends ResourceLook<FluidKey> {
    /** {@link ResourceLook#sprite} of {@code fluid} without components; throws for the empty fluid. */
    default TextureAtlasSprite sprite(Fluid fluid) {
        return sprite(FluidKey.of(fluid));
    }

    /** {@link ResourceLook#tint} of {@code fluid} without components; throws for the empty fluid. */
    default int tint(Fluid fluid) {
        return tint(FluidKey.of(fluid));
    }

    /** {@link ResourceLook#name} of {@code fluid} without components; throws for the empty fluid. */
    default Component name(Fluid fluid) {
        return name(FluidKey.of(fluid));
    }

    /**
     * The fluid kind's look Lib registered.
     *
     * @throws IllegalStateException
     *             before Lib registered it (never the case once a screen can open)
     */
    static FluidLook get() {
        Optional<ResourceLook<FluidKey>> look = ResourceLooks.get(ResourceKinds.FLUID);
        if (look.isPresent() && look.get() instanceof FluidLook fluid) {
            return fluid;
        }
        throw new IllegalStateException("No fluid look registered yet");
    }
}
