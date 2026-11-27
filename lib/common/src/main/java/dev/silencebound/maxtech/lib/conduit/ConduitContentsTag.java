package dev.silencebound.maxtech.lib.conduit;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import dev.silencebound.maxtech.lib.api.conduit.ConduitContents;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.KindKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
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
 * conduits that never set any (all of Storage's cables) save and sync exactly as before. A fluid is written as
 * {@code fluid: <registry name>}, as before kinds existed, since numeric registry ids are not stable between sessions;
 * a resource of another kind as {@code kind: <kind id>} and {@code resource: <the key in its kind's codec>}, with no
 * second {@code kind} inside {@code resource}. Each field is left out at its default.
 */
final class ConduitContentsTag {
    static final String KEY = "contents";
    static final String FLUID = "fluid";
    static final String KIND = KindKey.KIND_FIELD;
    static final String RESOURCE = "resource";
    static final String FILL = "fill";
    static final String UPSTREAM = "upstream";
    static final String LANE = "lane";
    private static final Logger LOGGER = LogUtils.getLogger();
    // Warnings already given this session, by problem and id: a stale tag would otherwise warn on every chunk or update
    // load.
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private ConduitContentsTag() {
    }

    /** Saves {@code contents}, or nothing when they are empty. */
    static void save(ValueOutput output, ConduitContents contents) {
        if (contents.isEmpty()) {
            return;
        }
        ValueOutput child = output.child(KEY);
        if (contents.resource().isPresent()) {
            KindKey<?> resource = contents.resource().get();
            Optional<FluidKey> fluid = resource.as(ResourceKinds.FLUID);
            if (fluid.isPresent()) {
                child.putString(FLUID, BuiltInRegistries.FLUID.getKey(fluid.get().fluid()).toString());
            } else {
                child.putString(KIND, resource.kindId().toString());
                storeKey(child, resource);
            }
        }
        if (contents.fill() != 0) {
            child.putInt(FILL, contents.fill());
        }
        if (contents.upstream().isPresent()) {
            child.store(UPSTREAM, Direction.CODEC, contents.upstream().get());
        }
        if (contents.lane()) {
            child.putBoolean(LANE, true);
        }
    }

    /** The saved contents, or {@link ConduitContents#EMPTY} for data without them; see the four-argument overload. */
    static ConduitContents load(ValueInput input) {
        return load(input, BuiltInRegistries.FLUID::getOptional, ResourceKinds::byId, LOGGER::warn);
    }

    /**
     * The saved contents, with fluids and kinds looked up by name. Data with a kind field reads that kind's resource
     * field; data without one reads the fluid field as a fluid. A fluid or kind that is no longer registered (its mod
     * was removed), or a resource its kind cannot read, loads as empty contents, with one warning per kind or fluid id
     * per session: conduit contents are only a display, and the addon sets them again when its network next recomputes
     * them. A fill out of range is clamped; without a resource it is 0.
     */
    static ConduitContents load(ValueInput input, Function<Identifier, Optional<Fluid>> fluids,
            Function<Identifier, Optional<ResourceKind<?>>> kinds, Consumer<String> warn) {
        Optional<ValueInput> found = input.child(KEY);
        if (found.isEmpty()) {
            return ConduitContents.EMPTY;
        }
        ValueInput child = found.get();
        Optional<KindKey<?>> resource = Optional.empty();
        Optional<String> kindName = child.getString(KIND);
        Optional<String> fluidName = child.getString(FLUID);
        if (kindName.isPresent()) {
            Identifier id = Identifier.tryParse(kindName.get());
            Optional<ResourceKind<?>> kind = id == null ? Optional.empty() : kinds.apply(id);
            if (kind.isEmpty()) {
                if (WARNED.add("kind " + kindName.get())) {
                    warn.accept("Conduit contents name an unknown resource kind " + kindName.get()
                            + "; loading them as empty");
                }
                return ConduitContents.EMPTY;
            }
            DataResult<? extends KindKey<?>> read = readKey(child, kind.get());
            if (read.isError()) {
                if (WARNED.add("key " + kindName.get())) {
                    warn.accept("Conduit contents hold a " + kindName.get() + " resource that does not load ("
                            + read.error().orElseThrow().message() + "); loading them as empty");
                }
                return ConduitContents.EMPTY;
            }
            resource = Optional.of(read.getOrThrow());
        } else if (fluidName.isPresent()) {
            Identifier id = Identifier.tryParse(fluidName.get());
            Optional<Fluid> fluid = id == null ? Optional.empty() : fluids.apply(id);
            if (fluid.isEmpty() || fluid.get().defaultFluidState().isEmpty()) {
                if (WARNED.add("fluid " + fluidName.get())) {
                    warn.accept(
                            "Conduit contents name an unknown fluid " + fluidName.get() + "; loading them as empty");
                }
                return ConduitContents.EMPTY;
            }
            resource = Optional.of(new KindKey<>(ResourceKinds.FLUID, FluidKey.of(fluid.get())));
        }
        int fill = resource.isEmpty() ? 0 : Math.clamp(child.getIntOr(FILL, 0), 0, ConduitContents.FULL);
        return new ConduitContents(resource, fill, child.read(UPSTREAM, Direction.CODEC),
                child.getBooleanOr(LANE, false));
    }

    private static <K> void storeKey(ValueOutput child, KindKey<K> resource) {
        child.store(RESOURCE, resource.kind().codec(), resource.key());
    }

    /**
     * Reads {@code resource} as raw data first, so a resource its kind cannot read reports through the caller's one
     * warning rather than the input's problem reporter.
     */
    private static <K> DataResult<KindKey<K>> readKey(ValueInput child, ResourceKind<K> kind) {
        Optional<Dynamic<?>> raw = child.read(RESOURCE, Codec.PASSTHROUGH);
        if (raw.isEmpty()) {
            return DataResult.error(() -> "no " + RESOURCE);
        }
        return kind.codec().parse(raw.get()).map(key -> new KindKey<>(kind, key));
    }
}
