package dev.silencebound.maxtech.lib.gui.transfer;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.platform.Platform;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * The menus whose crafting grid recipe viewers may fill, and the payload that carries a recipe's ingredients to the
 * server. A mod registers each {@link CraftingGridMenu} menu here during construction; Lib's JEI plugin reads the list
 * when JEI starts. The server acts on a payload only for the player's open, valid {@link CraftingGridMenu}, at most
 * once per tick per player.
 */
public final class CraftingGridMenus {
    /** Most acceptable stacks the payload carries per cell; JEI cycles through far fewer for any real recipe. */
    public static final int MAX_CANDIDATES = 64;
    private static final List<Entry<?>> MENUS = new CopyOnWriteArrayList<>();
    private static final Object2LongOpenHashMap<UUID> LAST_TRANSFER = new Object2LongOpenHashMap<>();

    /** A registered menu kind: its class and menu type. */
    public record Entry<M extends AbstractContainerMenu & CraftingGridMenu>(Class<M> menuClass,
            Supplier<? extends MenuType<M>> menuType) {
    }

    private CraftingGridMenus() {
    }

    /** Registers the payload; call once during mod construction. */
    public static void register() {
        Platform.get().network().registerToServer(MaxtechLib.MOD_ID, Transfer.TYPE, Transfer.CODEC,
                CraftingGridMenus::receive);
        Platform.get().lifecycle().onServerStopped(server -> LAST_TRANSFER.clear());
    }

    /** Lets recipe viewers fill the grid of menus of {@code menuClass} and {@code menuType}. Mod construction. */
    public static <M extends AbstractContainerMenu & CraftingGridMenu> void register(Class<M> menuClass,
            Supplier<? extends MenuType<M>> menuType) {
        MENUS.add(new Entry<>(menuClass, menuType));
    }

    /** Every registered menu kind, for the recipe viewer plugins. */
    public static List<Entry<?>> menus() {
        return List.copyOf(MENUS);
    }

    /**
     * Server: fills the grid of the player's open menu {@code payload.containerId()}. Returns whether it acted; refused
     * for another or invalid menu, a menu that is no {@link CraftingGridMenu}, a wrong cell count, or a second transfer
     * by the same player in one tick.
     */
    public static boolean receive(Transfer payload, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(player.containerMenu instanceof CraftingGridMenu target)
                || player.containerMenu.containerId != payload.containerId() || !player.containerMenu.stillValid(player)
                || payload.cells().size() != CraftingGridMenu.GRID_SIZE) {
            return false;
        }
        long now = serverPlayer.level().getGameTime();
        UUID id = player.getUUID();
        if (LAST_TRANSFER.containsKey(id) && LAST_TRANSFER.getLong(id) == now) {
            return false;
        }
        LAST_TRANSFER.put(id, now);
        target.receiveRecipe(serverPlayer, payload.cells(), payload.maxTransfer());
        return true;
    }

    /**
     * Client to server: fill the grid of menu {@code containerId} with these {@code cells} (nine lists of acceptable
     * stacks, row by row). Stacks go through vanilla's untrusted, validated item codec.
     */
    public record Transfer(int containerId, List<List<ItemStack>> cells,
            boolean maxTransfer) implements CustomPacketPayload {
        public static final Type<Transfer> TYPE = new Type<>(MaxtechLib.id("crafting_grid_transfer"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Transfer> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Transfer::containerId,
                ItemStack.validatedStreamCodec(ItemStack.OPTIONAL_UNTRUSTED_STREAM_CODEC)
                        .apply(ByteBufCodecs.list(MAX_CANDIDATES))
                        .apply(ByteBufCodecs.list(CraftingGridMenu.GRID_SIZE)),
                Transfer::cells, ByteBufCodecs.BOOL, Transfer::maxTransfer, Transfer::new);

        @Override
        public Type<Transfer> type() {
            return TYPE;
        }
    }
}
