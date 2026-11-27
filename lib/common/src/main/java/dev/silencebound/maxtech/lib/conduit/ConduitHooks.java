package dev.silencebound.maxtech.lib.conduit;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnectionRule;
import dev.silencebound.maxtech.lib.api.conduit.ConduitWrenchHook;

/**
 * The behaviour hooks of one conduit block, from the api builder ({@code Conduits.Builder}); addons cannot subclass
 * {@link ConduitBlock}, so this is how their conduits differ. {@link #NONE} for every block made otherwise.
 */
public record ConduitHooks(ConduitWrenchHook wrench, ConduitConnectionRule connectionRule) {
    /** Lib's behaviour alone. */
    public static final ConduitHooks NONE = new ConduitHooks(ConduitWrenchHook.NONE, ConduitConnectionRule.ANY);
}
