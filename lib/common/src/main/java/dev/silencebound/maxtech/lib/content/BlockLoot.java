package dev.silencebound.maxtech.lib.content;

import java.util.function.Supplier;
import net.minecraft.world.level.storage.loot.LootTable;

/** A block's loot table for datagen. A block with no loot declared gets no table. */
public sealed interface BlockLoot {
    /** The block drops itself. */
    BlockLoot SELF = new Self();

    /** A hand-built table; {@code table} runs only in datagen. */
    static BlockLoot table(Supplier<LootTable.Builder> table) {
        return new Table(table);
    }

    record Self() implements BlockLoot {
    }

    record Table(Supplier<LootTable.Builder> table) implements BlockLoot {
    }
}
