package dev.silencebound.maxtech.lib.neoforge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceKey;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitParts;
import dev.silencebound.maxtech.lib.client.conduit.ConduitGeometry;
import dev.silencebound.maxtech.lib.client.conduit.LibConduitParts;
import dev.silencebound.maxtech.lib.neoforge.api.client.conduit.ConduitModelHook;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ItemLike;
import org.junit.jupiter.api.Test;

class ConduitModelHooksTest {
    private static final Identifier TUBE = Identifier.fromNamespaceAndPath("test", "tube");
    private static final Identifier PIPE = Identifier.fromNamespaceAndPath("test", "pipe");
    private static final Identifier GLASS = Identifier.fromNamespaceAndPath("test", "block/glass");
    private static final Identifier FLUID = Identifier.fromNamespaceAndPath("test", "block/fluid");
    private static final ConduitModelHook HOOK = () -> Set.of(GLASS, FLUID);
    private static final ConduitType TYPE = new ConduitType(Identifier.fromNamespaceAndPath("test", "net"), "test",
            0x4078D8, 0.5F, () -> (ItemLike) () -> {
                throw new UnsupportedOperationException();
            });

    /** Each marked conduit gets its hook, with the translucency its builder declared. */
    @Test
    void markedConduitsGetTheirHooks() {
        ConduitModelHooks hooks = new ConduitModelHooks(Map.of(TUBE, true, PIPE, false));
        hooks.add(TUBE, HOOK);
        hooks.add(PIPE, HOOK);
        Map<Identifier, ConduitModelHooks.Hooked> result = hooks.finish();
        assertEquals(new ConduitModelHooks.Hooked(TUBE, HOOK, true), result.get(TUBE));
        assertEquals(new ConduitModelHooks.Hooked(PIPE, HOOK, false), result.get(PIPE));
    }

    @Test
    void aMarkedConduitWithoutAHookFailsNamingIt() {
        ConduitModelHooks hooks = new ConduitModelHooks(Map.of(TUBE, true, PIPE, false));
        hooks.add(PIPE, HOOK);
        IllegalStateException error = assertThrows(IllegalStateException.class, hooks::finish);
        assertTrue(String.valueOf(error.getMessage()).contains(TUBE.toString()), error.getMessage());
    }

    @Test
    void aHookForAnUnmarkedConduitFails() {
        ConduitModelHooks hooks = new ConduitModelHooks(Map.of(TUBE, true));
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> hooks.add(PIPE, HOOK));
        assertTrue(String.valueOf(error.getMessage()).contains(PIPE.toString()), error.getMessage());
    }

    @Test
    void aSecondHookFails() {
        ConduitModelHooks hooks = new ConduitModelHooks(Map.of(TUBE, true));
        hooks.add(TUBE, HOOK);
        assertThrows(IllegalStateException.class, () -> hooks.add(TUBE, HOOK));
    }

    /** Every sprite the hook lists is resolved, each once, and nothing else. */
    @Test
    void spritesResolveTheHooksSet() {
        List<Identifier> asked = new ArrayList<>();
        Map<Identifier, String> sprites = ConduitModelHooks.sprites(HOOK, id -> {
            asked.add(id);
            return "sprite " + id;
        });
        assertEquals(Map.of(GLASS, "sprite " + GLASS, FLUID, "sprite " + FLUID), sprites);
        assertEquals(2, asked.size());
    }

    /** Only a translucent custom model reports translucent quads; Lib's solid look never does. */
    @Test
    void materialFlagsFollowTheDeclaredTranslucency() {
        assertEquals(BakedQuad.FLAG_TRANSLUCENT, ConduitModel.materialFlags(true));
        assertEquals(0, ConduitModel.materialFlags(false));
    }

    /** A shell's glass is translucent, so Lib's model of a shell conduit reports it; a solid one does not. */
    @Test
    void shellModelsAreTranslucent() {
        assertTrue(ConduitModel.translucent(ConduitLook.shell(GLASS)));
        assertEquals(BakedQuad.FLAG_TRANSLUCENT,
                ConduitModel.materialFlags(ConduitModel.translucent(ConduitLook.shell(GLASS).withBorder(0))));
        assertEquals(0, ConduitModel.materialFlags(ConduitModel.translucent(ConduitLook.wire())));
    }

    /** A hook that overrides nothing draws exactly Lib's body, rings and stubs. */
    @Test
    void defaultPartsAreLibs() {
        ConduitParts.setFactory(new LibConduitParts());
        for (ConduitLook look : List.of(ConduitLook.wire(), ConduitLook.DEFAULT, ConduitLook.shell(GLASS))) {
            for (boolean labels : new boolean[]{false, true}) {
                for (int arms = 0; arms < 64; arms++) {
                    ConduitBodyKey key = new ConduitBodyKey(TYPE, DyeColor.RED, arms, arms & 0b010001, 7);
                    assertEquals(ConduitGeometry.body(look, key, labels), HOOK.body(look, key, labels));
                }
                for (Direction side : Direction.values()) {
                    for (ConduitFaceMode mode : ConduitFaceMode.values()) {
                        ConduitFaceKey key = new ConduitFaceKey(TYPE, side, mode);
                        assertEquals(ConduitGeometry.face(look, key, labels), HOOK.face(look, key, labels));
                    }
                }
            }
        }
    }
}
