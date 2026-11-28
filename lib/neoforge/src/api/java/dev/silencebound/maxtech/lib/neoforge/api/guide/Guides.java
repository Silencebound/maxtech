package dev.silencebound.maxtech.lib.neoforge.api.guide;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * Declares a mod's guide, {@code <modid>:guide}, drawn by GuideME. Its pages are every {@code .md} under
 * {@code assets/<modid>/guides/<modid>/guide/}, the start page {@code index.md}; a page's {@code item_ids} let
 * GuideME's hold key open it from an item's tooltip. Besides GuideME's tags the pages may use
 * {@code <Config key="..." unit="..." />}, a value from
 * {@link dev.silencebound.maxtech.lib.api.guide.GuideConfigValues}, and an item that implements
 * {@link dev.silencebound.maxtech.lib.api.guide.GuideBook} opens its guide when used. The build's {@code runClient}
 * reloads saved pages while the guide is open, and {@code runExportGuide} exports the guide for its website.
 * <p>
 * Threading: {@link #register} during mod construction, from the mod's client entrypoint; a dedicated server has no
 * guides, so a call there only records the id. Experimental (Lib 0.15.0).
 */
@ApiStatus.Experimental
public final class Guides {
    /** The path of every mod's guide id. */
    public static final String PATH = "guide";
    private static final Set<Identifier> REGISTERED = new LinkedHashSet<>();
    private static @Nullable Consumer<Identifier> declarer;

    private Guides() {
    }

    /**
     * Declares the guide of {@code modId} and returns its id, {@code <modId>:guide}. A second call for the same mod
     * returns the id and declares nothing. Throws {@link net.minecraft.IdentifierException} for an invalid mod id.
     */
    public static synchronized Identifier register(String modId) {
        Identifier id = Identifier.fromNamespaceAndPath(modId, PATH);
        Consumer<Identifier> target = declarer;
        if (REGISTERED.add(id) && target != null) {
            target.accept(id);
        }
        return id;
    }

    /** The guides declared so far, in the order they were registered; never null. Any thread. */
    public static synchronized Set<Identifier> registered() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(REGISTERED));
    }

    /**
     * Lib's client sets what declares a guide with GuideME, during its own construction; guides registered before are
     * declared now. Not for mods.
     */
    @ApiStatus.Internal
    public static synchronized void declareWith(Consumer<Identifier> target) {
        declarer = target;
        REGISTERED.forEach(target);
    }
}
