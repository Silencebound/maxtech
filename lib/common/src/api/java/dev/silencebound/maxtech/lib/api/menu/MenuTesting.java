package dev.silencebound.maxtech.lib.api.menu;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.jetbrains.annotations.ApiStatus;

/**
 * Test hooks for menus built from a {@link MenuDefinition}, for GameTests only; never call them in a running game. A
 * mock server player (from {@code GameTestHelper#makeMockServerPlayerInLevel}) has no connection, so Lib's values
 * payload cannot reach it and nothing on the client could read the values anyway. {@link #open} opens the menu with the
 * payloads captured into the returned {@link Session}, which reads the values the way a screen would, presses tank
 * buttons and shift-clicks slots.
 * <p>
 * Server thread (the GameTest thread). Not stable until Farms (FM1) settles the menu api.
 */
@ApiStatus.Experimental
public final class MenuTesting {
    private static volatile Support support = new Support() {
        @Override
        public <H> Session<H> openTesting(MenuDefinition<H> definition, ServerPlayer player, H host) {
            throw new IllegalStateException("MaxTech Lib has not set up menus yet");
        }
    };

    private MenuTesting() {
    }

    /**
     * Opens {@code definition} over {@code host} for {@code player} like {@link MenuDefinition#open}, but sends the
     * menu's values payloads to the returned session instead of the player's connection. Close the session when the
     * test is done; it does not remove the player. Throws {@link IllegalStateException} for any player but a GameTest
     * mock player, in dev and production runs alike.
     */
    public static <H> Session<H> open(MenuDefinition<H> definition, ServerPlayer player, H host) {
        return support.openTesting(definition, player, host);
    }

    /** Sets Lib's implementation; Lib calls it once during init. */
    @ApiStatus.Internal
    public static void setSupport(Support source) {
        support = source;
    }

    /** Lib's side of {@link MenuTesting}. */
    @ApiStatus.Internal
    public interface Support {
        /** See {@link MenuTesting#open}. */
        <H> Session<H> openTesting(MenuDefinition<H> definition, ServerPlayer player, H host);
    }

    /** One open menu under test. Closing it restores the normal values delivery for menus opened later. */
    public interface Session<H> extends AutoCloseable {
        /** The open menu: slots, carried stack and clicks as vanilla has them. */
        AbstractContainerMenu menu();

        /**
         * Runs one server tick of the menu: reads the host's getters and, if anything changed, captures one values
         * payload. Only this sends payloads (at most one per call); call it after changing the host and before reading
         * {@link #clientValues}.
         */
        void tick();

        /**
         * The values the server read at the last {@link #tick} (or when opening); if the mock player is ticked, its
         * menu also reads them, so they may be newer than what {@link #clientValues} shows.
         */
        MenuValueView serverValues();

        /**
         * The values a client screen would read: a client menu with no host mirrors every payload captured so far, in
         * order. Zero, the first enum constant and no resource before the first payload.
         */
        MenuValueView clientValues();

        /** How many values payloads the menu has sent, opening included. */
        int payloadsSent();

        /**
         * Clicks the bucket button of {@code tank}, as the screen does when the player clicks the gauge with a
         * container on the cursor; returns whether fluid moved. Throws {@link IllegalArgumentException} for a tank that
         * takes no bucket clicks (not a fluid tank) or one of another definition.
         */
        boolean pressTankButton(TankValue tank);

        /**
         * Shift-clicks slot {@code index} (0 based) of {@code group}, as a player does on that slot of the machine
         * side.
         */
        void shiftClick(MenuSlots group, int index);

        /**
         * Shift-clicks the player's slot {@code index} (0 to 35: the main inventory 0 to 26, left to right, top row
         * first, then the hotbar 27 to 35), the slots the menu shows below the machine's. Throws
         * {@link IllegalStateException} when the definition has no player inventory.
         */
        void shiftClickPlayerSlot(int index);

        /**
         * Closes the player's open container and delivers payloads to the player's connection again for menus built
         * from now on.
         */
        @Override
        void close();
    }
}
