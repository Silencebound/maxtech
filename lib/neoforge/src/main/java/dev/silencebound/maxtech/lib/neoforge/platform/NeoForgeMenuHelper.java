package dev.silencebound.maxtech.lib.neoforge.platform;

import dev.silencebound.maxtech.lib.platform.MenuHelper;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;

final class NeoForgeMenuHelper implements MenuHelper {
    @Override
    public <T extends AbstractContainerMenu> MenuType<T> menuType(MenuFactory<T> factory) {
        return IMenuTypeExtension.create(factory::create);
    }

    @Override
    public void open(ServerPlayer player, MenuProvider provider, Consumer<RegistryFriendlyByteBuf> extraData) {
        player.openMenu(provider, extraData);
    }

    @Override
    public NonNullList<ItemStack> craftingRemainders(Player player, CraftingRecipe recipe, CraftingInput input) {
        CommonHooks.setCraftingPlayer(player);
        try {
            return recipe.getRemainingItems(input);
        } finally {
            CommonHooks.setCraftingPlayer(null);
        }
    }

    @Override
    public <M extends AbstractContainerMenu, U extends Screen & MenuAccess<M>> void registerScreen(String modId,
            Supplier<? extends MenuType<M>> type, MenuScreens.ScreenConstructor<M, U> screen) {
        ModContainers.eventBus(modId).addListener(RegisterMenuScreensEvent.class,
                event -> event.register(type.get(), screen));
    }
}
