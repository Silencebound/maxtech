package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperBlockEntity;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperWorker;
import dev.silencebound.maxtech.storage.crafting.stock.StockRule;
import dev.silencebound.maxtech.storage.logic.LogicContext;
import dev.silencebound.maxtech.storage.logic.WatchKind;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.List;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Logic's view of the stock keepers (M7L2 chunk 15). Reads come from a labelled keeper's own state (its rules, and
 * whether each is filling, has a job out or is short); edits go through {@link StockKeeperWorker#setRule}, the call
 * behind the keeper's screen, so they are saved, synced and clamped like a player's. The Stock Keeper stays the one
 * stock system: logic switches and tunes rules that exist and never adds one. Only the first
 * {@code crafting.stockKeeperRules} rules count, the ones the keeper runs. Server thread only.
 */
final class StockLogic {
    private StockLogic() {
    }

    /**
     * The {@link WatchKind#STOCK} value {@code value} for the keepers labelled {@code labelKey}: the first matching
     * rule's amount, or the {@code STOCK_*} bits over every matching rule. 0 when no labelled keeper has a matching
     * rule. O(labelled devices times rules); allocates nothing.
     */
    static long read(NetworkState network, String labelKey, @Nullable LogicKey item, long value) {
        if (labelKey.isEmpty()) {
            return 0;
        }
        List<NetworkDevice> labelled = network.labels().devicesByKey(labelKey);
        int slots = StockKeeperWorker.ruleSlots();
        long flags = 0;
        long first = 0;
        for (int d = 0; d < labelled.size(); d++) {
            if (!(labelled.get(d) instanceof StockKeeperBlockEntity keeper)) {
                continue;
            }
            StockKeeperWorker worker = keeper.worker();
            for (int i = 0; i < slots; i++) {
                StockRule rule = worker.rule(i);
                if (!matches(rule, item)) {
                    continue;
                }
                if ((flags & WatchKind.STOCK_FOUND) == 0) {
                    flags |= WatchKind.STOCK_FOUND | (rule.switchedOff() ? 0 : WatchKind.STOCK_ON);
                    first = value == WatchKind.STOCK_KEEP
                            ? rule.keep()
                            : value == WatchKind.STOCK_RESTART ? rule.restart() : rule.batch();
                }
                flags |= (worker.outstanding(i) ? WatchKind.STOCK_RUNNING : 0)
                        | (worker.filling(i) ? WatchKind.STOCK_FILLING : 0)
                        | (worker.isShort(i) ? WatchKind.STOCK_SHORT : 0);
            }
        }
        return value == WatchKind.STOCK_FLAGS ? flags : first;
    }

    /** Whether {@code rule} names a key and it is {@code item}, or in it for a tag; any key when null. */
    private static boolean matches(StockRule rule, @Nullable LogicKey item) {
        return rule.key().isPresent() && (item == null || LogicWorker.matches(rule.key().get(), item));
    }

    /** Applies {@code edit} to each of {@code keeper}'s rules for {@code item} (every used rule when null). */
    static int edit(StockKeeperWorker keeper, @Nullable LogicKey item, UnaryOperator<StockRule> edit) {
        int slots = StockKeeperWorker.ruleSlots();
        int edited = 0;
        for (int i = 0; i < slots; i++) {
            StockRule rule = keeper.rule(i);
            if (!matches(rule, item)) {
                continue;
            }
            edited++;
            keeper.setRule(i, edit.apply(rule));
        }
        return edited;
    }

    /**
     * {@code rule} with one amount set, as the keeper's screen sets it: a keep amount that the restart threshold sat at
     * takes the threshold along, and a threshold stays at most keep.
     */
    static StockRule withAmount(StockRule rule, int field, long amount) {
        return switch (field) {
            case LogicContext.STOCK_RESTART -> rule.withRestart(amount);
            case LogicContext.STOCK_BATCH -> rule.withBatch(amount);
            default -> rule.withKeep(amount);
        };
    }

    /** The word an action line and its log use for {@code field}. */
    static String fieldName(int field) {
        return switch (field) {
            case LogicContext.STOCK_RESTART -> "restart";
            case LogicContext.STOCK_BATCH -> "batch";
            default -> "keep";
        };
    }

    /** Whether {@code device} is a stock keeper, for binding. */
    static @Nullable StockKeeperWorker keeper(NetworkDevice device) {
        return device instanceof StockKeeperBlockEntity keeper ? keeper.worker() : null;
    }
}
