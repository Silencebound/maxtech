package dev.silencebound.maxtech.lib.save;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import org.junit.jupiter.api.Test;

/** The migration chain: order, versions, newer data and failing steps, through tags, values and codecs. */
class SaveFormatTest {
    private static final Identifier KIND = Identifier.fromNamespaceAndPath("test", "thing");

    /** A step that appends {@code letter} to the {@code trail} string, so the order the steps ran in is visible. */
    private static MigrationStep append(String letter) {
        return (kind, tag) -> {
            tag.putString("trail", tag.getStringOr("trail", "") + letter);
            return tag;
        };
    }

    private static SaveFormat threeSteps() {
        return SaveFormat.builder("test").step(append("a")).step(append("b")).step(append("c")).build();
    }

    private static CompoundTag tag(String trail) {
        CompoundTag tag = new CompoundTag();
        tag.putString("trail", trail);
        tag.putInt("kept", 7);
        return tag;
    }

    @Test
    void versionlessDataIsTheFirstVersionAndCurrentWithoutSteps() {
        SaveFormat format = SaveFormat.builder("test").build();
        CompoundTag tag = tag("");
        SaveFormat.Migration migration = format.migrate(KIND, tag);
        assertEquals(1, format.currentVersion());
        assertEquals(SaveFormat.Outcome.CURRENT, migration.outcome());
        assertEquals(1, migration.fromVersion());
        assertSame(tag, migration.tag());
    }

    @Test
    void stepsRunInOrderFromTheSavedVersion() {
        SaveFormat format = threeSteps();
        CompoundTag old = tag("");
        SaveFormat.Migration all = format.migrate(KIND, old);
        assertEquals(SaveFormat.Outcome.MIGRATED, all.outcome());
        assertEquals("abc", all.tag().getStringOr("trail", "?"));
        assertEquals(4, all.tag().getIntOr(SaveFormat.VERSION_KEY, 0));
        assertEquals(7, all.tag().getIntOr("kept", 0));
        // The given tag is never changed.
        assertEquals("", old.getStringOr("trail", "?"));
        assertFalse(old.contains(SaveFormat.VERSION_KEY));

        CompoundTag second = tag("a");
        second.putInt(SaveFormat.VERSION_KEY, 2);
        assertEquals("abc", format.migrate(KIND, second).tag().getStringOr("trail", "?"));
    }

    @Test
    void newerDataIsKeptUnchanged() {
        CompoundTag future = tag("from the future");
        future.putInt(SaveFormat.VERSION_KEY, 9);
        SaveFormat.Migration migration = threeSteps().migrate(KIND, future);
        assertEquals(SaveFormat.Outcome.FUTURE, migration.outcome());
        assertSame(future, migration.tag());
        assertEquals("from the future", future.getStringOr("trail", "?"));
        assertEquals(9, future.getIntOr(SaveFormat.VERSION_KEY, 0));
    }

    @Test
    void aFailingStepKeepsTheDataUnchanged() {
        SaveFormat format = SaveFormat.builder("test").step(append("a")).step((kind, tag) -> {
            throw new IllegalStateException("broken step");
        }).build();
        CompoundTag old = tag("");
        SaveFormat.Migration migration = format.migrate(KIND, old);
        assertEquals(SaveFormat.Outcome.FAILED, migration.outcome());
        assertSame(old, migration.tag());
        assertEquals("", old.getStringOr("trail", "?"));
    }

    @Test
    void valueInputsAreMigratedOrPassedThrough() {
        HolderLookup.Provider lookup = HolderLookup.Provider.create(Stream.empty());
        SaveFormat format = threeSteps();

        CompoundTag current = tag("abc");
        current.putInt(SaveFormat.VERSION_KEY, 4);
        ValueInput currentInput = TagValueInput.create(ProblemReporter.DISCARDING, lookup, current);
        assertSame(currentInput, format.migrate(KIND, currentInput));

        ValueInput oldInput = TagValueInput.create(ProblemReporter.DISCARDING, lookup, tag(""));
        ValueInput migrated = format.migrate(KIND, oldInput);
        assertEquals("abc", migrated.getStringOr("trail", "?"));
        assertEquals(7, migrated.getIntOr("kept", 0));

        CompoundTag future = tag("x");
        future.putInt(SaveFormat.VERSION_KEY, 9);
        ValueInput futureInput = TagValueInput.create(ProblemReporter.DISCARDING, lookup, future);
        assertSame(futureInput, format.migrate(KIND, futureInput));
    }

