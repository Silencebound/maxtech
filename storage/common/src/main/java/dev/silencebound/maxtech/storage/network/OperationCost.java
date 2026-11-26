package dev.silencebound.maxtech.storage.network;

/**
 * Energy arithmetic for storage operations (PRD 7.2: devices pay energy per operation). An operation costs a fixed
 * amount plus a share per item, or per started portion of a fluid; efficiency cores reduce the whole cost. Pure
 * functions, any thread.
 */
public final class OperationCost {
    private OperationCost() {
    }

    /**
     * FE an operation moving {@code amount} costs: {@code perOperation} plus {@code perUnit} for each item, or for each
     * started {@code fluidMbPerUnit} mB of a fluid. Nothing moved costs nothing.
     */
    public static long energyCost(boolean fluid, long amount, long perOperation, long perUnit, int fluidMbPerUnit) {
        if (amount <= 0) {
            return 0;
        }
        long units = fluid ? (amount + fluidMbPerUnit - 1) / Math.max(1, fluidMbPerUnit) : amount;
        return perOperation + units * perUnit;
    }

    /**
     * The most an operation can move when {@code stored} FE is all there is to pay {@link #energyCost} with: items, or
     * whole {@code fluidMbPerUnit} portions of mB for a fluid. 0 when not even the smallest operation is affordable;
     * {@link Long#MAX_VALUE} when moving costs nothing per unit and the operation itself is paid.
     */
    public static long affordable(boolean fluid, long stored, long perOperation, long perUnit, int fluidMbPerUnit) {
        if (stored < perOperation + perUnit) {
            return 0;
        }
        if (perUnit <= 0) {
            return Long.MAX_VALUE;
        }
        long units = (stored - perOperation) / perUnit;
        return fluid ? units * Math.max(1, fluidMbPerUnit) : units;
    }

    /**
     * {@code cost} reduced to {@code percent} of itself (efficiency cores), rounded up, so an operation that costs
     * anything still costs at least 1 FE. {@code percent} of 100 or more leaves it unchanged.
     */
    public static long discounted(long cost, int percent) {
        if (percent >= 100 || cost <= 0) {
            return cost;
        }
        long p = Math.max(0, percent);
        long scaled = cost > Long.MAX_VALUE / 100 ? cost / 100 * p : (cost * p + 99) / 100;
        return Math.max(1, scaled);
    }

    /**
     * Like {@link #affordable(boolean, long, long, long, int)} for costs {@link #discounted} to {@code percent}: the
     * most {@code stored} FE pays for once every cost is reduced.
     */
    public static long affordable(boolean fluid, long stored, long perOperation, long perUnit, int fluidMbPerUnit,
            int percent) {
        if (percent >= 100) {
            return affordable(fluid, stored, perOperation, perUnit, fluidMbPerUnit);
        }
        long p = Math.max(1, percent);
        // ceil(c * p / 100) <= stored holds exactly when c <= floor(stored * 100 / p), for whole costs c.
        long effective = stored > Long.MAX_VALUE / 100 ? Long.MAX_VALUE : stored * 100 / p;
        return affordable(fluid, effective, perOperation, perUnit, fluidMbPerUnit);
    }

    /**
     * FE to charge for an operation costing {@code raw} FE before efficiency cores, when the network's operations this
     * tick already cost {@code rawBefore} before the discount and were charged {@code paidBefore}: the tick's
     * discounted total, {@code ceil((rawBefore + raw) * percent / 100)}, less what was charged. Summed over a tick the
     * charges are that tick's total cost reduced by the full percentage, rounded up once, so a core saves what it says
     * even at a few FE an operation. Never negative; {@code raw} itself at {@code percent} 100 or more.
     */
    public static long tickCharge(long rawBefore, long paidBefore, long raw, int percent) {
        if (raw <= 0) {
            return 0;
        }
        if (percent >= 100) {
            return raw;
        }
        long total = rawBefore + raw;
        long p = Math.max(0, percent);
        long discounted = total > Long.MAX_VALUE / 100 ? total / 100 * p : (total * p + 99) / 100;
        return Math.max(0, discounted - paidBefore);
    }

    /** Whether {@code stored} is below {@code percent} of {@code capacity}, without overflowing for huge stores. */
    public static boolean belowPercent(long stored, long capacity, int percent) {
        if (percent <= 0) {
            return false;
        }
        long p = Math.min(100, percent);
        long threshold = capacity / 100 * p + capacity % 100 * p / 100;
        return stored < threshold;
    }
}
