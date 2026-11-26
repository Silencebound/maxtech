package dev.silencebound.maxtech.storage.neoforge.compat.refinedstorage;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.common.api.support.network.InWorldNetworkNodeContainer;
import com.refinedmods.refinedstorage.common.api.support.network.NetworkNodeContainerProvider;
import com.refinedmods.refinedstorage.common.storage.AbstractDiskContainerBlockEntity;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import dev.silencebound.maxtech.storage.dev.ShowcaseBridges;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import org.jspecify.annotations.Nullable;

/**
 * The Refined Storage network of the dev showcase's bridges station: an RS cable touching the bridge, a creative
 * controller behind it, a disk drive with a 1k disk on one side of the cable and a grid on the other. Once the network
 * has the disk it is stocked with the station's marker item and the bridge is placed. An RS disk gets its storage only
 * while it ticks in a player's inventory, so the builder ticks it once in a fake player's before putting it in the
 * drive. Dev tooling; registered only with Refined Storage loaded.
 */
final class RsShowcase {
    private static final long STOCK = 256;

    private RsShowcase() {
    }

    static void register() {
        ShowcaseBridges.register(ShowcaseBridges.Foreign.REFINED_STORAGE, RsShowcase::build);
    }

    private static Block block(String path) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(RefinedStorageCompat.MOD_ID, path));
    }

    private static ShowcaseBridges.Task build(ShowcaseBridges.Site site) {
        ServerLevel level = site.level();
        BlockPos cable = site.pos(0, 0);
        BlockPos drivePos = site.pos(1, 0);
        Block cableBlock = block("cable");
        level.setBlockAndUpdate(cable, cableBlock.defaultBlockState());
        level.setBlockAndUpdate(site.pos(0, 1), block("creative_controller").defaultBlockState());
        level.setBlockAndUpdate(site.pos(-1, 0), block("grid").defaultBlockState());
        level.setBlockAndUpdate(drivePos, block("disk_drive").defaultBlockState());
        ItemStack disk = new ItemStack(BuiltInRegistries.ITEM
                .getValue(Identifier.fromNamespaceAndPath(RefinedStorageCompat.MOD_ID, "1k_storage_disk")));
        disk.getItem().inventoryTick(disk, level, FakePlayerFactory.getMinecraft(level), null);
        if (level.getBlockEntity(drivePos) instanceof AbstractDiskContainerBlockEntity<?> drive) {
            drive.getDiskInventory().setItem(0, disk);
        } else {
            site.warn("no RS disk drive at " + drivePos.toShortString());
        }
        Direction towardsBridge = site.facing().getOpposite();
        return () -> {
            if (!level.getBlockState(cable).is(cableBlock)) {
                // Cleared meanwhile.
                return true;
            }
            StorageNetworkComponent root = root(level, cable, towardsBridge);
            if (root == null || root.insert(RsKeys.resource(ShowcaseBridges.Foreign.REFINED_STORAGE.marker()), STOCK,
                    Action.EXECUTE, RsKeys.ACTOR) <= 0) {
                // No network yet, or the disk is not in it yet.
                return false;
            }
            site.placeBridge();
            return true;
        };
    }

    /** The storage of the RS network at {@code pos}, or null while RS has not given the block a network. */
    private static @Nullable StorageNetworkComponent root(ServerLevel level, BlockPos pos, Direction side) {
        NetworkNodeContainerProvider provider = level.getCapability(
                RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(), pos, side);
        if (provider == null) {
            return null;
        }
        for (InWorldNetworkNodeContainer container : provider.getContainers()) {
            Network network = container.getNode().getNetwork();
            if (network != null && RsNetwork.powered(network)) {
                return network.getComponent(StorageNetworkComponent.class);
            }
        }
        return null;
    }
}
