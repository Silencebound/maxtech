package dev.silencebound.maxtech.lib.api.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.inventory.ResourceTank;
import dev.silencebound.maxtech.lib.api.registry.Registrar;
import dev.silencebound.maxtech.lib.api.resource.AmountUnit;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.resource.TestKindTable;
import dev.silencebound.maxtech.lib.api.style.AccentTheme;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import org.junit.jupiter.api.Test;

/**
 * The checks a {@link MenuDefinition} runs when it is built, without Lib's backend (not strict). Tanks use a kind of
 * their own: {@code ResourceKinds}, and with it the fluid tank, needs registries.
 */
class MenuDefinitionTest {
    private static final ItemSlots.Builder SHAPE = ItemSlots.builder(5).output(1, 5);
    private static final ResourceKind<Integer> SPARK = new TestKindTable().register(
            Identifier.fromNamespaceAndPath("test", "definition_spark"), Codec.INT, ByteBufCodecs.VAR_INT.cast(),
            AmountUnit.ITEMS);

    /** Never constructed: getters only run in a live menu. */
    private abstract static class Hub {
        abstract ItemSlots slots();

        abstract ResourceTank<Integer> tank();

        abstract EnergyEndpoint energy();

        abstract long burn();
    }

    private static MenuDefinition<Hub> hub(java.util.function.BiFunction<MenuSlots[], Object[], Layout> layout) {
        return Menus.define("test", "hub", Hub.class, m -> {
            MenuItems<Hub> items = m.items(Hub::slots, SHAPE);
            MenuSlots fuel = m.slots(items, 0, 1);
            MenuSlots output = m.slots(items, 1, 5);
            TankValue tank = m.tank(SPARK, Hub::tank);
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
        MenuDefinition<Hub> definition = hub((slots, values) -> Layout.column(Layout.slots(slots[0], 1, 1),
                Layout.slots(slots[1], 4, 1), Layout.spacer(10, 200)));
        assertEquals(1, definition.problems().size(), definition.problems().toString());
        assertTrue(definition.problems().getFirst().contains("does not fit"));
    }

    /** Lib's backend in a dev run: problems throw; registering hands back a type that must not be used. */
    private static final class StrictBackend implements Menus.Backend {
        int registered;

        @Override
        public boolean strict() {
            return true;
        }

        @Override
        public <H> Supplier<? extends MenuType<?>> register(MenuDefinition<H> definition,
                Registrar<MenuType<?>> registrar) {
            registered++;
            return () -> {
                throw new UnsupportedOperationException();
            };
        }

        @Override
        public <H> void open(MenuDefinition<H> definition, ServerPlayer player, H host) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void problems(MenuDefinition<?> definition) {
        }
    }

    /** A registrar register never reaches: the fake backend ignores it. */
    @SuppressWarnings("unchecked") // never called
    private static Registrar<MenuType<?>> unusedRegistrar() {
        return (Registrar<MenuType<?>>) java.lang.reflect.Proxy.newProxyInstance(
                MenuDefinitionTest.class.getClassLoader(), new Class<?>[]{Registrar.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException();
                });
    }

    @Test
    void definitionBuiltBeforeLibIsCheckedWhenRegistered() {
        Menus.Backend lenient = Menus.backend();
        // Built before Lib sets its backend, as a static field of a class loaded early would be.
        MenuDefinition<Hub> broken = hub((slots, values) -> Layout.slots(slots[0], 1, 1));
        MenuDefinition<Hub> fine = hub(MenuDefinitionTest::labHub);
        StrictBackend strict = new StrictBackend();
        Menus.setBackend(strict);
        try {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> broken.register(unusedRegistrar()));
            assertTrue(String.valueOf(thrown.getMessage()).contains("Slots 1 placed 0 times"), thrown.getMessage());
            assertEquals(0, strict.registered, "a broken definition is not registered");
            fine.register(unusedRegistrar());
            assertEquals(1, strict.registered);
        } finally {
            Menus.setBackend(lenient);
        }
    }

    @Test
    void strictBackendThrows() {
        Menus.Backend lenient = Menus.backend();
        Menus.setBackend(new StrictBackend());
        try {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> hub((slots, values) -> Layout.slots(slots[0], 1, 1)));
            assertTrue(String.valueOf(thrown.getMessage()).contains("Slots 1 placed 0 times"), thrown.getMessage());
            assertEquals(List.of(), hub(MenuDefinitionTest::labHub).problems());
        } finally {
            Menus.setBackend(lenient);
        }
    }

