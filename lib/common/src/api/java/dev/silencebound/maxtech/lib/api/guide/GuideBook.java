package dev.silencebound.maxtech.lib.api.guide;

import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

/**
 * An item that opens a guide when used, such as Storage's manual. Lib opens {@link #guide()} on the using client
 * (before the item's own {@code use} runs); the item's {@code use} should return {@code SUCCESS} so the server sees the
 * use succeed. The guide is declared on NeoForge with {@code lib.neoforge.api.guide.Guides.register(modId)}.
 * <p>
 * Threading: {@link #guide()} on the client thread. Experimental (Lib 0.15.0).
 */
@ApiStatus.Experimental
public interface GuideBook {
    /** The guide this item opens, {@code <modid>:guide}; never null. */
    Identifier guide();
}
