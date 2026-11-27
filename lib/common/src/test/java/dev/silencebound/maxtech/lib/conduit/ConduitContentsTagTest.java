package dev.silencebound.maxtech.lib.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.silencebound.maxtech.lib.api.conduit.ConduitContents;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.junit.jupiter.api.Test;

/**
 * Contents in a conduit's saved data and update tag: each field only when set, the key only when not empty, and saves
 * naming a fluid or kind that is gone loading empty with one warning. Fluids and kinds can't load in JUnit; saving and
 * loading them runs in the {@code conduit_api_contents_*} and {@code save_compat_contents_fluid_name} GameTests.
 */
class ConduitContentsTagTest {
    private final List<String> warnings = new ArrayList<>();
    private final List<Identifier> asked = new ArrayList<>();

    @Test
    void emptyWritesNoKey() {
        CompoundTag tag = save(ConduitContents.EMPTY);
        assertFalse(tag.contains("contents"));
        assertEquals(0, tag.size());
        assertEquals(ConduitContents.EMPTY, load(new CompoundTag()));
        assertEquals(List.of(), warnings);
    }

    @Test
    void contentsWithoutAResourceRoundTrip() {
        ConduitContents flow = new ConduitContents(Optional.empty(), 0, Optional.of(Direction.DOWN), true);
        CompoundTag saved = save(flow).getCompoundOrEmpty("contents");
        assertEquals("down", saved.getStringOr("upstream", ""));
        assertEquals(2, saved.size(), "no resource, kind or fill written");
        assertEquals(flow, load(save(flow)));
        assertEquals(List.of(), warnings);
    }

    @Test
    void unknownFluidLoadsEmptyWithOneWarning() {
        for (String name : new String[]{"removed_mod:goo", "Not An Id!"}) {
            warnings.clear();
            assertEquals(ConduitContents.EMPTY, load(contents("fluid", name)), name);
            assertEquals(1, warnings.size(), name);
        }
        assertEquals(List.of(Identifier.fromNamespaceAndPath("removed_mod", "goo")), asked,
                "an invalid name is not looked up");
    }

    @Test
    void contentsUnknownKindLoadsEmptyWithOneWarning() {
        for (String name : new String[]{"removed_mod:chemical", "Not An Id!"}) {
            warnings.clear();
            CompoundTag tag = contents("kind", name);
            CompoundTag inner = tag.getCompoundOrEmpty("contents");
            inner.put("resource", IntTag.valueOf(7));
            // A fluid name next to a kind is not read: the kind decides.
            inner.putString("fluid", "minecraft:water");
            assertEquals(ConduitContents.EMPTY, load(tag), name);
            assertEquals(1, warnings.size(), name + ": " + warnings);
        }
        assertEquals(List.of(Identifier.fromNamespaceAndPath("removed_mod", "chemical")), asked,
                "an invalid kind is not looked up, and no fluid either");
    }

    @Test
    void fillWithoutAResourceLoadsAsZero() {
        CompoundTag tag = contents("fill", "");
        tag.getCompoundOrEmpty("contents").putInt("fill", 3);
        tag.getCompoundOrEmpty("contents").putBoolean("lane", true);
        assertEquals(new ConduitContents(Optional.empty(), 0, Optional.empty(), true), load(tag));
    }

    private static CompoundTag contents(String key, String value) {
        CompoundTag contents = new CompoundTag();
        contents.putString(key, value);
        CompoundTag tag = new CompoundTag();
        tag.put("contents", contents);
        return tag;
    }

    private static CompoundTag save(ConduitContents contents) {
        TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        ConduitContentsTag.save(output, contents);
        return output.buildResult();
    }

    private ConduitContents load(CompoundTag tag) {
        HolderLookup.Provider lookup = HolderLookup.Provider.create(Stream.empty());
        return ConduitContentsTag.load(TagValueInput.create(ProblemReporter.DISCARDING, lookup, tag), id -> {
            asked.add(id);
            return Optional.empty();
        }, id -> {
            asked.add(id);
            return Optional.empty();
        }, warnings::add);
    }
}
