package dev.silencebound.maxtech.storage.neoforge;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.research.source.SourceContent;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.LootModifier;

/**
 * Puts research notes into a generated structure chest with the chance {@code research.sources.notes.lootChance}, in
 * every vanilla chest loot table ({@code minecraft:chests/...}), as the skip token's modifier does.
 */
public final class ResearchNotesLootModifier extends LootModifier {
    static final MapCodec<ResearchNotesLootModifier> CODEC = RecordCodecBuilder
            .mapCodec(instance -> codecStart(instance).apply(instance, ResearchNotesLootModifier::new));

    ResearchNotesLootModifier(LootItemCondition[] conditions, int priority) {
        super(conditions, priority);
    }

    /** The modifier as datagen writes it: no conditions of its own, default priority. */
    public static ResearchNotesLootModifier create() {
        return new ResearchNotesLootModifier(new LootItemCondition[0], DEFAULT_PRIORITY);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        Identifier table = context.getQueriedLootTableId();
        if (table.getNamespace().equals(Identifier.DEFAULT_NAMESPACE) && table.getPath().startsWith("chests/")
                && context.getRandom().nextDouble() < StorageServerConfig.NOTES_LOOT_CHANCE.getAsDouble()) {
            generatedLoot.add(new ItemStack(SourceContent.RESEARCH_NOTES.get()));
        }
        return generatedLoot;
    }

    @Override
    public MapCodec<? extends LootModifier> codec() {
        return CODEC;
    }
}
