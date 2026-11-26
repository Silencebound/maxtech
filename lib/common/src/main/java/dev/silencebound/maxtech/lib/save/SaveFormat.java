package dev.silencebound.maxtech.lib.save;

import com.google.common.collect.MapMaker;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.slf4j.Logger;

/**
 * The version of one kind of saved data and the steps that bring older saves up to it (PRD §8.2 save compatibility).
 *
 * <p>
 * Saved data carries its version under {@link #VERSION_KEY}. Data saved without one (everything written before 1.0) is
 * version {@link #FIRST_VERSION}. Each {@link MigrationStep} added with {@link Builder#step} raises the current version
 * by one, so changing a saved format means adding one step where the format is built, plus a test.
 *
 * <p>
 * Loading never loses data: data already in the current version is used as it is; a step that throws and data from a
 * newer version than this build knows (a downgraded mod) are logged and loaded unchanged, never replaced by empty data.
 *
 * <p>
 * A stamp never goes down: whatever was loaded from a newer version is saved again with that newer version, so a later
 * upgrade never runs a step on data that was already migrated. Block entities and cable parts are tracked through
 * {@link #noteLoaded} and {@link #write(ValueOutput, Object)}, codec values by identity. Data an older build re-saves
 * may therefore lack fields of the newer format; steps must not assume more than that the data is no older than its
 * stamp.
 *
 * <p>
 * Thread-safe; steps must be pure.
 */
public final class SaveFormat {
    /** The key the version is saved under, next to the data's own fields. Data must not use it for anything else. */
    public static final String VERSION_KEY = "data_version";
    /** The version of data saved without a version key: the format at 1.0. */
    public static final int FIRST_VERSION = 1;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final MapCodec<CompoundTag> WHOLE = MapCodec.assumeMapUnsafe(CompoundTag.CODEC);

    private final String name;
    /** {@code steps.get(i)} migrates version {@code FIRST_VERSION + i} to the next. */
    private final List<MigrationStep> steps;
    /** Owners and values loaded from a newer version, with that version; weak and by identity. */
    private final ConcurrentMap<Object, Integer> newer = new MapMaker().weakKeys().makeMap();

    private SaveFormat(String name, List<MigrationStep> steps) {
        this.name = name;
        this.steps = List.copyOf(steps);
    }

    /** Starts a format named {@code name} (for logs) at {@link #FIRST_VERSION}. */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** This format's name, as given to {@link #builder}. */
    public String name() {
        return name;
    }

    /** The version this build writes: {@link #FIRST_VERSION} plus the number of steps. */
    public int currentVersion() {
        return FIRST_VERSION + steps.size();
    }

    /** The version {@code tag} was saved in; {@link #FIRST_VERSION} when it has none or an invalid one. */
    public static int versionOf(CompoundTag tag) {
        return Math.max(FIRST_VERSION, tag.getIntOr(VERSION_KEY, FIRST_VERSION));
    }

    /** The version {@code input} was saved in; {@link #FIRST_VERSION} when it has none or an invalid one. */
    public static int versionOf(ValueInput input) {
        return Math.max(FIRST_VERSION, input.getIntOr(VERSION_KEY, FIRST_VERSION));
    }

    /** Writes the current version into {@code output}, next to the data's own fields. */
    public void write(ValueOutput output) {
        output.putInt(VERSION_KEY, currentVersion());
    }

    /**
     * Writes the version for {@code owner}'s data: the current one, or the newer one its data was last loaded from
     * ({@link #noteLoaded}), so the stamp never goes down.
     */
    public void write(ValueOutput output, Object owner) {
        output.putInt(VERSION_KEY, stampFor(owner));
    }

    /**
     * Remembers the version {@code owner} is loading its data from, so {@link #write(ValueOutput, Object)} keeps a
     * newer stamp. Call it on every load; data in this build's version or older clears what was remembered.
     */
    public void noteLoaded(Object owner, ValueInput input) {
        noteVersion(owner, versionOf(input));
    }

    private void noteVersion(Object owner, int version) {
        if (version > currentVersion()) {
            newer.put(owner, version);
        } else if (!newer.isEmpty()) {
            newer.remove(owner);
        }
    }

    private int stampFor(Object owner) {
        if (newer.isEmpty()) {
            return currentVersion();
        }
        Integer version = newer.get(owner);
        return version != null ? version : currentVersion();
    }

    /** Whether {@code input} is already in the current version, so it needs no migration. */
    public boolean isCurrent(ValueInput input) {
        return versionOf(input) == currentVersion();
    }

