package dev.silencebound.maxtech.storage.neoforge.client;

import dev.silencebound.maxtech.lib.api.carried.CarriedItems;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.signal.NetworkPick;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * Pick block from the network (M7C item 7), client side: the pick key on a block, in survival, while a wireless
 * terminal is carried, also asks the server for the block from the network ({@link NetworkPick}). Vanilla's pick goes
 * on as usual; the server decides everything.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID, value = Dist.CLIENT)
final class NetworkPickClient {
    private NetworkPickClient() {
    }

    @SubscribeEvent
    static void pick(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isPickBlock()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || player.hasInfiniteMaterials() || player.isSpectator()
                || !(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || CarriedItems.find(player, WirelessTerminalItem.class).isEmpty()) {
            return;
        }
        NetworkPick.send(hit.getBlockPos());
    }
}
