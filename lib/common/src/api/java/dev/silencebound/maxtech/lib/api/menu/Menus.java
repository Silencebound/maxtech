package dev.silencebound.maxtech.lib.api.menu;

import dev.silencebound.maxtech.lib.api.registry.Registrar;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import org.jetbrains.annotations.ApiStatus;

/**
 * Where {@link MenuDefinition}s come from. Defining needs nothing from Lib, so definitions can be built and checked in
 * plain unit tests; registering and opening need Lib's backend, which Lib installs in its own construction (declare the
 * {@code maxtech_lib} dependency with {@code ordering = "AFTER"}).
 */
public final class Menus {
    /** Until Lib sets its own: not strict, and registering or opening throws. */
    private static final Backend NONE = new Backend() {
        @Override
        public boolean strict() {
            return false;
        }

        @Override
        public <H> Supplier<? extends MenuType<?>> register(MenuDefinition<H> definition,
                Registrar<MenuType<?>> registrar) {
            throw new IllegalStateException("MaxTech Lib has not set up menus yet");
        }

        @Override
        public <H> void open(MenuDefinition<H> definition, ServerPlayer player, H host) {
            throw new IllegalStateException("MaxTech Lib has not set up menus yet");
        }

        @Override
        public void problems(MenuDefinition<?> definition) {
        }
    };
    private static volatile Backend backend = NONE;

    private Menus() {
    }

    /**
     * Defines the menu {@code modId:name} over hosts of {@code hostClass}: {@code body} declares its parts on the
     * builder, in the order the menu adds and syncs them, and must set a layout. Usually a static field's initialiser.
     * In dev runs a layout problem throws {@link IllegalStateException} (see {@link MenuDefinition}).
     */
    public static <H> MenuDefinition<H> define(String modId, String name, Class<H> hostClass,
            Consumer<MenuDefinition.Builder<H>> body) {
        return MenuDefinition.build(modId, name, hostClass, body);
    }

    static Backend backend() {
        return backend;
    }

    /**
     * Throws or reports {@code definition}'s problems through Lib's backend; returns false when Lib has not set it yet
     * (a definition built before Lib's construction), so that {@link MenuDefinition#register} checks again.
     */
    static boolean checked(MenuDefinition<?> definition) {
        Backend backend = Menus.backend;
        if (Objects.equals(backend, NONE)) {
            return false;
        }
        if (definition.problems().isEmpty()) {
            return true;
        }
        if (backend.strict()) {
            throw new IllegalStateException("Menu " + definition.modId() + ":" + definition.name() + " layout: "
                    + String.join("; ", definition.problems()));
        }
        backend.problems(definition);
        return true;
    }

    /** Sets Lib's backend; Lib calls it once during init. Unit tests may set a fake. */
    @ApiStatus.Internal
    public static void setBackend(Backend source) {
        backend = source;
    }

    /** Lib's side of {@link Menus}. */
    @ApiStatus.Internal
    public interface Backend {
        /** Whether layout problems throw: in dev runs. */
        boolean strict();

        /** Registers the menu type (and the screen on a client); see {@link MenuDefinition#register}. */
        <H> Supplier<? extends MenuType<?>> register(MenuDefinition<H> definition, Registrar<MenuType<?>> registrar);

        /** See {@link MenuDefinition#open}. */
        <H> void open(MenuDefinition<H> definition, ServerPlayer player, H host);

        /** Reports the problems of a definition that is kept anyway (a built game). */
        void problems(MenuDefinition<?> definition);
    }
}