    /**
     * Brings {@code tag} to the current version. Never changes {@code tag}; a migrated result is a new tag with the
     * current version set.
     *
     * @param kind
     *            what the tag belongs to, passed to the steps and named in logs
     */
    public Migration migrate(Identifier kind, CompoundTag tag) {
        int from = versionOf(tag);
        int current = currentVersion();
        if (from == current) {
            return new Migration(tag, from, Outcome.CURRENT);
        }
        if (from > current) {
            LOGGER.warn("{} data for {} is version {}, newer than this build's {}; loading it unchanged", name, kind,
                    from, current);
            return new Migration(tag, from, Outcome.FUTURE);
        }
        CompoundTag migrated = tag.copy();
        int version = from;
        try {
            for (; version < current; version++) {
                migrated = steps.get(version - FIRST_VERSION).apply(kind, migrated);
            }
        } catch (RuntimeException e) {
            LOGGER.error("{} data for {} failed to migrate from version {} to {}; loading it unchanged", name, kind,
                    version, version + 1, e);
            return new Migration(tag, from, Outcome.FAILED);
        }
        migrated.putInt(VERSION_KEY, current);
        return new Migration(migrated, from, Outcome.MIGRATED);
    }

    /**
     * Brings a block entity's or other value-based save to the current version. Returns {@code input} itself when it is
     * current, could not be migrated or is from a newer version; otherwise a new input over the migrated data.
     */
    public ValueInput migrate(Identifier kind, ValueInput input) {
        if (isCurrent(input)) {
            return input;
        }
        Optional<CompoundTag> whole = input.read(WHOLE);
        if (whole.isEmpty()) {
            return input;
        }
        Migration migration = migrate(kind, whole.get());
        if (migration.outcome() != Outcome.MIGRATED) {
            return input;
        }
        return TagValueInput.create(ProblemReporter.DISCARDING, input.lookup(), migration.tag());
    }

    /**
     * Wraps a codec so what it saves carries this format's version and older saves are migrated before it decodes them.
     * Only map-shaped values get a version; others pass through unversioned, so wrap a codec that encodes to a map (a
     * record, a saved data object). The version key is removed before the inner codec sees the data. A value decoded
     * from a newer version is encoded with that version again (tracked by identity, so an equal value made afresh gets
     * the current version).
     *
     * @param kind
     *            what the data is (a saved data or data component id), passed to the steps
     */
    public <A> Codec<A> codec(Identifier kind, Codec<A> codec) {
        return new Codec<>() {
            @Override
            public <T> DataResult<T> encode(A value, DynamicOps<T> ops, T prefix) {
                return codec.encode(value, ops, prefix)
                        .map(encoded -> ops
                                .mergeToMap(encoded, ops.createString(VERSION_KEY), ops.createInt(stampFor(value)))
                                .result().orElse(encoded));
            }

            @Override
            public <T> DataResult<Pair<A, T>> decode(DynamicOps<T> ops, T input) {
                Optional<Number> saved = ops.get(input, VERSION_KEY).flatMap(ops::getNumberValue).result();
                T body = input;
                int version = saved.isPresent() ? Math.max(FIRST_VERSION, saved.get().intValue()) : FIRST_VERSION;
                if (version < currentVersion() && ops.convertTo(NbtOps.INSTANCE, input) instanceof CompoundTag tag) {
                    Migration migration = migrate(kind, tag);
                    if (migration.outcome() == Outcome.MIGRATED) {
                        body = NbtOps.INSTANCE.convertTo(ops, migration.tag());
                        saved = Optional.of(currentVersion());
                    }
                } else if (version > currentVersion()) {
                    LOGGER.warn("{} data for {} is version {}, newer than this build's {}; loading it unchanged", name,
                            kind, version, currentVersion());
                }
                if (saved.isPresent()) {
                    body = ops.remove(body, VERSION_KEY);
                }
                int loaded = version;
                return codec.decode(ops, body).map(decoded -> {
                    noteVersion(decoded.getFirst(), loaded);
                    return Pair.of(decoded.getFirst(), input);
                });
            }
        };
    }

    @Override
    public String toString() {
        return "SaveFormat[" + name + " v" + currentVersion() + "]";
    }

    /** What {@link #migrate(Identifier, CompoundTag)} did. */
    public enum Outcome {
        /** Already in the current version; the tag is the one given. */
        CURRENT,
        /** Brought up from an older version; the tag is a new one. */
        MIGRATED,
        /** From a newer version than this build knows; the tag is the one given, unchanged. */
        FUTURE,
        /** A step threw; the tag is the one given, unchanged. */
        FAILED
    }

    /**
     * The result of a migration.
     *
     * @param tag
     *            the data to load: the given tag unless {@code outcome} is {@link Outcome#MIGRATED}
     * @param fromVersion
     *            the version the data was saved in
     * @param outcome
     *            what happened
     */
    public record Migration(CompoundTag tag, int fromVersion, Outcome outcome) {
    }

    /** Collects a format's steps in version order. */
    public static final class Builder {
        private final String name;
        private final List<MigrationStep> steps = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        /**
         * Adds the step from the current last version to the next; the first step migrates {@link #FIRST_VERSION}.
         * Never remove or reorder steps once released.
         */
        public Builder step(MigrationStep step) {
            steps.add(step);
            return this;
        }

        /** The format, whose current version is {@link #FIRST_VERSION} plus the steps added. */
        public SaveFormat build() {
            return new SaveFormat(name, steps);
        }
    }
}
