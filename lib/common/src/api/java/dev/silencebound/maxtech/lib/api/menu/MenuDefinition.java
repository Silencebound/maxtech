package dev.silencebound.maxtech.lib.api.menu;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.inventory.ResourceTank;
import dev.silencebound.maxtech.lib.api.registry.Registrar;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.api.style.AccentTheme;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import dev.silencebound.maxtech.lib.api.transfer.TransferPort;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * One kind of MaxTech menu and its screen, declared once with {@link Menus#define}: the host's slots, tanks, energy,
 * progress and other synced values, the buttons that ask the server to change a value ({@link Builder#cycle}), its
 * transfer port's side config ({@link Builder#port}, {@link Builder#sideConfig}), where shift-clicks go, the mod's
 * {@link AccentTheme} and the {@link Layout} of the panel. Lib builds the server and client menus and the screen from
 * it; an addon writes no menu or screen class and no client code.
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
 * exactly once in cells enough for it, no two groups may share a slot of their inventory, the player's inventory at
 * most once, status lines must show an enum that implements {@link MenuStatus}, and every handle must come from this
 * definition. In dev runs a problem throws {@link IllegalStateException} at once (or in {@link #register}, for a
 * definition built before Lib's construction); in a built game the definition keeps working with the problem logged.
 * Text never moves anything (see {@link Layout}), so a layout that passes in dev passes on every client.
 * <p>
 * The server and the client build the menu from the same definition and must place the same slots at the same
 * positions, so the layout and the inventory shapes must be the same on both sides: build them from constants only,
 * never from config, the side or other state that can differ between a server and its clients.
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
    private final List<Tank<H, ?>> tanks;
    private final List<Cycle<H, ?>> cycles;
    private final List<Port<H>> ports;
    private final Layout.@Nullable Faces sideConfig;
    private final List<MenuSlots> shiftOrder;
    private final @Nullable BiPredicate<? super H, Player> validWhile;
    private final Layout layout;
    private final LayoutPlan plan;
    private final List<String> problems;
    private @Nullable Supplier<? extends MenuType<?>> type;
    /** Whether Lib's backend has seen the problems; false for a definition built before Lib's construction. */
    private volatile boolean checked;

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
        this.cycles = List.copyOf(builder.cycles);
        this.sideConfig = builder.sideConfig;
        this.shiftOrder = List.copyOf(builder.shiftOrder);
        this.validWhile = builder.validWhile;
        this.layout = layout;
        this.plan = LayoutPlan.of(layout);
        this.ports = shown(builder.ports, builder.sideConfig, plan);
        List<String> found = new ArrayList<>(plan.problems());
        found.addAll(builder.checkLayout(plan));
        this.problems = List.copyOf(found);
    }

    /** {@code ports} with the kinds each shows in a faces node of {@code plan} or {@code side}. */
    private static <H> List<Port<H>> shown(List<Port<H>> ports, Layout.@Nullable Faces side, LayoutPlan plan) {
        int[] masks = new int[ports.size()];
        List<Layout.Faces> nodes = new ArrayList<>();
        for (LayoutPlan.Box box : plan.boxes()) {
            if (box.node() instanceof Layout.Faces faces) {
                nodes.add(faces);
            }
        }
        if (side != null) {
            nodes.add(side);
        }
        for (Layout.Faces node : nodes) {
            int index = node.port().index();
            if (index < ports.size() && ports.get(index).value() == node.port()) {
                for (ResourceKind<?> kind : node.kinds()) {
                    int at = node.port().kindIndex(kind);
                    if (at >= 0) {
                        masks[index] |= 1 << at;
                    }
                }
            }
        }
        List<Port<H>> out = new ArrayList<>();
        for (int i = 0; i < masks.length; i++) {
            Port<H> port = ports.get(i);
            out.add(new Port<>(port.value(), port.port(), masks[i]));
        }
        return List.copyOf(out);
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
        definition.checked = Menus.checked(definition);
        return definition;
    }

    /**
     * Registers the menu type as {@code modId:name} with {@code registrar} (from
     * {@code Registrars.create(modId, Registries.MENU)}) and, on a client, the screen. Once, during mod construction.
     * Checks the layout first when the definition was built before Lib's construction (see the class comment).
     *
     * @throws IllegalStateException
     *             when called twice, or in a dev run for a layout with problems
     */
    public void register(Registrar<MenuType<?>> registrar) {
        if (type != null) {
            throw new IllegalStateException("Menu " + modId + ":" + name + " registered twice");
        }
        if (!checked) {
            checked = Menus.checked(this);
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

    /** The tanks, in declaration order (the bucket click ids of the fluid tanks follow it). */
    @ApiStatus.Internal
    public List<Tank<H, ?>> tanks() {
        return tanks;
    }

    /** The cycle actions, in declaration order (their button ids follow it). */
    @ApiStatus.Internal
    public List<Cycle<H, ?>> cycles() {
        return cycles;
    }

    /** The port values, in declaration order (their button ids follow it). */
    @ApiStatus.Internal
    public List<Port<H>> ports() {
        return ports;
    }

    /** The faces node the side config tab shows, when {@link Builder#sideConfig} declared one. */
    @ApiStatus.Internal
    public Optional<Layout.Faces> sideConfig() {
        return Optional.ofNullable(sideConfig);
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
     * A declared tank: its value handle, kind and the getter of the host's tank.
     *
     * @param value
     *            the synced value
     * @param kind
     *            the tank's resource kind
     * @param tank
     *            the host's tank
     * @param buckets
     *            whether clicks with a fluid container fill or drain it: fluid tanks declared with
     *            {@link Builder#tank(Function)}, whose getter returns a {@link FluidTank}
     */
    @ApiStatus.Internal
    public record Tank<H, K>(TankValue value, ResourceKind<K> kind, Function<? super H, ? extends ResourceTank<K>> tank,
            boolean buckets) {
    }

    /**
     * A declared cycle action: its handle and the setter it calls on the server.
     *
     * @param action
     *            the handle
     * @param setter
     *            sets the host's value to a choice
     */
    @ApiStatus.Internal
    public record Cycle<H, E extends Enum<E> & MenuChoice>(CycleAction<E> action,
            BiConsumer<? super H, ? super E> setter) {
        /**
         * Hands the choice with {@code ordinal} to the setter; returns false and does nothing for an ordinal that is no
         * constant of the enum. Server thread.
         */
        public boolean apply(H host, int ordinal) {
            List<E> choices = action.choices();
            if (ordinal < 0 || ordinal >= choices.size()) {
                return false;
            }
            setter.accept(host, choices.get(ordinal));
            return true;
        }
    }

    /**
     * A declared port value: its handle, the getter of the host's port and which of its kinds a faces node shows.
     *
     * @param value
     *            the handle
     * @param port
     *            the host's port
     * @param shown
     *            bit {@code k} set when the kind at position {@code k} of the value is in a faces node of the layout or
     *            the side config tab; clicks on other kinds are ignored
     */
    @ApiStatus.Internal
    public record Port<H>(PortValue value, Function<? super H, ? extends TransferPort> port, int shown) {
        /**
         * Sets {@code face} of the kind at {@code kind} (its position in the value's kinds) to the mode with id
         * {@code mode} on the host's port. Returns false and changes nothing for a kind index, face or mode id out of
         * range, a kind no faces node shows, or a mode the port does not allow for that kind; true otherwise, also when
         * the face already had that mode (nothing changes then). Server thread.
         */
        public boolean apply(H host, int kind, int face, int mode) {
            List<ResourceKind<?>> kinds = value.kinds();
            Optional<PortFaceMode> asked = PortFaceMode.byId(mode);
            if (kind < 0 || kind >= kinds.size() || (shown & (1 << kind)) == 0 || face < 0 || face > 5
                    || asked.isEmpty()) {
                return false;
            }
            TransferPort target = port.apply(host);
            ResourceKind<?> resource = kinds.get(kind);
            if (!target.allowed(resource).contains(asked.get())) {
                return false;
            }
            Direction direction = Direction.from3DDataValue(face);
            if (target.settings().mode(resource, direction) != asked.get()) {
                target.setSettings(target.settings().withMode(resource, direction, asked.get()));
            }
            return true;
        }
    }

    /**
     * Collects a definition inside {@link Menus#define}; every method returns a handle for the layout (or nothing).
     * Usable only inside that call; not thread-safe.
     */
    public static final class Builder<H> {
        private final String modId;
        private final String name;
        private final Class<H> hostClass;
        /** The side tab column beside the panel: gap, tab, gap (the client's {@code SideTabs}). */
        private static final int SIDE_TAB_COLUMN = 2 + 18 + 2;
        /** A flyout's padding and title strip (the client's {@code SideTabs.PAD} and {@code TITLE}). */
        private static final int FLYOUT_PAD = 6;
        private static final int FLYOUT_TITLE = 14;
        private static final PortFaceMode[] MODES = PortFaceMode.values();
        private final MenuValues.Builder<H> values = MenuValues.builder();
        private final List<MenuItems<H>> items = new ArrayList<>();
        private final List<MenuSlots> slots = new ArrayList<>();
        private final List<Tank<H, ?>> tanks = new ArrayList<>();
        private final List<Cycle<H, ?>> cycles = new ArrayList<>();
        private final List<Port<H>> ports = new ArrayList<>();
        private Layout.@Nullable Faces sideConfig;
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
         * client menu builds its empty copy from. The definition keeps a copy of {@code shape} as it is now.
         */
        public MenuItems<H> items(Function<? super H, ItemSlots> getter, ItemSlots.Builder shape) {
            checkOpen();
            MenuItems<H> handle = new MenuItems<>(items.size(), getter, shape.copy());
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
         * tank or empties it into the tank. A fluid that no longer loads shows as unknown with its amount.
         */
        public TankValue tank(Function<? super H, FluidTank> getter) {
            return addTank(ResourceKinds.FLUID, getter, true);
        }

        /**
         * A tank of the host holding resources of {@code kind}, synced; unknown contents show as unknown with their
         * amount. Clicks on it do nothing (only fluid tanks take container clicks); the host's tank must be of
         * {@code kind}, checked when the menu first reads it.
         */
        public <K> TankValue tank(ResourceKind<K> kind, Function<? super H, ? extends ResourceTank<K>> getter) {
            return addTank(Objects.requireNonNull(kind, "kind"), getter, false);
        }

        private <K> TankValue addTank(ResourceKind<K> kind, Function<? super H, ? extends ResourceTank<K>> getter,
                boolean buckets) {
            checkOpen();
            TankValue handle = values.tank(kind, getter);
            tanks.add(new Tank<>(handle, kind, getter, buckets));
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

        /** A whole number, synced for the screen; {@link Layout#value} shows it in a text. */
        public IntValue intValue(ToIntFunction<? super H> getter) {
            checkOpen();
            IntValue handle = values.intValue(getter);
            handles.add(handle);
            return handle;
        }

        /**
         * A server action that sets {@code value} from the screen: a {@link Layout#cycleButton} over the returned
         * handle steps through the enum's constants, and the server calls {@code setter} with the choice a player
         * clicked to (see {@link CycleAction}). The setter runs on the server thread with a constant of the enum, never
         * null; it may refuse a choice by leaving the host as it is, and the screen then keeps showing the host's
         * value. It should save the host ({@code setChanged}) and wake whatever the value controls.
         *
         * @throws IllegalArgumentException
         *             for a value of another definition, or an enum of more than {@link CycleAction#MAX_CHOICES}
         *             constants
         */
        public <E extends Enum<E> & MenuChoice> CycleAction<E> cycle(EnumValue<E> value,
                BiConsumer<? super H, ? super E> setter) {
            checkOpen();
            if (!handles.contains(value)) {
                throw new IllegalArgumentException("Enum value from another menu definition");
            }
            CycleAction<E> handle = new CycleAction<>(cycles.size(), value);
            cycles.add(new Cycle<>(handle, Objects.requireNonNull(setter, "setter")));
            handles.add(handle);
            return handle;
        }

        /**
         * The host's transfer port, synced for {@link Layout#faces} and {@link #sideConfig}: per kind of {@code kinds}
         * its face modes and the modes the kind allows, and the host's facing. The facing is the block state's vanilla
         * {@code BlockStateProperties.HORIZONTAL_FACING} or {@code FACING} property when the host is a block entity
         * whose block has one (other facing properties are not detected), and none otherwise (faces are then named
         * absolutely); {@link #port(Function, Function, ResourceKind...)} takes it from the host instead.
         * <p>
         * A click on a face names the mode it asks for, so a click the server sees twice sets the same mode twice. The
         * server sets it through {@link TransferPort#setSettings} only when the port allows that mode for the kind
         * ({@link TransferPort#allowed}) and ignores every other click; the port's settings listener runs as for a
         * wrench. Clicks on a kind that no faces node shows are ignored too. Kinds are fixed here, since the client
         * menu has no port to ask.
         *
         * @throws IllegalArgumentException
         *             for no kinds, a kind twice or more than {@link PortValue#MAX_KINDS} kinds
         */
        @ApiStatus.Experimental
        public PortValue port(Function<? super H, ? extends TransferPort> port, ResourceKind<?>... kinds) {
            return addPort(port, Builder::facingOf, kinds);
        }

        /**
         * Like {@link #port(Function, ResourceKind...)}, with the facing from {@code facing} (empty for none; must not
         * return null, and should return a kept {@code Optional}: it is read every tick).
         */
        @ApiStatus.Experimental
        public PortValue port(Function<? super H, ? extends TransferPort> port,
                Function<? super H, Optional<Direction>> facing, ResourceKind<?>... kinds) {
            Objects.requireNonNull(facing, "facing");
            return addPort(port, host -> {
                Optional<Direction> direction = facing.apply(host);
                return direction.isPresent() ? direction.get().get3DDataValue() : PortValue.NO_FACING;
            }, kinds);
        }

        private PortValue addPort(Function<? super H, ? extends TransferPort> port, ToIntFunction<? super H> facing,
                ResourceKind<?>[] kinds) {
            checkOpen();
            Objects.requireNonNull(port, "port");
            List<ResourceKind<?>> list = List.of(kinds);
            if (list.isEmpty() || list.size() > PortValue.MAX_KINDS || Set.copyOf(list).size() != list.size()) {
                throw new IllegalArgumentException(
                        "A port value needs 1 to " + PortValue.MAX_KINDS + " distinct kinds, got " + list);
            }
            IntValue facingValue = values.intValue(facing);
            IntValue[] faces = new IntValue[list.size()];
            IntValue[] allowed = new IntValue[list.size()];
            for (int i = 0; i < faces.length; i++) {
                ResourceKind<?> kind = list.get(i);
                faces[i] = values.intValue(host -> port.apply(host).settings().faces(kind));
                allowed[i] = values.intValue(host -> allowedMask(port.apply(host).allowed(kind)));
            }
            PortValue handle = new PortValue(ports.size(), list, facingValue, faces, allowed);
            ports.add(new Port<>(handle, port, 0));
            handles.add(handle);
            return handle;
        }

        /**
         * Adds the standard side config tab: a side tab whose flyout shows {@link Layout#faces} of {@code port} for
         * {@code kinds} (every kind of {@code port} when none are given). At most one per definition. The screen
         * centres the panel, the tab column and the flyout together; the three must fit a GUI of
         * {@link LayoutPlan#FIT_WIDTH} by {@link LayoutPlan#FIT_HEIGHT} side by side (three kinds do beside a 176 wide
         * panel), and the definition reports a problem otherwise.
         *
         * @throws IllegalArgumentException
         *             for a port value of another definition
         * @throws IllegalStateException
         *             when called twice
         */
        @ApiStatus.Experimental
        public void sideConfig(PortValue port, ResourceKind<?>... kinds) {
            checkOpen();
            if (!handles.contains(port)) {
                throw new IllegalArgumentException("Port value from another menu definition");
            }
            if (sideConfig != null) {
                throw new IllegalStateException("Menu " + modId + ":" + name + " has a side config tab already");
            }
            sideConfig = (Layout.Faces) Layout.faces(port, kinds);
        }

        /** The facing of a block entity host, or {@link PortValue#NO_FACING}; read every tick, so no lookups. */
        private static int facingOf(Object host) {
            if (host instanceof BlockEntity entity) {
                BlockState state = entity.getBlockState();
                if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                    return state.getValue(BlockStateProperties.HORIZONTAL_FACING).get3DDataValue();
                }
                if (state.hasProperty(BlockStateProperties.FACING)) {
                    return state.getValue(BlockStateProperties.FACING).get3DDataValue();
                }
            }
            return PortValue.NO_FACING;
        }

        /** {@code allowed} as a bit mask by mode id, without iterating the set (read every tick). */
        private static int allowedMask(Set<PortFaceMode> allowed) {
            int mask = 0;
            for (PortFaceMode mode : MODES) {
                if (allowed.contains(mode)) {
                    mask |= 1 << mode.id();
                }
            }
            return mask;
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

        /** A faces node's kinds must all be its port's. */
        private static List<String> checkFaces(Layout.Faces node) {
            List<String> problems = new ArrayList<>();
            for (ResourceKind<?> kind : node.kinds()) {
                if (node.port().kindIndex(kind) < 0) {
                    problems.add("Faces of port " + node.port().index() + " show kind " + kind.id()
                            + ", which the port value does not declare");
                }
            }
            return problems;
        }

        private void checkOpen() {
            if (done) {
                throw new IllegalStateException("Menu definition " + modId + ":" + name + " already built");
            }
        }

        /**
         * The definition's own checks of {@code plan}: groups placed once with room and sharing no slot, status lines
         * over {@link MenuStatus} enums, handles from here.
         */
        private List<String> checkLayout(LayoutPlan plan) {
            List<String> problems = new ArrayList<>();
            for (int i = 0; i < slots.size(); i++) {
                MenuSlots a = slots.get(i);
                for (int j = i + 1; j < slots.size(); j++) {
                    MenuSlots b = slots.get(j);
                    if (a.items() == b.items() && a.from() < b.to() && b.from() < a.to()) {
                        problems.add("Slots " + a.index() + " and " + b.index() + " share slots of inventory "
                                + a.items().index());
                    }
                }
            }
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
                    case Layout.Status node -> {
                        if (!MenuStatus.class.isAssignableFrom(node.value().type())) {
                            problems.add("Status " + node.value().index() + ": " + node.value().type().getName()
                                    + " does not implement MenuStatus");
                        }
                        yield node.value();
                    }
                    case Layout.Value node -> {
                        IntValue condition = node.shownWhenNonZero();
                        if (condition != null && !handles.contains(condition)) {
                            problems.add("Layout uses a handle from another menu definition: " + node);
                        }
                        yield node.value();
                    }
                    case Layout.CycleButton node -> node.action();
                    case Layout.Faces node -> {
                        problems.addAll(checkFaces(node));
                        yield node.port();
                    }
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
            Layout.Faces side = sideConfig;
            if (side != null) {
                problems.addAll(checkFaces(side));
                int outerWidth = side.width() + 2 * FLYOUT_PAD;
                int outerHeight = side.height() + FLYOUT_TITLE + 2 * FLYOUT_PAD;
                if (plan.panelWidth() + SIDE_TAB_COLUMN + outerWidth > LayoutPlan.FIT_WIDTH
                        || outerHeight > LayoutPlan.FIT_HEIGHT) {
                    problems.add("Side config flyout " + outerWidth + "x" + outerHeight
                            + " beside the panel does not fit " + LayoutPlan.FIT_WIDTH + "x" + LayoutPlan.FIT_HEIGHT);
                }
            }
            return problems;
        }
    }
}
