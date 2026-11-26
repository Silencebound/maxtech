package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.criterion.InventoryChangeTrigger;
import net.minecraft.advancements.criterion.ItemPredicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.advancements.AdvancementProvider;
import net.minecraft.data.advancements.AdvancementSubProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/**
 * Storage's advancement tree: one advancement per step of the manual's guided progress (the {@code progress}
 * frontmatter of the guide pages), in the same order and as one chain. Every step is "have the item in the inventory",
 * the same test the guide makes, so vanilla's {@code inventory_changed} is enough and no custom trigger exists. The
 * first step is the tree's root. A GameTest checks this list against the guide.
 */
public final class StorageAdvancements implements AdvancementSubProvider {
    /**
     * One step: the guide step number, the item's path (also the advancement's id under {@code guide/}), and the
     * English title and description.
     */
    public record Entry(int step, String item, String title, String description) {
        /** The advancement's id. */
        public Identifier id() {
            return MaxtechStorage.id("guide/" + item);
        }

        /** The lang key of the title. */
        public String titleKey() {
            return key("title");
        }

        /** The lang key of the description. */
        public String descriptionKey() {
            return key("description");
        }

        private String key(String part) {
            return "advancements." + MaxtechStorage.MOD_ID + ".guide." + item + "." + part;
        }
    }

    /** The steps in guide order; titles follow the guide's. */
    public static final List<Entry> STEPS = List.of(
            new Entry(10, "fuel_generator", "Build a first network", "Pick up a fuel generator to power your network."),
            new Entry(20, "storage_cable_tier_1", "Lay storage cable", "Pick up storage cable to link your devices."),
            new Entry(30, "small_storage_cell", "Store items in a cell", "Pick up a storage cell to hold items."),
            new Entry(40, "storage_terminal", "Open a terminal", "Pick up a storage terminal to reach your items."),
            new Entry(50, "storage_controller", "Add a controller",
                    "Pick up a storage controller to run the network."));

    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("gui/advancements/backgrounds/stone");

    private StorageAdvancements() {
    }

    /** Registers the provider for {@code runData}. */
    static AdvancementProvider provider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        return new AdvancementProvider(output, registries, List.of(new StorageAdvancements()));
    }

    @Override
    public void generate(HolderLookup.Provider registries, Consumer<AdvancementHolder> output) {
        HolderLookup.RegistryLookup<Item> items = registries.lookupOrThrow(Registries.ITEM);
        AdvancementHolder parent = null;
        for (int i = 0; i < STEPS.size(); i++) {
            Entry entry = STEPS.get(i);
            Item item = items.getOrThrow(ResourceKey.create(Registries.ITEM, MaxtechStorage.id(entry.item()))).value();
            Advancement.Builder builder = Advancement.Builder.advancement();
            if (parent != null) {
                builder.parent(parent);
            }
            AdvancementType frame = i == STEPS.size() - 1 ? AdvancementType.GOAL : AdvancementType.TASK;
            builder.display(item, Component.translatable(entry.titleKey()),
                    Component.translatable(entry.descriptionKey()), parent == null ? BACKGROUND : null, frame, true,
                    true, false);
            builder.addCriterion("has_" + entry.item(),
                    InventoryChangeTrigger.TriggerInstance.hasItems(ItemPredicate.Builder.item().of(items, item)));
            parent = builder.save(output, entry.id().toString());
        }
    }
}
