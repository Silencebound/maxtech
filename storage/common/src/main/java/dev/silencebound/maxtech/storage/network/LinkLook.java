package dev.silencebound.maxtech.storage.network;

import java.util.List;
import java.util.Locale;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * What the accent line on a device's front shows (MTX, owner 2026-10-17): online in the accent colour while the device
 * holds a channel (or, if {@link ChannelFree}, is a member) on a powered network with a controller in charge, no
 * channel in red while a network turned it away for channels (a full cable, link, hub or root link, the device limit or
 * no route), and offline in grey otherwise. The network works it out on topology, channel and power changes only
 * ({@link NetworkState}) and pushes it to the device ({@link NetworkDevice#linkLookChanged}); blocks keep it in
 * {@link #PROPERTY}, cable parts sync it with the cable.
 */
public enum LinkLook implements StringRepresentable {
    ONLINE, NO_CHANNEL, OFFLINE;

    /** The block state property of device blocks whose front carries the line; offline until a network says more. */
    public static final EnumProperty<LinkLook> PROPERTY = EnumProperty.create("link", LinkLook.class);

    /**
     * The front textures whose accent line shows the look: every face drawn with one of these gets the line of its
     * device's look on top (the texture itself has the online line, which items show). Paths under {@code textures/}.
     * Fronts of the controller and hubs, display panel walls (their frame runs round the whole wall) and machine
     * fronts, whose line shows that the machine works, are not on it.
     */
    public static final List<String> FRONT_TEXTURES = List.of("block/bulk_drive_front", "block/drive/front",
            "block/network_chest_front", "block/pattern_provider_face", "block/generator_provider_face",
            "block/pattern_terminal_front", "block/crafting_terminal_front", "block/research_terminal_front",
            "block/request_board_front", "block/mail_port_front", "block/terminal/screen", "block/sensor/front",
            "block/interface/face", "block/condition_switch/front_off", "block/condition_switch/front_on",
            "block/world/placer_front", "block/world/breaker_front", "block/world/collector", "block/p2p/item",
            "block/p2p/fluid", "block/p2p/energy", "block/p2p/redstone", "block/bridge/face_tier_1",
            "block/bridge/face_tier_2", "block/bridge/face_tier_3", "block/display/part_screen",
            "block/port/front_import", "block/port/front_import_tier_2", "block/port/front_import_tier_3",
            "block/port/front_export", "block/port/front_export_tier_2", "block/port/front_export_tier_3",
            "block/io_port_front", "block/storage_block/front_tier_1", "block/storage_block/front_tier_2",
            "block/storage_block/front_tier_3", "block/crafter_front");

    /** The overlay texture (under {@code textures/}) that draws this look's line, empty for online (no overlay). */
    public String overlayTexture() {
        return this == ONLINE ? "" : "block/link/" + getSerializedName();
    }

    /** Whether a front texture at {@code texture} (a block atlas sprite id) carries the line. */
    public static boolean isFront(Identifier texture) {
        return texture.getNamespace().equals("maxtech_storage") && FRONT_TEXTURES.contains(texture.getPath());
    }

    /** The look by its {@link #getSerializedName()}, offline for anything unknown (synced by another version). */
    public static LinkLook byName(String name) {
        for (LinkLook look : values()) {
            if (look.getSerializedName().equals(name)) {
                return look;
            }
        }
        return OFFLINE;
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
