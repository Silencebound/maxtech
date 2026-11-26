package dev.silencebound.maxtech.storage.smartlabel;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.util.StringRepresentable;

/**
 * What a smart label shows. The ids are saved in the label and its item and must not change.
 */
public enum LabelMode implements StringRepresentable {
    /** One item and its count: in the network the label's block belongs to, else in that block's own inventory. */
    ITEM("item", 0, "Item count"),
    /** How full the block the label sits on is: items, else fluids, else energy, else the network's storage. */
    FILL("fill", 1, "Fill level"),
    /** Whether the machine the label sits on works, waits or is stuck (its pattern provider or its lit state). */
    MACHINE("machine", 2, "Machine state");

    public static final Codec<LabelMode> CODEC = StringRepresentable.fromEnum(LabelMode::values);
    private static final LabelMode[] VALUES = values();

    private final String name;
    private final int id;

    LabelMode(String name, int id, String english) {
        this.name = name;
        this.id = id;
        ModContent.CONTENT.translation(translationKey(), english);
    }

    /** The id the item's network sync carries. */
    public int id() {
        return id;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public String translationKey() {
        return "gui." + MaxtechStorage.MOD_ID + ".smart_label.mode." + name;
    }

    /** The mode an empty-handed click switches to. */
    public LabelMode next() {
        return byId((id + 1) % VALUES.length);
    }

    /** The mode with {@link #id()} {@code id}, or {@link #ITEM} for an unknown one. */
    public static LabelMode byId(int id) {
        for (LabelMode mode : VALUES) {
            if (mode.id == id) {
                return mode;
            }
        }
        return ITEM;
    }
}
