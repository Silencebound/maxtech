package dev.silencebound.maxtech.farms.lab;

import dev.silencebound.maxtech.farms.MaxtechFarms;
import dev.silencebound.maxtech.lib.api.conduit.ConduitEntry;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.Conduits;
import dev.silencebound.maxtech.lib.api.network.NetworkManager;
import dev.silencebound.maxtech.lib.api.network.NetworkType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.Level;

/**
 * The lab tube conduit and the lab network type it forms. Lib owns the tube block: it saves, syncs and draws it (Lib's
 * shell look: cyan glass with gunmetal borders, showing the contents the lab network sets), takes dyes and facades and
 * reports each tube to the lab network. Tubes never tick.
 */
public final class LabTubes {
    private static final Direction[] SIDES = Direction.values();
    /** Dark gunmetal of the tube's borders and rims; a dye tints it. */
    private static final int BORDER_COLOUR = 0x2C3238;

    public static final Identifier NETWORK_ID = MaxtechFarms.id("lab");
    public static final NetworkType NETWORK = NetworkType.register(NETWORK_ID,
            level -> Conduits.connectivity(level, NETWORK_ID), LabNetworkLogic::new);
    public static final ConduitEntry<ConduitType> TUBE = Conduits
            .builder(MaxtechFarms.MOD_ID, "lab_tube",
                    item -> new ConduitType(NETWORK_ID, "lab", 0xE4F4F4, 0.375F, item))
            .look(ConduitLook.shell(MaxtechFarms.id("block/lab_tube_glass")).withBorderColour(BORDER_COLOUR)
                    .withBorder(1.0F).withRim(1.5F))
            .creativeTab(CreativeModeTabs.FUNCTIONAL_BLOCKS).register();

    private LabTubes() {
    }

    /** Loads the class so the tube registers during mod construction; called from {@link MaxtechFarms#init()}. */
    public static void init() {
    }

    /**
     * Has the lab tubes next to {@code pos} look at their neighbours again, for a block that joins a lab network by
     * standing next to a tube (hub, fluid port) when it loads or goes. Server only; the re-read happens with the
     * level's network tick.
     */
    public static void neighbourChanged(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        NetworkManager manager = NetworkManager.get(server);
        for (Direction side : SIDES) {
            BlockPos next = pos.relative(side);
            if (Conduits.isChunkPresent(server, next.getX(), next.getZ())
                    && server.getBlockState(next).is(TUBE.block().get())) {
                manager.invalidateNode(NETWORK, next);
            }
        }
    }
}
