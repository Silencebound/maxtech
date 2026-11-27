package dev.silencebound.maxtech.lib.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.conduit.ConduitContents;
import dev.silencebound.maxtech.lib.conduit.ConduitContentsTag.Saved;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.junit.jupiter.api.Test;

/**
 * Contents in a conduit's saved data and update tag: the fluid by registry name, each field only when set, the key only
 * when not empty. Fluids can't load in JUnit, so this covers the saved form and the unknown-name path; loading a known
 * fluid runs in the {@code conduit_api_contents_saved} GameTest.
 */
class ConduitContentsTagTest {
    @Test
    void savedFormRoundTripsByName() {
        Saved water = new Saved(Optional.of("minecraft:water"), 2, Optional.of(Direction.NORTH), true);
        CompoundTag tag = write(water);
        CompoundTag saved = tag.getCompoundOrEmpty("contents");
        assertEquals("minecraft:water", saved.getStringOr("fluid", ""));
        assertEquals(2, saved.getIntOr("fill", 0));
        assertEquals("north", saved.getStringOr("upstream", ""));
        assertTrue(saved.getBooleanOr("lane", false));
        assertEquals(Optional.of(water), read(tag));
        Saved lava = new Saved(Optional.of("minecraft:lava"), 0, Optional.empty(), false);
        assertEquals(1, write(lava).getCompoundOrEmpty("contents").size(), "defaults left out");
        assertEquals(Optional.of(lava), read(write(lava)));
    }

    @Test
    void emptyWritesNoKey() {
        TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        ConduitContentsTag.save(output, ConduitContents.EMPTY);
        CompoundTag tag = output.buildResult();
        assertFalse(tag.contains("contents"));
        assertEquals(0, tag.size());
        assertEquals(Optional.empty(), read(new CompoundTag()));
        assertEquals(ConduitContents.EMPTY, load(new CompoundTag()));
    }

    @Test
    void contentsWithoutAFluidRoundTrip() {
        ConduitContents flow = new ConduitContents(Optional.empty(), 0, Optional.of(Direction.DOWN), true);
        TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        ConduitContentsTag.save(output, flow);
        CompoundTag tag = output.buildResult();
        assertFalse(tag.getCompoundOrEmpty("contents").contains("fluid"));
        assertEquals(flow, read(tag).orElseThrow().resolve(id -> Optional.empty()));
    }

    @Test
    void unknownFluidLoadsAsEmpty() {
        List<Identifier> asked = new ArrayList<>();
        for (String name : new String[]{"removed_mod:goo", "Not An Id!"}) {
            Saved saved = new Saved(Optional.of(name), 4, Optional.of(Direction.UP), true);
            assertEquals(ConduitContents.EMPTY, saved.resolve(id -> {
                asked.add(id);
                return Optional.empty();
            }), name);
        }
        assertEquals(List.of(Identifier.fromNamespaceAndPath("removed_mod", "goo")), asked,
                "an invalid name is not looked up");
    }

    @Test
    void fillWithoutAFluidLoadsAsZero() {
        Saved saved = new Saved(Optional.empty(), 3, Optional.empty(), true);
        assertEquals(new ConduitContents(Optional.empty(), 0, Optional.empty(), true),
                saved.resolve(id -> Optional.empty()));
    }

    private static CompoundTag write(Saved saved) {
        TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        ConduitContentsTag.write(output, saved);
        return output.buildResult();
    }

    private static TagValueInput input(CompoundTag tag) {
        HolderLookup.Provider lookup = HolderLookup.Provider.create(Stream.empty());
        return (TagValueInput) TagValueInput.create(ProblemReporter.DISCARDING, lookup, tag);
    }

    private static Optional<Saved> read(CompoundTag tag) {
        return ConduitContentsTag.read(input(tag));
    }

    private static ConduitContents load(CompoundTag tag) {
        return ConduitContentsTag.load(input(tag));
    }
}
