package dev.silencebound.maxtech.storage.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

/**
 * Saved research with the controller cluster's old node ids (before M7R item 8) loads with the renamed size nodes
 * completed, keeps the old ids and everything else, and loading it again changes nothing.
 */
class ResearchRenamesTest {
    @Test
    void oldClusterNodesLoadAsSizeNodes() {
        ResearchTeam team = load(saved("cluster_expansion_1", "cluster_expansion_2", "port_tier_2"));
        assertTrue(team.completed(MaxtechStorage.id("controller_size_1")), "size step 1");
        assertTrue(team.completed(MaxtechStorage.id("controller_size_2")), "size step 2");
        assertFalse(team.completed(MaxtechStorage.id("controller_size_3")), "step 3 was not done");
        assertTrue(team.completed(MaxtechStorage.id("port_tier_2")), "other nodes stay");
        assertTrue(team.completed(MaxtechStorage.id("cluster_expansion_1")), "the old id stays for old data packs");
        assertEquals(42, team.points(), "points stay");
    }

    @Test
    void reloadingChangesNothing() {
        ResearchTeam first = load(saved("cluster_expansion_3"));
        Tag again = ResearchTeam.CODEC.encodeStart(NbtOps.INSTANCE, first).getOrThrow();
        ResearchTeam second = ResearchTeam.CODEC.parse(NbtOps.INSTANCE, again).getOrThrow();
        assertTrue(second.completed(MaxtechStorage.id("controller_size_3")));
        assertTrue(second.completed(MaxtechStorage.id("cluster_expansion_3")));
        assertEquals(2, ((CompoundTag) again).getListOrEmpty("completed").size(), "no id added twice");
        assertEquals(2, ((CompoundTag) ResearchTeam.CODEC.encodeStart(NbtOps.INSTANCE, second).getOrThrow())
                .getListOrEmpty("completed").size(), "the second load adds nothing");
    }

    @Test
    void onlyRenamedNodesMap() {
        assertEquals(MaxtechStorage.id("controller_size_2"),
                ResearchRenames.renamed(MaxtechStorage.id("cluster_expansion_2")));
        assertEquals(null, ResearchRenames.renamed(MaxtechStorage.id("controller_cards")));
    }

    @Test
    void efficientPowerWithoutCardSlotsBecomesAToken() {
        ResearchTeam team = load(saved("efficient_power", "energy_cells_2"));
        assertFalse(team.completed(MaxtechStorage.id("efficient_power")), "no card unlock without the slots");
        assertFalse(team.completed(MaxtechStorage.id("controller_cards")), "no slots the old tree did not give");
        assertTrue(team.completed(MaxtechStorage.id("energy_cells_2")), "other nodes stay");
        assertEquals(1, team.tokens(), "the node comes back as a skip token");
        Tag again = ResearchTeam.CODEC.encodeStart(NbtOps.INSTANCE, team).getOrThrow();
        assertEquals(1, ResearchTeam.CODEC.parse(NbtOps.INSTANCE, again).getOrThrow().tokens(), "once only");
    }

    @Test
    void efficientPowerWithCardSlotsStays() {
        ResearchTeam team = load(saved("efficient_power", "controller_cards"));
        assertTrue(team.completed(MaxtechStorage.id("efficient_power")));
        assertTrue(team.completed(MaxtechStorage.id("controller_cards")));
        assertEquals(0, team.tokens());
    }

    private static CompoundTag saved(String... completed) {
        CompoundTag tag = new CompoundTag();
        tag.store("anchor", net.minecraft.core.UUIDUtil.CODEC, new UUID(1, 2));
        ListTag nodes = new ListTag();
        for (String node : completed) {
            nodes.add(StringTag.valueOf(MaxtechStorage.id(node).toString()));
        }
        tag.put("completed", nodes);
        tag.putLong("points", 42);
        return tag;
    }

    private static ResearchTeam load(CompoundTag tag) {
        return ResearchTeam.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
    }
}
