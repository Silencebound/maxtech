package dev.silencebound.maxtech.lib.api.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.style.AccentTheme;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The checks a {@link MenuDefinition} runs when it is built, without Lib's backend (not strict). */
class MenuDefinitionTest {
    private static final ItemSlots.Builder SHAPE = ItemSlots.builder(5).output(1, 5);

    /** Never constructed: getters only run in a live menu. */
    private abstract static class Hub {
        abstract ItemSlots slots();

        abstract FluidTank tank();

        abstract EnergyEndpoint energy();

        abstract long burn();
    }

    private static MenuDefinition<Hub> hub(java.util.function.BiFunction<MenuSlots[], Object[], Layout> layout) {
        return Menus.define("test", "hub", Hub.class, m -> {
            MenuItems<Hub> items = m.items(Hub::slots, SHAPE);
            MenuSlots fuel = m.slots(items, 0, 1);
            MenuSlots output = m.slots(items, 1, 5);
            TankValue tank = m.tank(Hub::tank);
            EnergyValue energy = m.energy(Hub::energy);
            ProgressValue burn = m.progress(Hub::burn, Hub::burn);
            m.shiftClick(fuel);
            m.layout(layout.apply(new MenuSlots[]{fuel, output}, new Object[]{tank, energy, burn}));
        });
    }

    private static Layout labHub(MenuSlots[] slots, Object[] values) {
        return LayoutPlanTest.labHub(slots[0], slots[1], (TankValue) values[0], (EnergyValue) values[1],
                (ProgressValue) values[2]);
    }

    @Test
    void labHubDefinitionHasNoProblems() {
        MenuDefinition<Hub> definition = hub(MenuDefinitionTest::labHub);
        assertEquals(List.of(), definition.problems());
        assertEquals("container.test.hub", definition.titleKey());
        assertEquals(AccentTheme.LIB, definition.theme());
        assertEquals(2, definition.slots().size());
        assertEquals(1, definition.tanks().size());
        assertEquals(3, definition.values().size());
        assertEquals(List.of(definition.slots().getFirst()), definition.shiftOrder());
    }

    @Test
    void unplacedGroupIsReported() {
        MenuDefinition<Hub> definition = hub(
                (slots, values) -> Layout.column(Layout.slots(slots[0], 1, 1), Layout.playerInventory()));
        assertEquals(List.of("Slots 1 placed 0 times, must be once"), definition.problems());
    }

    @Test
    void groupPlacedTwiceOrInTooFewCells() {
        MenuDefinition<Hub> definition = hub((slots, values) -> Layout.row(Layout.slots(slots[0], 1, 1),
                Layout.slots(slots[0], 1, 1), Layout.slots(slots[1], 3, 1)));
        assertEquals(List.of("Slots 1: 3x1 cells for 4 slots", "Slots 0 placed 2 times, must be once"),
                definition.problems());
    }

    @Test
    void secondPlayerInventoryIsReported() {
        MenuDefinition<Hub> definition = hub((slots, values) -> Layout.row(Layout.slots(slots[0], 1, 1),
                Layout.slots(slots[1], 4, 1), Layout.column(Layout.playerInventory(), Layout.playerInventory())));
        assertTrue(definition.problems().contains("Player inventory placed 2 times"), definition.problems().toString());
    }

    @Test
    void handleFromAnotherDefinitionIsReported() {
        AtomicReference<TankValue> foreign = new AtomicReference<>();
        hub((slots, values) -> {
            foreign.set((TankValue) values[0]);
            return labHub(slots, values);
        });
        MenuDefinition<Hub> definition = hub((slots, values) -> Layout.column(Layout.slots(slots[0], 1, 1),
                Layout.slots(slots[1], 4, 1), Layout.tank(foreign.get(), 18, 18)));
        assertEquals(1, definition.problems().size(), definition.problems().toString());
        assertTrue(definition.problems().getFirst().startsWith("Layout uses a handle from another"));
    }

    @Test
    void geometryProblemsReachTheDefinition() {
        MenuDefinition<Hub> definition = hub(
                (slots, values) -> Layout.row(-2, Layout.slots(slots[0], 1, 1), Layout.slots(slots[1], 4, 1)));
        assertEquals(1, definition.problems().size(), definition.problems().toString());
        assertTrue(definition.problems().getFirst().contains("overlaps"));
    }

    @Test
    void strictBackendThrows() {
        Menus.Backend lenient = Menus.backend();
        Menus.setBackend(new Menus.Backend() {
            @Override
            public boolean strict() {
                return true;
            }

            @Override
            public <H> java.util.function.Supplier<? extends net.minecraft.world.inventory.MenuType<?>> register(
                    MenuDefinition<H> definition,
                    dev.silencebound.maxtech.lib.api.registry.Registrar<net.minecraft.world.inventory.MenuType<?>> r) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <H> void open(MenuDefinition<H> definition, net.minecraft.server.level.ServerPlayer player, H host) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void problems(MenuDefinition<?> definition) {
            }
        });
        try {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> hub((slots, values) -> Layout.slots(slots[0], 1, 1)));
            assertTrue(String.valueOf(thrown.getMessage()).contains("Slots 1 placed 0 times"), thrown.getMessage());
            assertEquals(List.of(), hub(MenuDefinitionTest::labHub).problems());
        } finally {
            Menus.setBackend(lenient);
        }
    }

    @Test
    void misuseThrowsAtOnce() {
        assertThrows(IllegalStateException.class, () -> Menus.define("test", "none", Hub.class, m -> {
        }));
        assertThrows(IndexOutOfBoundsException.class, () -> Menus.define("test", "bad", Hub.class, m -> {
            m.slots(m.items(Hub::slots, SHAPE), 3, 6);
        }));
        MenuDefinition<Hub> other = hub(MenuDefinitionTest::labHub);
        assertThrows(IllegalArgumentException.class, () -> Menus.define("test", "foreign", Hub.class, m -> {
            m.shiftClick(other.slots().getFirst());
        }));
    }
}
