package dev.silencebound.maxtech.storage.research;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Research node ids of the shipped tree that were renamed, old id to new. A team that completed an old node loads with
 * the new one completed as well ({@link ResearchTeam}), so renaming a node never costs a save its progress. The old id
 * stays in the team's list too: a data pack that still ships the old node keeps it done. Saved research has no version
 * stamp (the team list is not a map), so this runs on every load; it is idempotent.
 */
public final class ResearchRenames {
    /**
     * M7R item 8: the controller cluster's three nodes became the multiblock controller's size steps.
     */
    private static final Map<Identifier, Identifier> RENAMED = Map.of(MaxtechStorage.id("cluster_expansion_1"),
            MaxtechStorage.id("controller_size_1"), MaxtechStorage.id("cluster_expansion_2"),
            MaxtechStorage.id("controller_size_2"), MaxtechStorage.id("cluster_expansion_3"),
            MaxtechStorage.id("controller_size_3"));

    /**
     * Nodes of the shipped tree that gained a prerequisite which gates what they unlock, node to prerequisite. M7R item
     * 8: Efficient Power (the efficiency core and surge regulator cards) follows Upgrade Cards (the card slots). A team
     * that completed such a node without the prerequisite gets the node back as a skip token instead
     * ({@link ResearchTeam}): it keeps the progress but holds no card unlock without the slot unlock, and it can spend
     * the token once it has the prerequisite. Never more than the old tree gave, and nothing lost.
     */
    private static final Map<Identifier, Identifier> GATED = Map.of(MaxtechStorage.id("efficient_power"),
            MaxtechStorage.id("controller_cards"));

    private ResearchRenames() {
    }

    /** The prerequisite {@code node} must have for a save's completion of it to stand, or null. */
    public static @Nullable Identifier gatedBy(Identifier node) {
        return GATED.get(node);
    }

    /** The nodes {@link #gatedBy} names a prerequisite for. */
    public static Set<Identifier> gated() {
        return GATED.keySet();
    }

    /** The node {@code old} was renamed to, or null when it was not renamed. */
    public static @Nullable Identifier renamed(Identifier old) {
        return RENAMED.get(old);
    }
}
