package dev.silencebound.maxtech.lib.api.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.lib.api.resource.AmountUnit;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.resource.TestKindTable;
import dev.silencebound.maxtech.lib.api.transfer.TransferPort;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** The checks a definition runs on port values, faces nodes and its side config tab. */
class PortValueDefinitionTest {
    private static final TestKindTable KINDS = new TestKindTable();
    private static final ResourceKind<Integer> A = kind("port_a");
    private static final ResourceKind<Integer> B = kind("port_b");
    private static final ResourceKind<Integer> C = kind("port_c");
    private static final ResourceKind<Integer> D = kind("port_d");

    private static ResourceKind<Integer> kind(String path) {
        return KINDS.register(Identifier.fromNamespaceAndPath("test", path), Codec.INT, ByteBufCodecs.VAR_INT.cast(),
                AmountUnit.ITEMS);
    }

    /** Never constructed: getters only run in a live menu. */
    private abstract static class Host {
        abstract TransferPort port();
    }

    @Test
    void facesNodeHasTheNetsSize() {
        AtomicReference<PortValue> handle = new AtomicReference<>();
        MenuDefinition<Host> definition = Menus.define("test", "faces", Host.class, m -> {
            PortValue port = m.port(Host::port, A, B);
            handle.set(port);
            m.layout(Layout.faces(port));
        });
        assertEquals(List.of(), definition.problems());
        LayoutPlan.Box box = definition.plan().boxes().getFirst();
        assertEquals(Layout.FACES_WIDTH, box.width());
        assertEquals(Layout.FACES_PREVIEW + 2 * (Layout.SMALL_GAP + Layout.FACES_NET_HEIGHT), box.height());
        assertEquals(List.of(A, B), handle.get().kinds());
        assertEquals(1, handle.get().kindIndex(B));
        assertEquals(-1, handle.get().kindIndex(C));
        assertEquals(1, definition.ports().size());
    }

    @Test
    void facesOfAKindThePortValueLacksAreAProblem() {
        MenuDefinition<Host> definition = Menus.define("test", "faces_foreign_kind", Host.class, m -> {
            PortValue port = m.port(Host::port, A);
            m.layout(Layout.faces(port, B));
        });
        assertEquals(1, definition.problems().size(), definition.problems().toString());
        assertTrue(definition.problems().getFirst().contains("test:port_b"), definition.problems().toString());
    }

    @Test
    void sideConfigOfThreeKindsFitsAndFourDoNot() {
        MenuDefinition<Host> three = Menus.define("test", "side_three", Host.class, m -> {
            m.sideConfig(m.port(Host::port, A, B, C));
            m.layout(Layout.playerInventory());
        });
        assertEquals(List.of(), three.problems());
        assertEquals(List.of(A, B, C), three.sideConfig().orElseThrow().kinds());
        MenuDefinition<Host> four = Menus.define("test", "side_four", Host.class, m -> {
            m.sideConfig(m.port(Host::port, A, B, C, D));
            m.layout(Layout.playerInventory());
        });
        assertEquals(1, four.problems().size(), four.problems().toString());
        assertTrue(four.problems().getFirst().startsWith("Side config flyout"), four.problems().toString());
    }

    @Test
    void sideConfigShowsChosenKindsOnceAndOnlyOwnPorts() {
        MenuDefinition<Host> chosen = Menus.define("test", "side_chosen", Host.class, m -> {
            m.sideConfig(m.port(Host::port, A, B), B);
            m.layout(Layout.playerInventory());
        });
        assertEquals(List.of(B), chosen.sideConfig().orElseThrow().kinds());
        assertThrows(IllegalStateException.class, () -> Menus.define("test", "side_twice", Host.class, m -> {
            PortValue port = m.port(Host::port, A);
            m.sideConfig(port);
            m.sideConfig(port);
            m.layout(Layout.playerInventory());
        }));
        AtomicReference<PortValue> foreign = new AtomicReference<>();
        Menus.define("test", "side_owner", Host.class, m -> {
            foreign.set(m.port(Host::port, A));
            m.layout(Layout.playerInventory());
        });
        assertThrows(IllegalArgumentException.class, () -> Menus.define("test", "side_foreign", Host.class, m -> {
            m.sideConfig(foreign.get());
            m.layout(Layout.playerInventory());
        }));
        MenuDefinition<Host> user = Menus.define("test", "faces_foreign", Host.class, m -> {
            m.port(Host::port, A);
            m.layout(Layout.faces(foreign.get()));
        });
        assertTrue(user.problems().stream().anyMatch(problem -> problem.contains("another menu definition")),
                user.problems().toString());
    }

    @Test
    void portValuesNeedDistinctKinds() {
        assertThrows(IllegalArgumentException.class, () -> Menus.define("test", "port_none", Host.class, m -> {
            m.port(Host::port);
            m.layout(Layout.playerInventory());
        }));
        assertThrows(IllegalArgumentException.class, () -> Menus.define("test", "port_twice", Host.class, m -> {
            m.port(Host::port, A, A);
            m.layout(Layout.playerInventory());
        }));
        assertThrows(IllegalArgumentException.class, () -> Layout.faces(portOf(), A, A));
    }

    private static PortValue portOf() {
        AtomicReference<PortValue> handle = new AtomicReference<>();
        Menus.define("test", "port_of", Host.class, m -> {
            handle.set(m.port(Host::port, A));
            m.layout(Layout.playerInventory());
        });
        return handle.get();
    }
}
