package dev.silencebound.maxtech.lib.content;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * The {@code c:} item tags that recipes declared in common use for non-material inputs. NeoForge's {@code Tags} holds
 * the same tags but is outside the packages common may import (ADR-0002). Material inputs go through
 * {@code MaterialDatagen} instead.
 */
public final class ConventionTags {
    public static final TagKey<Item> GLASS_BLOCKS = tag("glass_blocks");
    public static final TagKey<Item> REDSTONE_DUSTS = tag("dusts/redstone");
    public static final TagKey<Item> WOODEN_RODS = tag("rods/wooden");
    public static final TagKey<Item> NORMAL_BRICKS = tag("bricks/normal");
    public static final TagKey<Item> DIAMOND_GEMS = tag("gems/diamond");
    public static final TagKey<Item> REDSTONE_STORAGE_BLOCKS = tag("storage_blocks/redstone");
    public static final TagKey<Item> CHESTS = tag("chests");
    public static final TagKey<Item> FURNACES = tag("player_workstations/furnaces");
    public static final TagKey<Item> STONES = tag("stones");

    private ConventionTags() {
    }

    private static TagKey<Item> tag(String path) {
        return TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("c", path));
    }
}
