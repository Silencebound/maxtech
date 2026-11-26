package dev.silencebound.maxtech.storage.neoforge.compat.curios;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Which Curios slot each carried gadget goes into. Curios ships the preset slot types ({@code belt}, {@code charm} and
 * the others) but gives a player none of them until a mod asks; Storage's data asks for the two it uses
 * ({@code data/maxtech_storage/curios/entities/storage_gadgets.json}) and puts its items into Curios' preset item tags:
 * the restock belt into {@code curios:belt}; the wireless terminal, crafting tablet, logic remote, pocket uplink and
 * portable cell into {@code curios:charm}, Curios' slot for gadgets not tied to a body part. Plain data, no Curios
 * classes: datagen and {@link CuriosCarriedSource} share it.
 */
public final class CuriosSlots {
    /** Curios' mod id. */
    public static final String MOD_ID = "curios";
    /** The belt slot type, for the restock belt. */
    public static final String BELT = "belt";
    /** The charm slot type, for the handheld gadgets. */
    public static final String CHARM = "charm";
    /** Curios' universal slot type, which takes any curio when a pack or another mod gives a player one. */
    public static final String CURIO = "curio";
    /** The slot types {@link CuriosCarriedSource} looks in, in order. */
    static final String[] SEARCHED = {BELT, CHARM, CURIO};

    public static final TagKey<Item> BELT_TAG = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath(MOD_ID, BELT));
    public static final TagKey<Item> CHARM_TAG = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath(MOD_ID, CHARM));

    private CuriosSlots() {
    }
}
