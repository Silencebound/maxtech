package dev.silencebound.maxtech.lib.conduit;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.conduit.ConduitContents;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.slf4j.Logger;

/**
 * How a conduit's {@link ConduitContents} are saved and synced: under their own key, and only when not empty, so
 * conduits that never set any (all of Storage's cables) save and sync exactly as before. The fluid is written by
 * registry name, since numeric registry ids are not stable between sessions; each field is left out at its default.
 */
final class ConduitContentsTag {
    static final String KEY = "contents";
    static final String FLUID = "fluid";
    static final String FILL = "fill";
    static final String UPSTREAM = "upstream";
    static final String LANE = "lane";
    private static final Logger LOGGER = LogUtils.getLogger();

    private ConduitContentsTag() {
    }

    /** Saves {@code contents}, or nothing when they are empty. */
    static void save(ValueOutput output, ConduitContents contents) {
        if (!contents.isEmpty()) {
            write(output, Saved.of(contents));
        }
    }

    /** The saved contents, or {@link ConduitContents#EMPTY} for data without them; see {@link Saved#resolve}. */
    static ConduitContents load(ValueInput input) {
        Optional<Saved> saved = read(input);
        return saved.isEmpty() ? ConduitContents.EMPTY : saved.get().resolve(BuiltInRegistries.FLUID::getOptional);
    }

    static void write(ValueOutput output, Saved saved) {
        ValueOutput child = output.child(KEY);
        if (saved.fluid().isPresent()) {
            child.putString(FLUID, saved.fluid().get().toString());
        }
        if (saved.fill() != 0) {
            child.putInt(FILL, saved.fill());
        }
        if (saved.upstream().isPresent()) {
            child.store(UPSTREAM, Direction.CODEC, saved.upstream().get());
        }
        if (saved.lane()) {
            child.putBoolean(LANE, true);
        }
    }

    /** The saved form, or empty without the key. A fluid name that is not a valid id reads as an unknown one. */
    static Optional<Saved> read(ValueInput input) {
        Optional<ValueInput> found = input.child(KEY);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ValueInput child = found.get();
        Optional<String> name = child.getString(FLUID);
        return Optional.of(new Saved(name, child.getIntOr(FILL, 0), child.read(UPSTREAM, Direction.CODEC),
                child.getBooleanOr(LANE, false)));
    }

    /**
     * Contents as saved, with the fluid as its registry name.
     *
     * @param fluid
     *            the fluid's registry name as written, or empty for none
     */
    record Saved(Optional<String> fluid, int fill, Optional<Direction> upstream, boolean lane) {
        static Saved of(ConduitContents contents) {
            Optional<String> name = contents.fluid().isPresent()
                    ? Optional.of(BuiltInRegistries.FLUID.getKey(contents.fluid().get()).toString())
                    : Optional.empty();
            return new Saved(name, contents.fill(), contents.upstream(), contents.lane());
        }

        /**
         * The contents, with the fluid looked up by name. A name that is no longer registered (its mod was removed)
         * loads as empty contents, logged: a fill or flow without its fluid means nothing, and the addon sets the
         * contents again when its network next recomputes them. A fill out of range is clamped; without a fluid it is
         * 0.
         */
        ConduitContents resolve(Function<Identifier, Optional<Fluid>> fluids) {
            Optional<Fluid> found = Optional.empty();
            if (fluid.isPresent()) {
                Identifier id = Identifier.tryParse(fluid.get());
                found = id == null ? Optional.empty() : fluids.apply(id);
                if (found.isEmpty() || found.get().defaultFluidState().isEmpty()) {
                    LOGGER.warn("Conduit contents name an unknown fluid {}; loading them as empty", fluid.get());
                    return ConduitContents.EMPTY;
                }
            }
            int clamped = found.isEmpty() ? 0 : Math.clamp(fill, 0, ConduitContents.FULL);
            return new ConduitContents(found, clamped, upstream, lane);
        }
    }
}
