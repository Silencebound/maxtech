package dev.silencebound.maxtech.lib.platform;

import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;

/**
 * Menus whose client side needs data from the server when it opens (e.g. which machine), which vanilla's
 * {@link MenuType} cannot carry. Menus and screens themselves are plain vanilla classes in {@code common}.
 */
public interface MenuHelper {
    /**
     * A menu type whose client-side menu is built from the data the server wrote in
     * {@link #open(ServerPlayer, MenuProvider, Consumer)}. Register the result like any other menu type.
     */
    <T extends AbstractContainerMenu> MenuType<T> menuType(MenuFactory<T> factory);

    /**
     * Opens {@code provider}'s menu for {@code player} and sends {@code extraData} to the client's {@link MenuFactory}.
     * Server thread.
     */
    void open(ServerPlayer player, MenuProvider provider, Consumer<RegistryFriendlyByteBuf> extraData);

    /**
     * Binds a screen to a menu type for mod {@code modId}. Client only, and during mod construction: the screen event
     * fires while the game window is built, before client setup, so a later call is silently lost. Never call it on a
     * dedicated server.
     */
    <M extends AbstractContainerMenu, U extends Screen & MenuAccess<M>> void registerScreen(String modId,
            Supplier<? extends MenuType<M>> type, MenuScreens.ScreenConstructor<M, U> screen);

    /**
     * The remainders of one craft of {@code recipe} from {@code input} ({@code recipe.getRemainingItems(input)}),
     * worked out with {@code player} as the crafting player, as vanilla's result slot does, so items whose remainder
     * depends on who crafts see the player (NeoForge: {@code CommonHooks.setCraftingPlayer} around the call). The
     * crafted event itself is fired by vanilla's {@code ResultSlot.checkTakeAchievements}, which a result slot calls.
     * Server thread.
     */
    NonNullList<ItemStack> craftingRemainders(Player player, CraftingRecipe recipe, CraftingInput input);

    /** Builds the client-side menu from the server's extra data. */
    @FunctionalInterface
    interface MenuFactory<T extends AbstractContainerMenu> {
        T create(int containerId, Inventory inventory, RegistryFriendlyByteBuf data);
    }
}
