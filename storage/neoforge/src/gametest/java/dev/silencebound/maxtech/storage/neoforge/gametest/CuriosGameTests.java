package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.carried.CarriedItems;
import dev.silencebound.maxtech.lib.api.carried.CarriedSlot;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.logic.remote.RemoteContent;
import dev.silencebound.maxtech.storage.neoforge.compat.curios.CuriosSlots;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem;
import dev.silencebound.maxtech.storage.tool.belt.BeltContent;
import dev.silencebound.maxtech.storage.tool.belt.BeltRestock;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletContent;
import dev.silencebound.maxtech.storage.tool.uplink.UplinkContent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import top.theillusivec4.curios.api.CuriosCapability;
import top.theillusivec4.curios.api.CuriosSlotTypes;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

/**
 * Curios compat with Curios itself loaded; registered only then, so these run only with {@code -PwithCurios=true}
 * (never in CI, which tests Storage on Lib alone and the finder through a fake slot, {@link CarriedItemGameTests}).
 * Storage's data gives players a belt and a charm slot and puts each gadget into the slot it is meant for; a belt and a
 * terminal equipped there are found by Lib's finder and read live from the slot.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class CuriosGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();

    static {
        if (ModList.get().isLoaded(CuriosSlots.MOD_ID)) {
            StorageTestEnvironment.init();
            register("curios_gadgets_in_slots", helper -> WithCurios.gadgetsInSlots(helper));
        }
    }

    private CuriosGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        if (NAMES.isEmpty()) {
            return;
        }
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("curios"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 100);
        }
    }

    /** The Curios calls, in a class of their own so nothing loads Curios' classes without Curios. */
    private static final class WithCurios {
        private WithCurios() {
        }

        // Curios declares the capability's context as plain Void; NeoForge's no-context overload wants @Nullable Void.
        @SuppressWarnings("NullAway")
        static void gadgetsInSlots(GameTestHelper helper) {
            // A player that never joined: Curios' join sync cannot reach a mock connection (as with KubeJS), so the
            // handler's slots are set up here with reset(), as Curios does on join.
            ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "curios_holder");
            slotFor(helper, player, BeltContent.RESTOCK_BELT.get(), CuriosSlots.BELT);
            slotFor(helper, player, SignalContent.WIRELESS_TERMINAL.get(), CuriosSlots.CHARM);
            slotFor(helper, player, CraftingTabletContent.TABLET.get(), CuriosSlots.CHARM);
            slotFor(helper, player, RemoteContent.REMOTE.get(), CuriosSlots.CHARM);
            slotFor(helper, player, UplinkContent.POCKET_UPLINK.get(), CuriosSlots.CHARM);
            slotFor(helper, player, CellContent.PORTABLE_STORAGE_CELL.get(), CuriosSlots.CHARM);

            ICuriosItemHandler handler = player.getCapability(CuriosCapability.INVENTORY);
            helper.assertTrue(handler != null, "Curios handler");
            if (handler == null) {
                return;
            }
            handler.reset();
            helper.assertTrue(handler.getStacksHandler(CuriosSlots.BELT).isPresent(), "player has a belt slot");
            helper.assertTrue(handler.getStacksHandler(CuriosSlots.CHARM).isPresent(), "player has a charm slot");
            ItemStack belt = new ItemStack(BeltContent.RESTOCK_BELT.get());
            ItemStack terminal = new ItemStack(SignalContent.WIRELESS_TERMINAL.get());
            handler.setEquippedCurio(CuriosSlots.BELT, 0, belt);
            handler.setEquippedCurio(CuriosSlots.CHARM, 0, terminal);
            helper.assertTrue(BeltRestock.beltOf(player) == belt, "belt found in the belt slot");
            CarriedSlot slot = CarriedItems.locate(player, WirelessTerminalItem.class).orElse(CarriedSlot.EMPTY);
            helper.assertTrue(slot.get() == terminal, "terminal located in the charm slot");

            handler.setEquippedCurio(CuriosSlots.BELT, 0, ItemStack.EMPTY);
            handler.setEquippedCurio(CuriosSlots.CHARM, 0, ItemStack.EMPTY);
            helper.assertTrue(BeltRestock.beltOf(player).isEmpty(), "belt after unequipping");
            helper.assertTrue(slot.get().isEmpty(), "terminal slot after unequipping");
            helper.succeed();
        }

        private static void slotFor(GameTestHelper helper, ServerPlayer player, Item item, String slot) {
            helper.assertTrue(CuriosSlotTypes.getItemSlotTypes(new ItemStack(item), player).containsKey(slot),
                    item + " fits the " + slot + " slot");
        }
    }
}