    /** An enum that a status line cannot show. */
    private enum Plain {
        A
    }

    /** A lab hub's state. */
    private enum HubState implements MenuStatus {
        RUNNING;

        @Override
        public String translationKey() {
            return "test.running";
        }

        @Override
        public Tone tone() {
            return Tone.OK;
        }
    }

    @Test
    void statusOfAnEnumWithoutMenusStatusIsReported() {
        MenuDefinition<Hub> definition = Menus.define("test", "plain", Hub.class, m -> {
            EnumValue<Plain> plain = m.enumValue(Plain.class, hub -> Plain.A);
            // The record's constructor takes any enum value; Layout.status would not compile.
            m.layout(new Layout.Status(plain, 60));
        });
        assertEquals(List.of("Status 0: " + Plain.class.getName() + " does not implement MenuStatus"),
                definition.problems());
        MenuDefinition<Hub> fine = Menus.define("test", "state", Hub.class, m -> {
            m.layout(Layout.status(m.enumValue(HubState.class, hub -> HubState.RUNNING), 60));
        });
        assertEquals(List.of(), fine.problems());
    }

    @Test
    void valueLineIsPlacedAndChecked() {
        AtomicReference<IntValue> foreign = new AtomicReference<>();
        MenuDefinition<Hub> definition = Menus.define("test", "value", Hub.class, m -> {
            IntValue samples = m.intValue(hub -> 3);
            foreign.set(samples);
            m.layout(Layout.value(samples, "test.samples_left", 80));
        });
        assertEquals(List.of(), definition.problems());
        LayoutPlan.Box box = definition.plan().boxes().getFirst();
        assertEquals(List.of(7, 7, 80, Layout.TEXT_HEIGHT), List.of(box.x(), box.y(), box.width(), box.height()));
        MenuDefinition<Hub> other = Menus.define("test", "other", Hub.class, m -> {
            m.layout(Layout.value(foreign.get(), "test.samples_left", 80));
        });
        assertEquals(1, other.problems().size(), other.problems().toString());
        assertTrue(other.problems().getFirst().startsWith("Layout uses a handle from another"));
    }

    @Test
    void valueConditionFromAnotherDefinitionIsReported() {
        AtomicReference<IntValue> foreign = new AtomicReference<>();
        Menus.define("test", "condition_owner", Hub.class, m -> {
            IntValue sampler = m.intValue(hub -> 1);
            foreign.set(sampler);
            m.layout(Layout.value(sampler, "test.samples_left", 80));
        });
        MenuDefinition<Hub> other = Menus.define("test", "condition_user", Hub.class, m -> {
            IntValue samples = m.intValue(hub -> 3);
            m.layout(Layout.value(samples, "test.samples_left", 80, foreign.get()));
        });
        assertEquals(1, other.problems().size(), other.problems().toString());
        assertTrue(other.problems().getFirst().startsWith("Layout uses a handle from another"));
    }

    @Test
    void groupsSharingSlotsAreReported() {
        MenuDefinition<Hub> definition = Menus.define("test", "shared", Hub.class, m -> {
            MenuItems<Hub> items = m.items(Hub::slots, SHAPE);
            MenuSlots first = m.slots(items, 0, 3);
            MenuSlots second = m.slots(items, 2, 5);
            m.layout(Layout.row(Layout.slots(first, 3, 1), Layout.slots(second, 3, 1)));
        });
        assertEquals(List.of("Slots 0 and 1 share slots of inventory 0"), definition.problems());
        MenuDefinition<Hub> apart = Menus.define("test", "apart", Hub.class, m -> {
            MenuItems<Hub> items = m.items(Hub::slots, SHAPE);
            MenuSlots first = m.slots(items, 0, 2);
            MenuSlots second = m.slots(items, 2, 5);
            m.layout(Layout.row(Layout.slots(first, 2, 1), Layout.slots(second, 3, 1)));
        });
        assertEquals(List.of(), apart.problems());
    }