    /** A saved record whose field was renamed from {@code old} to {@code name} in version 2. */
    private record Named(String name) {
        static final Codec<Named> CODEC = RecordCodecBuilder
                .create(i -> i.group(Codec.STRING.fieldOf("name").forGetter(Named::name)).apply(i, Named::new));
    }

    private static final SaveFormat RENAMED = SaveFormat.builder("test").step((kind, tag) -> {
        tag.getString("old").ifPresent(old -> tag.putString("name", old));
        tag.remove("old");
        return tag;
    }).build();

    @Test
    void codecsStampTheVersionAndMigrateOlderSaves() {
        Codec<Named> codec = RENAMED.codec(KIND, Named.CODEC);
        Tag saved = codec.encodeStart(NbtOps.INSTANCE, new Named("x")).getOrThrow();
        assertEquals(2, ((CompoundTag) saved).getIntOr(SaveFormat.VERSION_KEY, 0));
        assertEquals(new Named("x"), codec.parse(NbtOps.INSTANCE, saved).getOrThrow());

        CompoundTag old = new CompoundTag();
        old.putString("old", "from 0.4");
        assertEquals(new Named("from 0.4"), codec.parse(NbtOps.INSTANCE, old).getOrThrow());

        JsonObject json = new JsonObject();
        json.addProperty("old", "json");
        assertEquals(new Named("json"), codec.parse(JsonOps.INSTANCE, json).getOrThrow());
        JsonElement encoded = codec.encodeStart(JsonOps.INSTANCE, new Named("y")).getOrThrow();
        assertEquals(2, encoded.getAsJsonObject().get(SaveFormat.VERSION_KEY).getAsInt());
    }

    @Test
    void codecsLoadNewerSavesAsTheyAre() {
        Codec<Named> codec = RENAMED.codec(KIND, Named.CODEC);
        CompoundTag future = new CompoundTag();
        future.putString("name", "kept");
        future.putInt(SaveFormat.VERSION_KEY, 5);
        assertEquals(new Named("kept"), codec.parse(NbtOps.INSTANCE, future).getOrThrow());
    }

    @Test
    void codecsKeepANewerStampForTheValueTheyLoaded() {
        Codec<Named> codec = RENAMED.codec(KIND, Named.CODEC);
        CompoundTag future = new CompoundTag();
        future.putString("name", "kept");
        future.putInt(SaveFormat.VERSION_KEY, 5);
        Named loaded = codec.parse(NbtOps.INSTANCE, future).getOrThrow();
        Tag again = codec.encodeStart(NbtOps.INSTANCE, loaded).getOrThrow();
        assertEquals(5, ((CompoundTag) again).getIntOr(SaveFormat.VERSION_KEY, 0));
        // An equal value made by this build is in this build's format.
        Tag fresh = codec.encodeStart(NbtOps.INSTANCE, new Named("kept")).getOrThrow();
        assertEquals(2, ((CompoundTag) fresh).getIntOr(SaveFormat.VERSION_KEY, 0));
    }

    @Test
    void ownersKeepANewerStampUntilTheyLoadCurrentData() {
        HolderLookup.Provider lookup = HolderLookup.Provider.create(Stream.empty());
        SaveFormat format = threeSteps();
        Object owner = new Object();
        CompoundTag future = tag("x");
        future.putInt(SaveFormat.VERSION_KEY, 9);
        format.noteLoaded(owner, TagValueInput.create(ProblemReporter.DISCARDING, lookup, future));
        assertEquals(9, stamp(format, owner));
        assertEquals(4, stamp(format, new Object()));

        format.noteLoaded(owner, TagValueInput.create(ProblemReporter.DISCARDING, lookup, tag("")));
        assertEquals(4, stamp(format, owner));
    }

    private static int stamp(SaveFormat format, Object owner) {
        TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        format.write(output, owner);
        return output.buildResult().getIntOr(SaveFormat.VERSION_KEY, 0);
    }

    @Test
    void codecsHideTheVersionFromTheInnerCodec() {
        Codec<Map<String, Integer>> codec = RENAMED.codec(KIND, Codec.unboundedMap(Codec.STRING, Codec.INT));
        Tag saved = codec.encodeStart(NbtOps.INSTANCE, Map.of("a", 1)).getOrThrow();
        assertEquals(Map.of("a", 1), codec.parse(NbtOps.INSTANCE, saved).getOrThrow());
    }

    @Test
    void nonMapValuesPassThroughUnversioned() {
        Codec<Integer> codec = RENAMED.codec(KIND, Codec.INT);
        Tag saved = codec.encodeStart(NbtOps.INSTANCE, 5).getOrThrow();
        assertEquals(5, codec.parse(NbtOps.INSTANCE, saved).getOrThrow());
    }
}
