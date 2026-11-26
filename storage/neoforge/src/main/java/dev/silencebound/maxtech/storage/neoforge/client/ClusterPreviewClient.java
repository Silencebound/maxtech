package dev.silencebound.maxtech.storage.neoforge.client;

import dev.silencebound.maxtech.lib.client.ghost.GhostBlocks;
import dev.silencebound.maxtech.lib.client.ghost.GhostSet;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cluster.ClusterNeeds;
import dev.silencebound.maxtech.storage.cluster.ClusterPreview;
import dev.silencebound.maxtech.storage.controller.ControllerBlock;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuPartBlock;
import dev.silencebound.maxtech.storage.palette.StatusPalette;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * The client side of the cluster ghost preview (M7C item 22). While the player holds a controller block or a CPU part,
 * it asks the server every {@link ClusterPreview#REQUEST_TICKS} ticks and shows the answer through Lib's
 * {@link GhostBlocks}: the cluster's blocks outlined (red in a conflict), the cells where the held block would join it
 * as translucent cubes, green when one more raises what the cluster can do and amber when it would add nothing. The
 * ghost cells are built only when a new answer arrives; when the cluster's first need changes it is shown in the action
 * bar. Without such a block held the tick reads the two held stacks and does nothing else. Client thread.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID, value = Dist.CLIENT)
final class ClusterPreviewClient {
    static final Identifier CHANNEL = MaxtechStorage.id("cluster_preview");
    /** The cluster's blocks, as the locate outlines. */
    private static final int MEMBER_COLOUR = 0xFF40E0FF;
    /** The alpha of the growth cubes' faces. */
    private static final int FACE_ALPHA = 0x48000000;
    private static boolean holding;
    private static int countdown;
    private static int shownCount = -1;
    private static List<ClusterNeeds.Need> lastNeeds = List.of();

    private ClusterPreviewClient() {
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || !holdsClusterBlock(player)) {
            if (holding) {
                stop();
            }
            return;
        }
        holding = true;
        if (--countdown <= 0) {
            countdown = ClusterPreview.REQUEST_TICKS;
            ClusterPreview.request();
        }
        if (ClusterPreview.receivedCount() != shownCount) {
            shownCount = ClusterPreview.receivedCount();
            show(minecraft, ClusterPreview.received());
        }
    }

    @SubscribeEvent
    static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        stop();
    }

    private static void stop() {
        holding = false;
        countdown = 0;
        ClusterPreview.forget();
        shownCount = ClusterPreview.receivedCount();
        lastNeeds = List.of();
        GhostBlocks.client().clear(CHANNEL);
    }

    private static boolean holdsClusterBlock(LocalPlayer player) {
        return clusterBlock(player.getMainHandItem()) || clusterBlock(player.getOffhandItem());
    }

    private static boolean clusterBlock(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item
                && (item.getBlock() instanceof ControllerBlock || item.getBlock() instanceof CpuPartBlock);
    }

    private static void show(Minecraft minecraft, ClusterPreview.Preview preview) {
        if (preview.mode() == ClusterPreview.Mode.NONE) {
            GhostBlocks.client().clear(CHANNEL);
            lastNeeds = List.of();
            return;
        }
        GhostSet.Builder builder = GhostSet.builder();
        int memberColour = preview.conflict() ? StatusPalette.bad() : MEMBER_COLOUR;
        LongList members = preview.members();
        for (int i = 0; i < members.size(); i++) {
            builder.add(members.getLong(i), memberColour, GhostSet.Style.OUTLINE);
        }
        int growthColour = ((preview.helps() ? StatusPalette.good() : StatusPalette.warn()) & 0xFFFFFF) | FACE_ALPHA;
        LongList growth = preview.growth();
        for (int i = 0; i < growth.size(); i++) {
            builder.add(growth.getLong(i), growthColour, GhostSet.Style.FILLED);
        }
        GhostBlocks.client().show(CHANNEL, builder.build());
        if (!preview.needs().equals(lastNeeds)) {
            lastNeeds = preview.needs();
            ClusterNeeds.Need first = firstNeed(preview.needs());
            if (first != null) {
                minecraft.gui.setOverlayMessage(ClusterPreview.text(first), false);
            }
        }
    }

    /** The first line that is not a CPU's heading. */
    private static ClusterNeeds.@Nullable Need firstNeed(List<ClusterNeeds.Need> needs) {
        for (ClusterNeeds.Need need : needs) {
            if (need.kind() != ClusterNeeds.Kind.CPU) {
                return need;
            }
        }
        return null;
    }
}
