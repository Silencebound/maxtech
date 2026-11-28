package dev.silencebound.maxtech.lib.api.menu;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.registry.Registrar;
import dev.silencebound.maxtech.lib.api.style.AccentTheme;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * One kind of MaxTech menu and its screen, declared once with {@link Menus#define}: the host's slots, tanks, energy,
 * progress and other synced values, where shift-clicks go, the mod's {@link AccentTheme} and the {@link Layout} of the
 * panel. Lib builds the server and client menus and the screen from it; an addon writes no menu or screen class and no
 * client code.
 *
 * <pre>{@code
 * static final MenuDefinition<LabHub> HUB = Menus.define(MOD_ID, "lab_hub", LabHub.class, m -> {
 *     MenuItems<LabHub> items = m.items(LabHub::slots, LabHub.SLOTS);
 *     MenuSlots fuel = m.slots(items, 0, 1);
 *     TankValue nutrient = m.tank(LabHub::tank);
 *     EnergyValue energy = m.energy(LabHub::energy);
 *     ProgressValue burn = m.progress(LabHub::burnLeft, LabHub::burnTotal);
 *     m.shiftClick(fuel);
 *     m.theme(FARMS_THEME);
 *     m.layout(Layout.column(
 *             Layout.row(Layout.tank(nutrient, 18, 52), Layout.slots(fuel, 1, 1),
 *                     Layout.progress(burn, Layout.ProgressShape.FLAME), Layout.energy(energy, 52)),
 *             Layout.playerInventory()));
 * });
 * // during mod construction:
 * HUB.register(Registrars.create(MOD_ID, Registries.MENU));
 * // when a player uses the block, on the server:
 * HUB.open(serverPlayer, hub);
 * }</pre>
 *
 * The layout is checked when the definition is built ({@link #problems}): the panel and its title tab must fit a GUI of
 * {@link LayoutPlan#FIT_WIDTH} by {@link LayoutPlan#FIT_HEIGHT}, no boxes may overlap, every slot group must be placed
 * exactly once in cells enough for it, the player's inventory at most once, and every handle must come from this
 * definition. In dev runs a problem throws {@link IllegalStateException} at once; in a built game the definition keeps
 * working with the problem logged. Text never moves anything (see {@link Layout}), so a layout that passes in dev
 * passes on every client.
 * <p>
 * Immutable once built; any thread. Getters run on the server thread (see {@link MenuValues}).
 *
 * @param <H>
 *            the server-side object the menu shows, usually a block entity
 */
public final class MenuDefinition<H> {
    private final String modId;
    private final String name;
    private final Class<H> hostClass;
    private final String titleKey;
    private final AccentTheme theme;
    private final MenuValues<H> values;
    private final List<MenuItems<H>> items;
    private final List<MenuSlots> slots;
    private final List<Tank<H>> tanks;
    private final List<MenuSlots> shiftOrder;
    private final @Nullable BiPredicate<? super H, Player> validWhile;
    private final Layout layout;
    private final LayoutPlan plan;
    private final List<String> problems;
    private @Nullable Supplier<? extends MenuType<?>> type;

    private MenuDefinition(Builder<H> builder, Layout layout) {
        this.modId = builder.modId;
        this.name = builder.name;
        this.hostClass = builder.hostClass;
        this.titleKey = builder.titleKey != null ? builder.titleKey : "container." + modId + "." + name;
        this.theme = builder.theme;
        this.values = builder.values.build();
        this.items = List.copyOf(builder.items);
        this.slots = List.copyOf(builder.slots);
        this.tanks = List.copyOf(builder.tanks);
        this.shiftOrder = List.copyOf(builder.shiftOrder);
        this.validWhile = builder.validWhile;
        this.layout = layout;
        this.plan = LayoutPlan.of(layout);
        List<String> found = new ArrayList<>(plan.problems());
        found.addAll(builder.checkLayout(plan));
        this.problems = List.copyOf(found);
    }

    /** Builds a definition; see {@link Menus#define}. */
    static <H> MenuDefinition<H> build(String modId, String name, Class<H> hostClass, Consumer<Builder<H>> body) {
        Builder<H> builder = new Builder<>(modId, name, hostClass);
        body.accept(builder);
        builder.done = true;
        Layout layout = builder.layout;
        if (layout == null) {
            throw new IllegalStateException("Menu " + modId + ":" + name + " has no layout");
        }
        MenuDefinition<H> definition = new MenuDefinition<>(builder, layout);
        Menus.checked(definition);
        return definition;
    }

    /**
     * Registers the menu type as {@code modId:name} with {@code registrar} (from
     * {@code Registrars.create(modId, Registries.MENU)}) and, on a client, the screen. Once, during mod construction.
     *
     * @throws IllegalStateException
     *             when called twice
     */
    public void register(Registrar<MenuType<?>> registrar) {
        if (type != null) {
            throw new IllegalStateException("Menu " + modId + ":" + name + " registered twice");
        }
        type = Menus.backend().register(this, registrar);
    }

    /**
     * Opens the menu over {@code host} for {@code player}, replacing any menu the player has open. Server thread; the
     * menu type must be registered.
     */
    public void open(ServerPlayer player, H host) {
        Menus.backend().open(this, player, hostClass.cast(host));
    }

    /** What is wrong with the layout (see the class comment); empty when nothing is. */
    public List<String> problems() {
        return problems;
    }

    /** The arranged layout: the panel's size and every box. */
    public LayoutPlan plan() {
        return plan;
    }

    /** The mod that declared the menu. */
    public String modId() {
        return modId;
    }

    /** The menu's name in its mod; the menu type's id path. */
    public String name() {
        return name;
    }

    /** The lang key of the panel's title. */
    public String titleKey() {
        return titleKey;
    }

    /** The colours the screen draws in. */
    public AccentTheme theme() {
        return theme;
    }

    /** The layout as declared. */
    public Layout layout() {
        return layout;
    }

    /** The registered menu type; throws {@link IllegalStateException} before {@link #register}. */
    @ApiStatus.Internal
    public Supplier<? extends MenuType<?>> type() {
        Supplier<? extends MenuType<?>> registered = type;
        if (registered == null) {
            throw new IllegalStateException("Menu " + modId + ":" + name + " is not registered");
        }
        return registered;
    }

    /** The host's type. */
    @ApiStatus.Internal
    public Class<H> hostClass() {
        return hostClass;
    }

    /** The synced values, in declaration order. */
    @ApiStatus.Internal
    public MenuValues<H> values() {
        return values;
    }

    /** The inventories, in declaration order. */
    @ApiStatus.Internal
    public List<MenuItems<H>> items() {
        return items;
    }

    /** The slot groups, in declaration order. */
    @ApiStatus.Internal
    public List<MenuSlots> slots() {
        return slots;
    }

    /** The tanks, in declaration order (their bucket click ids follow it). */
    @ApiStatus.Internal
    public List<Tank<H>> tanks() {
        return tanks;
    }

    /** Where shift-clicked player stacks go, in order. */
    @ApiStatus.Internal
    public List<MenuSlots> shiftOrder() {
        return shiftOrder;
    }

    /** The test for whether the menu stays open, when the definition sets one. */
    @ApiStatus.Internal
    public Optional<BiPredicate<? super H, Player>> validWhile() {
        return Optional.ofNullable(validWhile);
    }

    /**
     * A declared tank: its value handle and the getter of the host's tank.
     *
     * @param value
     *            the synced value
     * @param tank
     *            the host's tank
     */
    @ApiStatus.Internal
    public record Tank<H>(TankValue value, Function<? super H, FluidTank> tank) {
    }

    /**
     * Collects a definition inside {@link Menus#define}; every method returns a handle for the layout (or nothing).
     * Usable only inside that call; not thread-safe.
     */
    public static final class Builder<H> {
        private final String modId;
        private final String name;
        private final Class<H> hostClass;
        private final MenuValues.Builder<H> values = MenuValues.builder();
        private final List<MenuItems<H>> items = new ArrayList<>();
        private final List<MenuSlots> slots = new ArrayList<>();
        private final List<Tank<H>> tanks = new ArrayList<>();
        private final List<MenuSlots> shiftOrder = new ArrayList<>();
        private final Set<Object> handles = Collections.newSetFromMap(new IdentityHashMap<>());
        private @Nullable String titleKey;
        private AccentTheme theme = AccentTheme.LIB;
        private @Nullable BiPredicate<? super H, Player> validWhile;
        private @Nullable Layout layout;
        private boolean done;

        private Builder(String modId, String name, Class<H> hostClass) {
            this.modId = Objects.requireNonNull(modId, "modId");
            this.name = Objects.requireNonNull(name, "name");
            this.hostClass = Objects.requireNonNull(hostClass, "hostClass");
        }

        /**
         * An inventory of the host: {@code getter} returns its {@link ItemSlots}, built from {@code shape}, which the
         * client menu builds its empty copy from.
         */
        public MenuItems<H> items(Function<? super H, ItemSlots> getter, ItemSlots.Builder shape) {
            checkOpen();
            MenuItems<H> handle = new MenuItems<>(items.size(), getter, shape);
            items.add(handle);
            return handle;
        }

        /**
         * Menu slots for slots {@code from} (inclusive) to {@code to} (exclusive) of {@code inventory}. The menu adds
         * groups in declaration order, then the player's inventory.
         */
        public MenuSlots slots(MenuItems<H> inventory, int from, int to) {
            checkOpen();
            if (!items.contains(inventory)) {
                throw new IllegalArgumentException("Inventory from another menu definition");
            }
            if (from < 0 || to > inventory.size() || from >= to) {
                throw new IndexOutOfBoundsException("Slots " + from + " to " + to + " of " + inventory.size());
            }
            MenuSlots handle = new MenuSlots(slots.size(), inventory, from, to);
            slots.add(handle);
            handles.add(handle);
            return handle;
        }

        /**
         * A fluid tank of the host, synced and clickable: a click with a fluid container fills the container from the
         * tank or empties it into the tank.
         */
        public TankValue tank(Function<? super H, FluidTank> getter) {
            checkOpen();
            TankValue handle = values.tank(host -> getter.apply(host).resource(), host -> getter.apply(host).amount(),
                    host -> getter.apply(host).capacity());
            tanks.add(new Tank<>(handle, getter));
            handles.add(handle);
            return handle;
        }

        /** The host's energy buffer, synced as stored and capacity in MU. */
        public EnergyValue energy(Function<? super H, EnergyEndpoint> getter) {
            checkOpen();
            EnergyValue handle = values.energy(host -> getter.apply(host).stored(),
                    host -> getter.apply(host).capacity());
            handles.add(handle);
            return handle;
        }

        /** Work done and work in total, in ticks (the screen shows seconds left at 20 ticks a second). */
        public ProgressValue progress(ToLongFunction<? super H> current, ToLongFunction<? super H> total) {
            checkOpen();
            ProgressValue handle = values.progress(current, total);
            handles.add(handle);
            return handle;
        }

        /** An enum constant; {@link Layout#status} shows one that implements {@link MenuStatus}. */
        public <E extends Enum<E>> EnumValue<E> enumValue(Class<E> type, Function<? super H, E> getter) {
            checkOpen();
            EnumValue<E> handle = values.enumValue(type, getter);
            handles.add(handle);
            return handle;
        }

        /** A whole number, synced for the screen. */
        public IntValue intValue(ToIntFunction<? super H> getter) {
            checkOpen();
            IntValue handle = values.intValue(getter);
            handles.add(handle);
            return handle;
        }

        /**
         * Where shift-clicked player stacks go: these groups in this order, each filled before the next; a stack none
         * of them takes moves between the main inventory and the hotbar. Groups not named never receive shift-clicked
         * items. Replaces any earlier order.
         */
        public void shiftClick(MenuSlots... groups) {
            checkOpen();
            for (MenuSlots group : groups) {
                if (!slots.contains(group)) {
                    throw new IllegalArgumentException("Slot group from another menu definition");
                }
            }
            shiftOrder.clear();
            shiftOrder.addAll(Arrays.asList(groups));
        }

        /** The lang key of the panel's title; {@code container.<modId>.<name>} by default. */
        public void title(String key) {
            checkOpen();
            titleKey = Objects.requireNonNull(key, "key");
        }

        /** The colours the screen draws in; {@link AccentTheme#LIB} by default. */
        public void theme(AccentTheme value) {
            checkOpen();
            theme = Objects.requireNonNull(value, "theme");
        }

        /**
         * Whether the menu stays open for a player; checked on the server every tick. By default a block entity host
         * keeps it open while it stands and the player is in reach (vanilla's rule), and any other host always does.
         */
        public void validWhile(BiPredicate<? super H, Player> test) {
            checkOpen();
            validWhile = Objects.requireNonNull(test, "test");
        }

        /** The panel's layout; required. */
        public void layout(Layout root) {
            checkOpen();
            layout = Objects.requireNonNull(root, "root");
        }

        private void checkOpen() {
            if (done) {
                throw new IllegalStateException("Menu definition " + modId + ":" + name + " already built");
            }
        }

        /** The definition's own checks of {@code plan}: groups placed once with room, handles from here. */
        private List<String> checkLayout(LayoutPlan plan) {
            List<String> problems = new ArrayList<>();
            IdentityHashMap<MenuSlots, Integer> placed = new IdentityHashMap<>();
            int inventories = 0;
            for (LayoutPlan.Box box : plan.boxes()) {
                Object handle = switch (box.node()) {
                    case Layout.Slots node -> {
                        placed.merge(node.group(), 1, Integer::sum);
                        if (node.columns() * node.rows() < node.group().size()) {
                            problems.add("Slots " + node.group().index() + ": " + node.columns() + "x" + node.rows()
                                    + " cells for " + node.group().size() + " slots");
                        }
                        yield node.group();
                    }
                    case Layout.Tank node -> node.value();
                    case Layout.Energy node -> node.value();
                    case Layout.Progress node -> node.value();
                    case Layout.Status node -> node.value();
                    case Layout.PlayerInventory node -> {
                        inventories++;
                        yield null;
                    }
                    case Layout.Label node -> null;
                    case Layout.Spacer node -> null;
                };
                if (handle != null && !handles.contains(handle)) {
                    problems.add("Layout uses a handle from another menu definition: " + box.node());
                }
            }
            for (MenuSlots group : slots) {
                int count = placed.getOrDefault(group, 0);
                if (count != 1) {
                    problems.add("Slots " + group.index() + " placed " + count + " times, must be once");
                }
            }
            if (inventories > 1) {
                problems.add("Player inventory placed " + inventories + " times");
            }
            return problems;
        }
    }
}
