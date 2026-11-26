package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import io.netty.buffer.Unpooled;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** {@code Platform.menus()}: a menu type builds its client-side menu from the server's extra data. */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class MenuGameTests {
    private static final Identifier STRUCTURE = MaxtechLib.id("empty_7x2x3");
    private static final String NAME = "menu_built_from_extra_data";

    private static final RegistryHelper.Registrar<MenuType<?>> MENUS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.MENU);
    private static final RegistryEntry<MenuType<?>, MenuType<TestMenu>> TEST_MENU = MENUS.register("test_menu",
            () -> Platform.get().menus().menuType((id, inventory, data) -> new TestMenu(id, data.readVarInt())));

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        FUNCTIONS.register(NAME, () -> MenuGameTests::opens);
    }

    private MenuGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("menus"));
        event.registerTest(MaxtechLib.id(NAME),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechLib.id(NAME)),
                        new TestData<>(environment, STRUCTURE, 20, 0, true)));
    }

    /**
     * The client side of a menu type from {@code menuType}: built from the server's extra data. Opening through
     * {@code Platform.menus().open} needs a client that negotiated NeoForge's payloads, which a GameTest mock player
     * has not, so that one-line pass-through is checked in a dev client instead.
     */
    private static void opens(GameTestHelper helper) {
        RegistryFriendlyByteBuf data = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess());
        data.writeVarInt(42);
        Inventory inventory = helper.makeMockPlayer(GameType.SURVIVAL).getInventory();
        TestMenu menu = TEST_MENU.get().create(7, inventory, data);
        helper.assertValueEqual(menu.value(), 42, "value from extra data");
        helper.assertValueEqual(menu.containerId, 7, "container id");
        helper.assertValueEqual(menu.getType(), TEST_MENU.get(), "menu type");
        helper.succeed();
    }

    /** A slotless menu carrying one number from the server. */
    private static final class TestMenu extends AbstractContainerMenu {
        private final int value;

        TestMenu(int containerId, int value) {
            super(TEST_MENU.get(), containerId);
            this.value = value;
        }

        int value() {
            return value;
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }
}
