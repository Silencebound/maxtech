package dev.silencebound.maxtech.storage.neoforge;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.research.ResearchContent;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.LootModifier;

/**
 * Puts a research skip token into a generated structure chest with the chance {@code research.skipTokens.lootChance}
 * (owner decision 2026-10-03). It applies to vanilla's chest loot tables ({@code minecraft:chests/...}: dungeons,
 * mineshafts, strongholds, temples and the rest), read from the table being rolled, so no list of tables needs keeping.
 */
public final class SkipTokenLootModifier extends LootModifier {
    static final MapCodec<SkipTokenLootModifier> CODEC = RecordCodecBuilder
            .mapCodec(instance -> codecStart(instance).apply(instance, SkipTokenLootModifier::new));

    SkipTokenLootModifier(LootItemCondition[] conditions, int priority) {
        super(conditions, priority);
    }

    /** The modifier as datagen writes it: no conditions of its own, default priority. */
    public static SkipTokenLootModifier create() {
        return new SkipTokenLootModifier(new LootItemCondition[0], DEFAULT_PRIORITY);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        Identifier table = context.getQueriedLootTableId();
        if (table.getNamespace().equals(Identifier.DEFAULT_NAMESPACE) && table.getPath().startsWith("chests/")
                && context.getRandom().nextDouble() < StorageServerConfig.RESEARCH_TOKEN_LOOT_CHANCE.getAsDouble()) {
            generatedLoot.add(new ItemStack(ResearchContent.SKIP_TOKEN.get()));
        }
        return generatedLoot;
    }

    @Override
    public MapCodec<? extends LootModifier> codec() {
        return CODEC;
    }
}
