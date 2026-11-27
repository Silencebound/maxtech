package dev.silencebound.maxtech.lib.neoforge.client;

import dev.silencebound.maxtech.lib.neoforge.api.client.conduit.ConduitModelHook;
import dev.silencebound.maxtech.lib.neoforge.api.client.conduit.RegisterConduitModelsEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.resources.Identifier;

/**
 * Collects the hooks of {@link RegisterConduitModelsEvent} against the conduits marked {@code customModel}: a hook for
 * an unmarked or already hooked conduit fails at once, a marked conduit left without one fails in {@link #finish}.
 */
final class ConduitModelHooks {
    /** One hooked conduit block. */
    record Hooked(Identifier block, ConduitModelHook hook, boolean translucent) {
    }

    private final Map<Identifier, Boolean> marked;
    private final Map<Identifier, Hooked> hooks = new HashMap<>();

    /** {@code marked}: the custom-model conduit blocks, with whether each is translucent. */
    ConduitModelHooks(Map<Identifier, Boolean> marked) {
        this.marked = Map.copyOf(marked);
    }

    /** The event's sink. */
    void add(Identifier block, ConduitModelHook hook) {
        Boolean translucent = marked.get(block);
        if (translucent == null) {
            throw new IllegalStateException("A conduit model hook was registered for " + block
                    + ", which is not a conduit marked customModel on its builder");
        }
        if (hooks.putIfAbsent(block, new Hooked(block, hook, translucent)) != null) {
            throw new IllegalStateException("Conduit " + block + " has more than one model hook");
        }
    }

    /** Every hooked conduit by block id; throws if a marked conduit has no hook. */
    Map<Identifier, Hooked> finish() {
        List<String> missing = new ArrayList<>();
        for (Identifier block : marked.keySet()) {
            if (!hooks.containsKey(block)) {
                missing.add(block.toString());
            }
        }
        if (!missing.isEmpty()) {
            missing.sort(null);
            throw new IllegalStateException("Conduit " + String.join(", ", missing)
                    + " is marked customModel but its mod registered no hook in RegisterConduitModelsEvent");
        }
        return Map.copyOf(hooks);
    }

    /** A hook's sprites resolved through {@code atlas}, by atlas id; asks the hook once. */
    static <S> Map<Identifier, S> sprites(ConduitModelHook hook, Function<Identifier, S> atlas) {
        Set<Identifier> ids = hook.sprites();
        Map<Identifier, S> sprites = new HashMap<>();
        for (Identifier id : ids) {
            sprites.put(id, atlas.apply(id));
        }
        return Map.copyOf(sprites);
    }
}
