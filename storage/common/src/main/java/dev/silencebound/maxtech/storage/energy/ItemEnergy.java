package dev.silencebound.maxtech.storage.energy;

import dev.silencebound.maxtech.lib.api.energy.FeRate;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;
// PLATFORM-LEAK: carried items show their MU to chargers as NeoForge's FE item capability (MLE).
import net.neoforged.neoforge.transfer.access.ItemAccess;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.energy.ItemAccessEnergyHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * Energy in Storage's items (MLE item 4): the carried items' charge (portable cell, wireless terminal, crafting tablet,
 * logic remote, goggles, cable layer) and the energy a dropped controller or energy cell carries. Items saved before
 * the move to MaxTech Units hold FE in the same components; an item counts as MU once it carries
 * {@link ModDataComponents#ENERGY_IN_MU}, which every write here sets. Reading an item without it converts its FE at
 * Lib's current rate, rounded down (the fraction of one MU is lost, only at a rate other than 1:1), so an item is
 * converted when it is first written after the update, exactly once. Any thread for reads; writes on the item's owner
 * thread.
 */
public final class ItemEnergy {
    private ItemEnergy() {
    }

    /** MU in {@code components} under {@code type}, converting an item from before MLE. */
    public static long get(DataComponentGetter components, DataComponentType<Long> type) {
        Long raw = components.get(type);
        if (raw == null) {
            return 0;
        }
        return Boolean.TRUE.equals(components.get(ModDataComponents.ENERGY_IN_MU.get())) ? raw : toMu(raw);
    }

    /** MU in {@code components} under the int component {@code type}, converting an item from before MLE. */
    public static int getInt(DataComponentGetter components, DataComponentType<Integer> type) {
        Integer raw = components.get(type);
        if (raw == null) {
            return 0;
        }
        return Boolean.TRUE.equals(components.get(ModDataComponents.ENERGY_IN_MU.get()))
                ? raw
                : (int) Math.min(Integer.MAX_VALUE, toMu(raw));
    }

    /** Sets {@code mu} MU under {@code type} and marks the item as holding MU. */
    public static void set(ItemStack stack, DataComponentType<Long> type, long mu) {
        stack.set(type, mu);
        stack.set(ModDataComponents.ENERGY_IN_MU.get(), true);
    }

    /** Sets {@code mu} MU under the int component {@code type} and marks the item as holding MU. */
    public static void setInt(ItemStack stack, DataComponentType<Integer> type, int mu) {
        stack.set(type, mu);
        stack.set(ModDataComponents.ENERGY_IN_MU.get(), true);
    }

    /**
     * The FE item capability of a carried item: its int component {@code type} in MU, filled by chargers through Lib's
     * FE rate, never drained by them (the item spends its charge per operation itself).
     */
    public static EnergyHandler chargeOnly(ItemAccess access, DataComponentType<Integer> type, int capacity,
            int maxInput) {
        return FeView.input(new MuItemHandler(access, type, capacity, maxInput), ItemEnergy::nothing);
    }

    private static void nothing() {
    }

    private static long toMu(long fe) {
        FeRate rate = ServerConfig.feRate();
        return rate.toMu(fe);
    }

    /** NeoForge's item energy handler over an MU component, converting an item from before MLE on its first write. */
    private static final class MuItemHandler extends ItemAccessEnergyHandler {
        MuItemHandler(ItemAccess access, DataComponentType<Integer> type, int capacity, int maxInput) {
            super(access, type, Math.max(0, capacity), Math.max(0, maxInput), 0);
        }

        @Override
        protected int getAmountFrom(ItemResource accessResource) {
            if (!accessResource.is(validItem)) {
                return 0;
            }
            int raw = accessResource.getOrDefault(energyComponent, 0);
            return accessResource.getOrDefault(ModDataComponents.ENERGY_IN_MU.get(), false)
                    ? raw
                    : (int) Math.min(Integer.MAX_VALUE, toMu(raw));
        }

        @Override
        protected ItemResource update(ItemResource accessResource, int newAmount) {
            return accessResource.with(energyComponent, newAmount).with(ModDataComponents.ENERGY_IN_MU.get(), true);
        }
    }
}
