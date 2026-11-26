package dev.silencebound.maxtech.storage.dev;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;

import dev.silencebound.maxtech.storage.bridge.BridgeBlock;
import dev.silencebound.maxtech.storage.bridge.BridgeContent;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;

/**
 * The foreign networks of the showcase's bridges station. Common code never names AE2 or Refined Storage classes, so
 * each mod's compat registers a {@link Builder} here when that mod is loaded; a mod without one gets a sign on the pad
 * that names the dev-run flag instead. Registration at mod construction, building on the server thread. Dev tooling
 * only.
 */
public final class ShowcaseBridges {
    private static final Map<Foreign, Builder> BUILDERS = new EnumMap<>(Foreign.class);

    private ShowcaseBridges() {
    }

    /**
     * The mods a bridge talks to, with the flag that adds each to the dev runs and the item its network is stocked
     * with.
     */
    public enum Foreign {
        AE2("AE2", "-PwithAe2=true"), REFINED_STORAGE("Refined Storage", "-PwithRs=true");

        private final String english;
        private final String flag;

        Foreign(String english, String flag) {
            this.english = english;
            this.flag = flag;
        }

        /** The mod's name, as signs and the step wall show it. */
        public String english() {
            return english;
        }

        /** The Gradle flag that adds the mod to the dev runs. */
        public String flag() {
            return flag;
        }

        /** What the builder stocks the foreign network with, so the Storage terminal shows it came through. */
        public ItemKey marker() {
            return ItemKey.of(this == AE2 ? Items.AMETHYST_SHARD : Items.PRISMARINE_SHARD);
        }
    }

    /** Builds one foreign network; implemented by the compat of its mod. */
    @FunctionalInterface
    public interface Builder {
        /**
         * Places a powered foreign network on {@code site} and returns the work that finishes it: stocking it with
         * {@link Foreign#marker()} once it is up, then {@link Site#placeBridge()}. The showcase runs the task after
         * each server tick until it returns true, and gives up after a while.
         */
        Task build(Site site);
    }

    /** Work a builder finishes over later ticks. */
    @FunctionalInterface
    public interface Task {
        /** One try; true once done. Server thread, after the server tick. */
        boolean tick();
    }

    /**
     * Where a foreign network goes: in front of the bridge spot, two blocks deep along {@link #facing()} and three wide
     * across it. Positions outside that area may belong to the Storage network.
     */
    public static final class Site {
        private final ServerLevel level;
        private final BlockPos bridge;
        private final Direction facing;
        private final Direction across;
        private final List<String> warnings;

        Site(ServerLevel level, BlockPos bridge, Direction facing, Direction across, List<String> warnings) {
            this.level = level;
            this.bridge = bridge.immutable();
            this.facing = facing;
            this.across = across;
            this.warnings = warnings;
        }

        public ServerLevel level() {
            return level;
        }

        /** From the bridge towards the foreign network. */
        public Direction facing() {
            return facing;
        }

        /**
         * A block of the area: {@code across} -1 to 1 sideways ({@link #across()} is +1), {@code depth} 0 (touching the
         * bridge's front at {@code across} 0) or 1.
         */
        public BlockPos pos(int across, int depth) {
            if (across < -1 || across > 1 || depth < 0 || depth > 1) {
                throw new IllegalArgumentException("outside the site: " + across + ", " + depth);
            }
            return bridge.relative(facing, depth + 1).relative(this.across, across);
        }

        /** The direction of +1 across. */
        public Direction across() {
            return across;
        }

        /** Places the Storage bridge facing the foreign network; it joins the Storage network behind it. */
        public void placeBridge() {
            level.setBlockAndUpdate(bridge,
                    BridgeContent.BRIDGE_TIER_3.get().defaultBlockState().setValue(BridgeBlock.FACING, facing));
        }

        /** Whether the bridge is placed. */
        public boolean bridgePlaced() {
            return level.getBlockState(bridge).is(BridgeContent.BRIDGE_TIER_3.get());
        }

        /** Reports a problem in the build's warnings (shown in chat). Only during {@link Builder#build}. */
        public void warn(String warning) {
            warnings.add(warning);
        }
    }

    /** Registers the builder for {@code foreign}; call once at mod construction with that mod loaded. */
    public static synchronized void register(Foreign foreign, Builder builder) {
        BUILDERS.put(foreign, builder);
    }

    /** The builder for {@code foreign}, empty while its mod is not loaded. */
    public static synchronized Optional<Builder> builder(Foreign foreign) {
        return Optional.ofNullable(BUILDERS.get(foreign));
    }
}
