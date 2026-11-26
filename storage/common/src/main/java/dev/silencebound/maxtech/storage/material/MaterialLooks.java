package dev.silencebound.maxtech.storage.material;

import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import net.minecraft.resources.Identifier;

/**
 * How Storage's generic materials look (owner decision 2026-10-15): a vanilla item texture of the same form, tinted at
 * runtime by the item definition's constant tint, so they read as Minecraft materials and Storage ships no Mojang
 * pixels (the models name the vanilla textures by id). The tints are the item-redraw mockup's material colours, nudged
 * where two materials of one form came out too alike (niobium lavender, tantalum dark steel, kerf light grey).
 */
public final class MaterialLooks {
    /** Lumen quartz and its dust. */
    static final int LUMEN = 0x7FE0F0;
    static final int EMBER = 0xF5A050;
    static final int DATA = 0x78A8F5;
    static final int VOID = 0x9A6AD0;
    static final int NIOBIUM = 0xB4B0E6;
    static final int TANTALUM = 0x8C94AA;
    static final int SIGNAL = 0xF07A52;
    /** Silicon kerf, the silicon dust. */
    static final int SILICON = 0xA8B4C4;
    /** Raw tantalite, the rock it is broken from. */
    static final int ROCK = 0xC09080;

    /** A material form and the vanilla texture it is drawn from. */
    public enum Form {
        /**
         * Sugar: the lightest vanilla dust and nearly colourless, so a multiplied tint keeps its shading; glowstone and
         * redstone dust are coloured and gunpowder too dark.
         */
        DUST("dust", "item/sugar"), INGOT("ingot", "item/iron_ingot"),
        /**
         * Nether quartz: a pale crystal cluster that takes any tint; the amethyst shard is saturated purple, so every
         * tint but violet turns muddy on it.
         */
        GEM("gem", "item/quartz"), RAW("raw", "item/raw_iron");

        private final String name;
        private final String texture;

        Form(String name, String texture) {
            this.name = name;
            this.texture = texture;
        }

        /** The flat model every item of this form shares, e.g. {@code maxtech_storage:item/material/dust}. */
        public Identifier model() {
            return MaxtechStorage.id("item/material/" + name);
        }

        /** The vanilla texture the form is drawn from. */
        public Identifier texture() {
            return Identifier.withDefaultNamespace(texture);
        }
    }

    private MaterialLooks() {
    }

    /** The model of a material of {@code form}, tinted {@code rgb}. */
    static ItemModel tinted(Form form, int rgb) {
        return ItemModel.tintedShared(form.model(), form.texture(), rgb);
    }
}
