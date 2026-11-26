package dev.silencebound.maxtech.storage.neoforge.datagen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.logic.remote.RemoteContent;
import dev.silencebound.maxtech.storage.neoforge.compat.curios.CuriosSlots;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import dev.silencebound.maxtech.storage.tool.belt.BeltContent;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletContent;
import dev.silencebound.maxtech.storage.tool.uplink.UplinkContent;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.ItemTagsProvider;
import net.neoforged.neoforge.common.data.JsonCodecProvider;

/**
 * Storage's Curios data ({@link CuriosSlots}): its gadgets in Curios' preset item tags, and the belt and charm slots
 * given to players. Both are plain data that only Curios reads, so they ship whether or not Curios is installed.
 */
final class CuriosSlotDatagen {
    private CuriosSlotDatagen() {
    }

    /**
     * Curios' entity slot file: {@code entities} gain {@code slots} (Curios' {@code IEntitiesData.Entry}, verified in
     * the 15.0.0+26.1.2 sources: {@code replace} is optional and false by default).
     */
    record EntitySlots(List<String> entities, List<String> slots) {
        static final Codec<EntitySlots> CODEC = RecordCodecBuilder.create(instance -> instance
                .group(Codec.STRING.listOf().fieldOf("entities").forGetter(EntitySlots::entities),
                        Codec.STRING.listOf().fieldOf("slots").forGetter(EntitySlots::slots))
                .apply(instance, EntitySlots::new));
    }

    /** Gives players the belt and charm slots ({@code data/maxtech_storage/curios/entities/}). */
    static final class Entities extends JsonCodecProvider<EntitySlots> {
        Entities(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
            super(output, PackOutput.Target.DATA_PACK, "curios/entities", EntitySlots.CODEC, registries,
                    MaxtechStorage.MOD_ID);
        }

        @Override
        protected void gather() {
            unconditional(MaxtechStorage.id("storage_gadgets"),
                    new EntitySlots(List.of("minecraft:player"), List.of(CuriosSlots.BELT, CuriosSlots.CHARM)));
        }

        @Override
        public String getName() {
            return "Curios entity slots";
        }
    }

    /** The gadgets in {@code curios:belt} and {@code curios:charm}. */
    static final class Tags extends ItemTagsProvider {
        Tags(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
            super(output, registries, MaxtechStorage.MOD_ID);
        }

        @Override
        protected void addTags(HolderLookup.Provider registries) {
            tag(CuriosSlots.BELT_TAG).add(BeltContent.RESTOCK_BELT.get());
            tag(CuriosSlots.CHARM_TAG).add(SignalContent.WIRELESS_TERMINAL.get(), CraftingTabletContent.TABLET.get(),
                    RemoteContent.REMOTE.get(), UplinkContent.POCKET_UPLINK.get(),
                    CellContent.PORTABLE_STORAGE_CELL.get());
        }

        @Override
        public String getName() {
            return "Curios slot tags";
        }
    }
}
