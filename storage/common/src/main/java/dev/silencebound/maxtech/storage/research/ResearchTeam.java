package dev.silencebound.maxtech.storage.research;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * One team's research progress (PRD 7.2: research is shared per team): completed nodes, task counters, points, skip
 * tokens, the kinds its networks discovered and the advancements already credited. A team is anchored on the player who
 * first needed one; other players join it while Lib's owner security puts them on the anchor's team
 * ({@link ResearchData}). Server thread only; changes go through {@link Research}, which marks the save data dirty.
 */
public final class ResearchTeam {
    static final Codec<ResearchTeam> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("anchor").forGetter(t -> t.anchor),
            Codec.STRING.optionalFieldOf("anchor_name", "").forGetter(t -> t.anchorName),
            Codec.LONG.optionalFieldOf("created", 0L).forGetter(t -> t.created),
            UUIDUtil.CODEC.listOf().optionalFieldOf("members", List.of()).forGetter(t -> List.copyOf(t.members)),
            Identifier.CODEC.listOf().optionalFieldOf("completed", List.of()).forGetter(t -> List.copyOf(t.completed)),
            Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("counters", Map.of()).forGetter(
                    t -> Map.copyOf(t.counters)),
            Codec.LONG.optionalFieldOf("points", 0L).forGetter(t -> t.points),
            Codec.LONG.optionalFieldOf("token_progress", 0L).forGetter(t -> t.tokenProgress),
            Codec.INT.optionalFieldOf("tokens", 0).forGetter(t -> t.tokens),
            Identifier.CODEC.listOf().optionalFieldOf("discovered_items", List.of())
                    .forGetter(t -> List.copyOf(t.discoveredItems)),
            Identifier.CODEC.listOf().optionalFieldOf("discovered_fluids", List.of())
                    .forGetter(t -> List.copyOf(t.discoveredFluids)),
            Identifier.CODEC.listOf().optionalFieldOf("advancements", List.of())
                    .forGetter(t -> List.copyOf(t.advancements)))
            .apply(instance, ResearchTeam::new));

    final UUID anchor;
    String anchorName;
    /** Order of creation; when a player's team could be two teams, the older wins. */
    final long created;
    final Set<UUID> members = new ObjectOpenHashSet<>();
    final Set<Identifier> completed = new ObjectOpenHashSet<>();
    final Object2LongOpenHashMap<String> counters = new Object2LongOpenHashMap<>();
    long points;
    /** Points earned since the last skip token they paid for. */
    long tokenProgress;
    int tokens;
    final Set<Identifier> discoveredItems = new ObjectOpenHashSet<>();
    final Set<Identifier> discoveredFluids = new ObjectOpenHashSet<>();
    final Set<Identifier> advancements = new ObjectOpenHashSet<>();
    /** The save data holding the team, marked dirty on every change. */
    private @Nullable ResearchData data;
    /** Rises with every change; not saved. */
    private int revision;

    ResearchTeam(UUID anchor, String anchorName, long created) {
        this.anchor = anchor;
        this.anchorName = anchorName;
        this.created = created;
        members.add(anchor);
    }

    // The codec's constructor: one argument per saved field.
    @SuppressWarnings("TooManyParameters")
    private ResearchTeam(UUID anchor, String anchorName, long created, List<UUID> members, List<Identifier> completed,
            Map<String, Long> counters, long points, long tokenProgress, int tokens, List<Identifier> items,
            List<Identifier> fluids, List<Identifier> advancements) {
        this(anchor, anchorName, created);
        this.members.addAll(members);
        for (Identifier node : completed) {
            this.completed.add(node);
            // A node of the shipped tree that was renamed counts as done under its new id too.
            Identifier renamed = ResearchRenames.renamed(node);
            if (renamed != null) {
                this.completed.add(renamed);
            }
        }
        this.counters.putAll(counters);
        this.points = points;
        this.tokenProgress = tokenProgress;
        this.tokens = tokens;
        // A completed node whose new prerequisite gates its unlocks is held back as a token (ResearchRenames).
        for (Identifier node : ResearchRenames.gated()) {
            Identifier needs = ResearchRenames.gatedBy(node);
            if (needs != null && this.completed.contains(node) && !this.completed.contains(needs)) {
                this.completed.remove(node);
                this.tokens++;
            }
        }
        this.discoveredItems.addAll(items);
        this.discoveredFluids.addAll(fluids);
        this.advancements.addAll(advancements);
    }

    void attach(ResearchData data) {
        this.data = data;
    }

    /** Marks the save data holding the team as changed and counts the change for open research screens. */
    void dirty() {
        revision++;
        ResearchData current = data;
        if (current != null) {
            current.setDirty();
        }
    }

    /** Rises with every change of the team's progress, so an open screen knows when to refresh. */
    public int revision() {
        return revision;
    }

    /** The player the team is anchored on. */
    public UUID anchor() {
        return anchor;
    }

    /** The anchor as an owner record, for Lib's team checks. */
    OwnerRecord anchorRecord() {
        return new OwnerRecord(anchor, anchorName, List.of(), true);
    }

    /** The players known to belong to the team, the anchor included. */
    public List<UUID> members() {
        return new ArrayList<>(members);
    }

    /** Whether node {@code id} was completed (start nodes count as complete without being listed). */
    public boolean completed(Identifier id) {
        return completed.contains(id);
    }

    /** The value of counter {@code name}; 0 before anything was counted. */
    public long counter(String name) {
        return counters.getLong(name);
    }

    public long points() {
        return points;
    }

    public int tokens() {
        return tokens;
    }

    /** Points earned towards the next skip token. */
    public long tokenProgress() {
        return tokenProgress;
    }

    /** How many kinds (items plus fluids) the team's networks discovered. */
    public int discovered() {
        return discoveredItems.size() + discoveredFluids.size();
    }
}