    @Test
    void shapeIsCopiedWhenDefined() {
        ItemSlots.Builder shape = ItemSlots.builder(2);
        MenuDefinition<Hub> definition = Menus.define("test", "copy", Hub.class, m -> {
            MenuItems<Hub> items = m.items(Hub::slots, shape);
            m.layout(Layout.slots(m.slots(items, 0, 2), 2, 1));
        });
        assertTrue(definition.items().getFirst().shape() != shape, "the definition holds the caller's builder");
    }

    /** Farms' lab hub: 2 fuel and sampler slots, then 27 outputs. */
    private static final ItemSlots.Builder LAB_HUB = ItemSlots.builder(29).output(2, 29);

    /**
     * The lab hub in one of two arrangements, with {@code extraLines} more label lines below its status line: compact
     * (outputs beside the tank, fuel, sampler and energy) or stacked (outputs below them).
     */
    private static MenuDefinition<Hub> labHub(boolean compact, int extraLines) {
        return Menus.define("test", "lab_hub", Hub.class, m -> {
            MenuItems<Hub> items = m.items(Hub::slots, LAB_HUB);
            MenuSlots fuel = m.slots(items, 0, 1);
            MenuSlots sampler = m.slots(items, 1, 2);
            MenuSlots outputs = m.slots(items, 2, 29);
            TankValue nutrient = m.tank(SPARK, Hub::tank);
            EnergyValue energy = m.energy(Hub::energy);
            ProgressValue burn = m.progress(Hub::burn, Hub::burn);
            EnumValue<HubState> state = m.enumValue(HubState.class, hub -> HubState.RUNNING);
            IntValue samples = m.intValue(hub -> 0);
            Layout machine = Layout.row(Layout.tank(nutrient, 18, 52),
                    Layout.column(Layout.SMALL_GAP, Layout.slots(fuel, 1, 1),
                            Layout.progress(burn, Layout.ProgressShape.FLAME)),
                    Layout.slots(sampler, 1, 1), Layout.energy(energy, 52));
            Layout grid = Layout.slots(outputs, 9, 3);
            int width = compact ? 242 : 162;
            Layout status = Layout.row(Layout.status(state, width / 2 - 2),
                    Layout.value(samples, "test.samples_left", width / 2 - 2));
            List<Layout> parts = new java.util.ArrayList<>();
            if (compact) {
                parts.add(Layout.row(machine, grid));
            } else {
                parts.add(machine);
                parts.add(grid);
            }
            parts.add(status);
            for (int i = 0; i < extraLines; i++) {
                parts.add(Layout.label("test.line", width));
            }
            parts.add(Layout.playerInventory());
            m.shiftClick(fuel, sampler);
            m.layout(Layout.column(parts.toArray(Layout[]::new)));
        });
    }

    @Test
    void farmsLabHubFitsWithItsTitleTab() {
        MenuDefinition<Hub> compact = labHub(true, 0);
        assertEquals(List.of(), compact.problems());
        // 7 + 54 + 4 + 10 + 4 + 76 + 7 = 162 tall (plus the tab 173), 7 + 242 + 7 = 256 wide.
        assertEquals(List.of(256, 162), List.of(compact.plan().panelWidth(), compact.plan().panelHeight()));
        // Room for four more lines of text: 162 + 4 * 14 + 11 = 229.
        assertEquals(List.of(), labHub(true, 4).problems());

        MenuDefinition<Hub> stacked = labHub(false, 0);
        // 7 + 52 + 4 + 54 + 4 + 10 + 4 + 76 + 7 = 218 tall, plus the tab 229: fits, but one more line does not.
        assertEquals(List.of(), stacked.problems());
        assertEquals(218, stacked.plan().panelHeight());
        assertTrue(labHub(false, 1).problems().getFirst().contains("does not fit"), "stacked with a label line");
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
