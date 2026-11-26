package dev.silencebound.maxtech.storage.tool.layer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class LayerPathTest {
    @Test
    void singlePointIsOneStep() {
        BlockPos at = new BlockPos(3, 64, -2);
        assertEquals(List.of(at), LayerPath.between(at, at));
        assertEquals(1, LayerPath.length(at, at));
    }

    @Test
    void straightLineBackwards() {
        List<BlockPos> path = LayerPath.between(new BlockPos(5, 0, 0), new BlockPos(2, 0, 0));
        assertEquals(
                List.of(new BlockPos(5, 0, 0), new BlockPos(4, 0, 0), new BlockPos(3, 0, 0), new BlockPos(2, 0, 0)),
                path);
    }

    @Test
    void goesXThenZThenY() {
        BlockPos from = new BlockPos(0, 0, 0);
        BlockPos to = new BlockPos(2, 2, -1);
        List<BlockPos> path = LayerPath.between(from, to);
        assertEquals(List.of(from, new BlockPos(1, 0, 0), new BlockPos(2, 0, 0), new BlockPos(2, 0, -1),
                new BlockPos(2, 1, -1), to), path);
        assertEquals(LayerPath.length(from, to), path.size());
    }

    @Test
    void neighboursTouchFaceToFace() {
        List<BlockPos> path = LayerPath.between(new BlockPos(-7, 70, 12), new BlockPos(9, 60, -4));
        for (int i = 1; i < path.size(); i++) {
            assertEquals(1, path.get(i - 1).distManhattan(path.get(i)), "step " + i);
        }
        assertTrue(path.getLast().equals(new BlockPos(9, 60, -4)));
    }
}
