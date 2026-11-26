package dev.silencebound.maxtech.storage.research;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

/**
 * One node of the research tree, an entry of the datapack registry {@code maxtech_storage:research}
 * ({@code data/<namespace>/maxtech_storage/research/<name>.json}; loaded with the world and synced to clients, so the
 * research screen can draw the tree). JSON:
 *
 * <pre>
 * {"branch": "memory", "order": 1, "tier": 2, "icon": "maxtech_storage:memory_module_1",
 *  "requires": ["maxtech_storage:logic_mask"],
 *  "any_of": [{"type": "task", "task": "store_items", "count": 4096}, {"type": "points", "cost": 150}],
 *  "unlocks": ["maxtech_storage:memory_module_2"]}
 * </pre>
 *
 * A node with no options is complete from the start (the tree's start nodes). Its name is the lang key
 * {@code research.<namespace>.<path>}, its branch's {@code research.maxtech_storage.branch.<branch>}. Immutable.
 *
 * @param branch
 *            the branch it is listed under
 * @param order
 *            its row within the branch, lowest first (ties by id)
 * @param tier
 *            the step of Storage's tier ladder it belongs to (0 to 5), for the screen's progression panel
 * @param icon
 *            an item id drawn beside its name; none draws a blank
 * @param requires
 *            nodes that must be complete before this one can complete
 * @param anyOf
 *            the options, any one of which completes it
 * @param unlocks
 *            the unlock ids it grants ({@link ResearchGates}); its own id is always granted too
 */
public record ResearchNode(String branch, int order, int tier, Optional<Identifier> icon, List<Identifier> requires,
        List<ResearchOption> anyOf, List<Identifier> unlocks) {
    /** Bounds that keep a broken data pack from building huge nodes. */
    static final int MAX_ENTRIES = 16;
    /** The highest tier of Storage's tier ladder (PRD 7.2: 0 local to 5 endgame). */
    public static final int MAX_TIER = 5;

    public static final ResourceKey<Registry<ResearchNode>> REGISTRY = ResourceKey
            .createRegistryKey(MaxtechStorage.id("research"));

    public static final Codec<ResearchNode> CODEC = RecordCodecBuilder
            .create(instance -> instance.group(Codec.STRING.fieldOf("branch").forGetter(ResearchNode::branch),
                    Codec.INT.optionalFieldOf("order", 0).forGetter(ResearchNode::order),
                    Codec.intRange(0, MAX_TIER).optionalFieldOf("tier", 0).forGetter(ResearchNode::tier),
                    Identifier.CODEC.optionalFieldOf("icon").forGetter(ResearchNode::icon),
                    Identifier.CODEC.sizeLimitedListOf(MAX_ENTRIES).optionalFieldOf("requires", List.of())
                            .forGetter(ResearchNode::requires),
                    ResearchOption.CODEC.sizeLimitedListOf(MAX_ENTRIES).optionalFieldOf("any_of", List.of())
                            .forGetter(ResearchNode::anyOf),
                    Identifier.CODEC.sizeLimitedListOf(MAX_ENTRIES * 4).optionalFieldOf("unlocks", List.of())
                            .forGetter(ResearchNode::unlocks))
                    .apply(instance, ResearchNode::new));

    public ResearchNode {
        requires = List.copyOf(requires);
        anyOf = List.copyOf(anyOf);
        unlocks = List.copyOf(unlocks);
    }

    /** The lang key of the name of node {@code id}. */
    public static String nameKey(Identifier id) {
        return "research." + id.getNamespace() + "." + id.getPath();
    }

    /** The lang key of the name of tier {@code tier} of the ladder. */
    public static String tierKey(int tier) {
        return "research." + MaxtechStorage.MOD_ID + ".tier." + tier;
    }

    /** The lang key of the material tier {@code tier} brings. */
    public static String tierMaterialKey(int tier) {
        return tierKey(tier) + ".material";
    }

    /** The lang key of the name of unlock id {@code unlock}. */
    public static String unlockKey(Identifier unlock) {
        return "research." + unlock.getNamespace() + ".unlock." + unlock.getPath();
    }

    /** The lang key of the name of branch {@code branch}. */
    public static String branchKey(String branch) {
        return "research." + MaxtechStorage.MOD_ID + ".branch." + branch;
    }
}
