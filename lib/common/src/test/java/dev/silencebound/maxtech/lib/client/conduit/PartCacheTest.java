package dev.silencebound.maxtech.lib.client.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import dev.silencebound.maxtech.lib.conduit.ConduitRenderData;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ItemLike;
import org.junit.jupiter.api.Test;

class PartCacheTest {
    private static final Direction.Axis[] AXES = {Direction.Axis.X, Direction.Axis.Y, Direction.Axis.Z};
    private static final Direction[] SIDES = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST};
    private static final ConduitType CABLE = new ConduitType(Identifier.fromNamespaceAndPath("test", "cable"), "cable",
            0x4078D8, 0.5F, () -> (ItemLike) () -> {
                throw new UnsupportedOperationException();
            });

    @Test
    void repeatedKeysHitTheCache() {
        PartCache<String, Object> cache = new PartCache<>(key -> new Object());
        Object first = cache.get("a");
        assertSame(first, cache.get("a"));
        assertSame(first, cache.get(new String("a")), "equal keys share a value");
        cache.get("b");
        assertEquals(2, cache.bakes());
        assertEquals(2, cache.size());
    }

    /**
     * A large network of cables (a 32 x 32 x 32 lattice, 32768 cables, every one with its own connections and rings)
     * meshes from as many body parts as it has distinct arm and ring masks, and meshing it again bakes nothing: the
     * parts hold no position, so the model never churns per block.
     */
    @Test
    void aLargeNetworkSharesItsBodies() {
        ConduitLook look = ConduitLook.wire();
        PartCache<ConduitBodyKey, List<ConduitQuad>> bodies = new PartCache<>(key -> ConduitGeometry.body(look, key));
        Random random = new Random(26_1_2L);
        int size = 32;
        int[] arms = new int[size * size * size];
        int[] rings = new int[arms.length];
        Set<Long> distinct = new HashSet<>();
        for (int i = 0; i < arms.length; i++) {
            // Mostly straight runs, with junctions and ends towards blocks mixed in.
            int axis = random.nextInt(3);
            int mask = ConduitConnections
                    .bit(Direction.fromAxisAndDirection(AXES[axis], Direction.AxisDirection.POSITIVE))
                    | ConduitConnections
                            .bit(Direction.fromAxisAndDirection(AXES[axis], Direction.AxisDirection.NEGATIVE));
            if (random.nextInt(8) == 0) {
                mask |= ConduitConnections.bit(SIDES[random.nextInt(SIDES.length)]);
            }
            arms[i] = mask;
            rings[i] = random.nextInt(16) == 0 ? mask & ConduitConnections.bit(Direction.UP) : 0;
            distinct.add(((long) arms[i] << 8) | rings[i]);
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < arms.length; i++) {
                ConduitRenderData data = new ConduitRenderData(CABLE, null, arms[i], rings[i], List.of(), null);
                bodies.get(data.bodyKey());
            }
            assertEquals(distinct.size(), bodies.bakes(), "one bake per distinct body after pass " + pass);
        }
        assertTrue(bodies.bakes() < 64, "a lattice of 32768 cables needs only " + bodies.bakes() + " bodies");
    }
}
