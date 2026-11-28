package dev.silencebound.maxtech.lib.menu;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.menu.MenuDefinition;
import dev.silencebound.maxtech.lib.api.menu.MenuTesting;
import dev.silencebound.maxtech.lib.api.menu.Menus;
import dev.silencebound.maxtech.lib.api.registry.Registrar;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Lib's {@link Menus} backend: plain vanilla menu types (the client menu needs no data from the server, since the
 * definition says everything), opened with vanilla's open-screen packet, and the screen bound on clients through a
 * binder that Lib's client entrypoint sets before any addon is constructed.
 */
public final class LibMenus implements Menus.Backend, MenuTesting.Support {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile @Nullable Consumer<MenuDefinition<?>> screens;

    private final boolean strict;

    /** {@code strict}: layout problems throw (dev runs). */
    public LibMenus(boolean strict) {
        this.strict = strict;
    }

    /**
     * Binds every definition registered from now on to its screen with {@code binder}; Lib's client entrypoint calls it
     * during Lib's construction, so addon definitions, registered during theirs, all reach it.
     */
    public static void bindScreensWith(Consumer<MenuDefinition<?>> binder) {
        screens = binder;
    }

    @Override
    public boolean strict() {
        return strict;
    }

    @Override
    public <H> Supplier<? extends MenuType<?>> register(MenuDefinition<H> definition,
            Registrar<MenuType<?>> registrar) {
        Supplier<? extends MenuType<?>> type = registrar.register(definition.name(),
                () -> new MenuType<DefinedMenu<H>>(
                        (containerId, inventory) -> new DefinedMenu<>(definition, containerId, inventory, null),
                        FeatureFlags.VANILLA_SET));
        Consumer<MenuDefinition<?>> binder = screens;
        if (binder != null) {
            // The binder reads the type only when the screen event fires, after this returns.
            binder.accept(definition);
        }
        return type;
    }

    @Override
    public <H> void open(MenuDefinition<H> definition, ServerPlayer player, H host) {
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, opener) -> new DefinedMenu<>(definition, containerId, inventory, host),
                Component.translatable(definition.titleKey())));
    }

    @Override
    public <H> MenuTesting.Session<H> openTesting(MenuDefinition<H> definition, ServerPlayer player, H host) {
        return new MenuTestSession<>(definition, player, host, strict);
    }

    @Override
    public void problems(MenuDefinition<?> definition) {
        LOGGER.error("Menu {}:{} layout: {}", definition.modId(), definition.name(),
                String.join("; ", definition.problems()));
    }
}
