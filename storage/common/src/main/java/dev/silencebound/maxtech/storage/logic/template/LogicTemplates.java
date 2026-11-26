package dev.silencebound.maxtech.storage.logic.template;

import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * The rule templates the server loaded ({@link LogicTemplateListener}), ordered by file id (namespace, then path). A
 * reload replaces them all at once; the controller's Templates list asks for them each time it opens. Safe to read from
 * any thread (the list is swapped whole); written on the server thread.
 */
public final class LogicTemplates {
    /** One loaded template file. */
    public record Entry(Identifier id, LogicTemplate<LogicKey> template) {
    }

    // By the id as text: Identifier#compareTo compares the path first.
    private static final Comparator<Entry> BY_ID = Comparator.comparing(entry -> entry.id().toString());

    private static volatile List<Entry> templates = List.of();

    private LogicTemplates() {
    }

    /** Takes the templates of a (re)load, replacing all earlier ones. */
    public static void apply(Map<Identifier, LogicTemplate<LogicKey>> loaded) {
        List<Entry> sorted = new ArrayList<>(loaded.size());
        for (Map.Entry<Identifier, LogicTemplate<LogicKey>> entry : loaded.entrySet()) {
            sorted.add(new Entry(entry.getKey(), entry.getValue()));
        }
        sorted.sort(BY_ID);
        templates = List.copyOf(sorted);
    }

    /** Every loaded template, by file id. */
    public static List<Entry> all() {
        return templates;
    }

    /** The template in file {@code id}; empty when no pack has it (any more). */
    public static Optional<LogicTemplate<LogicKey>> get(Identifier id) {
        for (Entry entry : templates) {
            if (entry.id().equals(id)) {
                return Optional.of(entry.template());
            }
        }
        return Optional.empty();
    }
}
