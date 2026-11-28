package dev.silencebound.maxtech.farms.hub;

import dev.silencebound.maxtech.farms.MaxtechFarms;
import dev.silencebound.maxtech.farms.sampler.SamplerItem;
import dev.silencebound.maxtech.lib.api.menu.EnergyValue;
import dev.silencebound.maxtech.lib.api.menu.EnumValue;
import dev.silencebound.maxtech.lib.api.menu.IntValue;
import dev.silencebound.maxtech.lib.api.menu.Layout;
import dev.silencebound.maxtech.lib.api.menu.MenuDefinition;
import dev.silencebound.maxtech.lib.api.menu.MenuItems;
import dev.silencebound.maxtech.lib.api.menu.MenuSlots;
import dev.silencebound.maxtech.lib.api.menu.Menus;
import dev.silencebound.maxtech.lib.api.menu.ProgressValue;
import dev.silencebound.maxtech.lib.api.menu.TankValue;
import dev.silencebound.maxtech.lib.api.style.AccentTheme;
import net.minecraft.world.item.ItemStack;

/**
 * The lab hub's screen: nutrient tank, MU bar, fuel slot over its flame, sampler slot with its charge, the 27 output
 * slots beside them (take only), the status line and the player's inventory. Shift-clicked stacks go to the fuel slot,
 * then the sampler slot, never into the output. The handles are public for tests.
 */
public final class HubMenu {
    /** Farms' mint on the Accent look (Farms PRD §12); the gauge runs from the mint to its dark tone. */
    public static final AccentTheme THEME = new AccentTheme(0xFF5FE08A, 0xFF1D7A45, 0xFF5FE08A, 0xFF1D7A45, 0xFFB8EFC9,
            0xFF5FE08A);

    /** Lang key of the sampler line, "Sampler: %s%%". */
    public static final String SAMPLER_KEY = "gui.maxtech_farms.lab_hub.sampler";
    /** Lang key of the label over the output slots. */
    public static final String OUTPUT_KEY = "gui.maxtech_farms.lab_hub.output";
    /** Lang key of the label over the player's inventory. */
    public static final String INVENTORY_KEY = "gui.maxtech_farms.lab_hub.inventory";

    private static final int MACHINE_HEIGHT = 3 * Layout.SLOT;
    /** Tank, energy bar, fuel column and sampler slot with the gaps between them, left of the output. */
    private static final int MACHINE_WIDTH = Layout.SLOT + Layout.GAP + Layout.ENERGY_WIDTH + Layout.GAP + Layout.SLOT
            + Layout.GAP + Layout.SLOT;

    public static final TankValue TANK;
    public static final EnergyValue ENERGY;
    public static final ProgressValue BURN;
    public static final IntValue SAMPLER_PERCENT;
    /** 1 while a sampler is in its slot, else 0: the "Sampler: N%" line shows only then. */
    public static final IntValue SAMPLER_PRESENT;
    public static final EnumValue<HubStatus> STATUS;
    public static final MenuSlots FUEL;
    public static final MenuSlots OUTPUT;
    public static final MenuSlots SAMPLER;
    public static final MenuDefinition<LabHubBlockEntity> MENU;

    static {
        Handles handles = new Handles();
        MENU = Menus.define(MaxtechFarms.MOD_ID, "lab_hub", LabHubBlockEntity.class, m -> {
            MenuItems<LabHubBlockEntity> items = m.items(hub -> hub.storage().slots(), HubStorage.SHAPE);
            handles.fuel = m.slots(items, HubStorage.FUEL, HubStorage.FUEL + 1);
            handles.output = m.slots(items, HubStorage.OUTPUT_FIRST, HubStorage.SAMPLER);
            handles.sampler = m.slots(items, HubStorage.SAMPLER, HubStorage.SIZE);
            handles.tank = m.tank(hub -> hub.storage().tank());
            handles.energy = m.energy(LabHubBlockEntity::energy);
            handles.burn = m.progress(hub -> hub.burner().burnLeft(), hub -> hub.burner().burnTotal());
            handles.samplerPercent = m.intValue(hub -> samplerPercent(hub.storage().sampler()));
            handles.samplerPresent = m.intValue(hub -> hub.storage().sampler().isEmpty() ? 0 : 1);
            handles.status = m.enumValue(HubStatus.class, HubStatus::of);
            m.shiftClick(handles.fuel, handles.sampler);
            m.theme(THEME);
            m.layout(Layout.column(
                    Layout.column(Layout.SMALL_GAP,
                            Layout.row(Layout.spacer(MACHINE_WIDTH, Layout.TEXT_HEIGHT),
                                    Layout.label(OUTPUT_KEY, 9 * Layout.SLOT)),
                            Layout.row(Layout.tank(handles.tank, Layout.SLOT, MACHINE_HEIGHT),
                                    Layout.energy(handles.energy, MACHINE_HEIGHT),
                                    Layout.column(Layout.SMALL_GAP, Layout.slots(handles.fuel, 1, 1),
                                            Layout.progress(handles.burn, Layout.ProgressShape.FLAME)),
                                    Layout.slots(handles.sampler, 1, 1), Layout.slots(handles.output, 9, 3))),
                    Layout.row(Layout.status(handles.status, 172),
                            Layout.value(handles.samplerPercent, SAMPLER_KEY, 66, handles.samplerPresent)),
                    Layout.column(Layout.SMALL_GAP, Layout.label(INVENTORY_KEY, Layout.PLAYER_INVENTORY_WIDTH),
                            Layout.playerInventory())));
        });
        TANK = handles.tank;
        ENERGY = handles.energy;
        BURN = handles.burn;
        SAMPLER_PERCENT = handles.samplerPercent;
        SAMPLER_PRESENT = handles.samplerPresent;
        STATUS = handles.status;
        FUEL = handles.fuel;
        OUTPUT = handles.output;
        SAMPLER = handles.sampler;
    }

    private HubMenu() {
    }

    /**
     * The sampler's charge in whole percent, rounded down so 100 means full; 0 without a sampler (the line is hidden
     * then, by {@link #SAMPLER_PRESENT}).
     */
    static int samplerPercent(ItemStack sampler) {
        long capacity = SamplerItem.capacity();
        if (sampler.isEmpty() || capacity <= 0) {
            return 0;
        }
        return (int) Math.min(100, SamplerItem.charge(sampler) * 100 / capacity);
    }

    /** The handles the definition's body makes, carried out of the lambda into the static fields. */
    @SuppressWarnings("NullAway.Init") // the body sets every field before the static initialiser reads them
    private static final class Handles {
        TankValue tank;
        EnergyValue energy;
        ProgressValue burn;
        IntValue samplerPercent;
        IntValue samplerPresent;
        EnumValue<HubStatus> status;
        MenuSlots fuel;
        MenuSlots output;
        MenuSlots sampler;
    }
}
