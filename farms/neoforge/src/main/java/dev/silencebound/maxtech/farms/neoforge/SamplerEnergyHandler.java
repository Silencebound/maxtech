package dev.silencebound.maxtech.farms.neoforge;

import dev.silencebound.maxtech.farms.registry.FarmsContent;
import dev.silencebound.maxtech.farms.sampler.SamplerItem;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetworks;
import dev.silencebound.maxtech.lib.api.energy.FeRate;
import net.neoforged.neoforge.transfer.TransferPreconditions;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * The sampler's MU as NeoForge's FE item capability, at Lib's FE rate, so other mods' chargers fill it. Insert only:
 * the sampler spends its charge per sample itself. FE that would not make a whole MU is refused, never kept, so no rate
 * creates energy.
 */
final class SamplerEnergyHandler implements EnergyHandler {
    private final ItemAccess access;

    SamplerEnergyHandler(ItemAccess access) {
        this.access = access;
    }

    @Override
    public long getAmountAsLong() {
        ItemResource resource = access.getResource();
        if (!isSampler(resource)) {
            return 0;
        }
        return EnergyNetworks.feRate().toFe(SamplerItem.charge(resource)) * access.getAmount();
    }

    @Override
    public long getCapacityAsLong() {
        ItemResource resource = access.getResource();
        if (!isSampler(resource)) {
            return 0;
        }
        return EnergyNetworks.feRate().toFe(SamplerItem.capacity()) * access.getAmount();
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        ItemResource resource = access.getResource();
        int count = access.getAmount();
        if (count == 0 || !isSampler(resource)) {
            return 0;
        }
        FeRate rate = EnergyNetworks.feRate();
        long charge = SamplerItem.charge(resource);
        long mu = Math.min(rate.toMu(amount / count), SamplerItem.capacity() - charge);
        if (mu <= 0) {
            return 0;
        }
        // The FE those MU are worth, rounded up: never more than offered, since mu came from rounding down.
        long fe = (rate.unitsOfMu(mu) + rate.mu() - 1) / rate.mu();
        ItemResource charged = resource.with(FarmsContent.SAMPLER_CHARGE.get(), charge + mu);
        return (int) (fe * access.exchange(charged, count, transaction));
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        return 0;
    }

    private static boolean isSampler(ItemResource resource) {
        return resource.is(FarmsContent.SAMPLER.get());
    }
}
