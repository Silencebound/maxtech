package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.drive.DriveContent;
import dev.silencebound.maxtech.storage.iface.InterfaceBlockEntity;
import dev.silencebound.maxtech.storage.iface.InterfaceContent;
import dev.silencebound.maxtech.storage.label.LabelCopy;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Copy settings to many devices (M7C item 8): a port's filter and mode go to the other ports labelled the same that the
 * player may configure, not to a port someone else owns or to an interface with the label; a drive's priority goes to
 * the other drive with its label; labels and their colours stay as they were. A player who may not configure the source
 * changes nothing. A controller, a cable along x 2 to 7 at y = 1, z = 1, and south of it ports at x 2 to 4, an
 * interface at x 5 and drives at x 6 and 7.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LabelCopyGameTests {
    private static final String[] NAMES = {"label_copy_settings", "label_copy_refused"};

    static {
        StorageTestEnvironment.init();
        register(NAMES[0], LabelCopyGameTests::settings);
        register(NAMES[1], LabelCopyGameTests::refused);
    }

    private LabelCopyGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("label_copy"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 200);
        }
    }

    private record Layout(List<PortBlockEntity> ports, InterfaceBlockEntity iface, DriveBlockEntity bank,
            DriveBlockEntity other) {
    }

    private static Layout layout(GameTestHelper helper, ServerPlayer owner, ServerPlayer someoneElse) {
        NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 7, CableContent.STORAGE_CABLE_TIER_1.type().get());
        DeviceLabel smelt = DeviceLabel.of("smelt", DyeColor.ORANGE);
        List<PortBlockEntity> ports = new ArrayList<>();
        for (int x = 2; x <= 4; x++) {
            BlockPos pos = NetworkGameTests.at(x).south();
            helper.setBlock(pos, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.SOUTH)
                    .setValue(PortBlock.MODE, PortMode.IMPORT));
            PortBlockEntity port = helper.getBlockEntity(pos, PortBlockEntity.class);
            port.setOwner(OwnerRecord.of(x == 4 ? someoneElse : owner));
            port.setLabel(x == 3 ? DeviceLabel.of("SMELT", DyeColor.RED) : smelt);
            ports.add(port);
        }
        BlockPos ifacePos = NetworkGameTests.at(5).south();
        helper.setBlock(ifacePos, InterfaceContent.INTERFACE.get());
        InterfaceBlockEntity iface = helper.getBlockEntity(ifacePos, InterfaceBlockEntity.class);
        iface.setLabel(smelt);
        DeviceLabel bankLabel = DeviceLabel.of("bank", null);
        helper.setBlock(NetworkGameTests.at(6).south(), DriveContent.DRIVE.get());
        helper.setBlock(NetworkGameTests.at(7).south(), DriveContent.DRIVE.get());
        DriveBlockEntity bank = helper.getBlockEntity(NetworkGameTests.at(6).south(), DriveBlockEntity.class);
        DriveBlockEntity other = helper.getBlockEntity(NetworkGameTests.at(7).south(), DriveBlockEntity.class);
        bank.setLabel(bankLabel);
        other.setLabel(bankLabel);
        return new Layout(ports, iface, bank, other);
    }

    private static void joined(GameTestHelper helper, Layout layout) {
        for (PortBlockEntity port : layout.ports()) {
            helper.assertTrue(port.network() != null, "port joined");
        }
        helper.assertTrue(
                layout.iface().network() != null && layout.bank().network() != null && layout.other().network() != null,
                "devices joined");
    }

    /**
     * The source port (export, cobblestone filter) reaches the port labelled "SMELT" in red (same label key) but not
     * the one someone else owns nor the interface; the drive's priority 5 reaches the other "bank" drive.
     */
    private static void settings(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "copy_owner");
        ServerPlayer someoneElse = NetworkGameTests.player(helper.getLevel(), "copy_other");
        Layout layout = layout(helper, owner, someoneElse);
        helper.startSequence().thenWaitUntil(() -> joined(helper, layout)).thenExecute(() -> {
            PortBlockEntity source = layout.ports().get(0);
            source.setMode(PortMode.EXPORT);
            source.filter().setItem(0, new ItemStack(Items.COBBLESTONE));
            LabelCopy.Result result = LabelCopy.copy(owner, source);
            helper.assertValueEqual(result, new LabelCopy.Result(1, 1, 1), "changed, denied, other kind");
            PortBlockEntity same = layout.ports().get(1);
            helper.assertValueEqual(same.mode(), PortMode.EXPORT, "the mode copied");
            helper.assertTrue(same.filter().getItem(0).is(Items.COBBLESTONE), "the filter copied");
            helper.assertValueEqual(same.label(), DeviceLabel.of("SMELT", DyeColor.RED), "its own label kept");
            PortBlockEntity foreign = layout.ports().get(2);
            helper.assertValueEqual(foreign.mode(), PortMode.IMPORT, "someone else's port untouched");
            helper.assertTrue(foreign.filter().isBlank(), "someone else's filter untouched");

            layout.bank().setPriority(5);
            helper.assertValueEqual(LabelCopy.copy(owner, layout.bank()), new LabelCopy.Result(1, 0, 0),
                    "one drive changed");
            helper.assertValueEqual(layout.other().priority(), 5, "the priority copied");
        }).thenSucceed();
    }

    /** Someone who may not configure the source port copies nothing; an unlabelled device copies nothing. */
    private static void refused(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "copy_owner2");
        ServerPlayer someoneElse = NetworkGameTests.player(helper.getLevel(), "copy_other2");
        Layout layout = layout(helper, owner, someoneElse);
        helper.startSequence().thenWaitUntil(() -> joined(helper, layout)).thenExecute(() -> {
            PortBlockEntity source = layout.ports().get(0);
            source.setMode(PortMode.EXPORT);
            helper.assertValueEqual(LabelCopy.copy(someoneElse, source), new LabelCopy.Result(0, 0, 0),
                    "a stranger's copy");
            helper.assertValueEqual(layout.ports().get(1).mode(), PortMode.IMPORT, "nothing changed");
            source.setLabel(DeviceLabel.NONE);
            helper.assertValueEqual(LabelCopy.copy(owner, source), new LabelCopy.Result(0, 0, 0), "no label");
            helper.assertValueEqual(layout.ports().get(1).mode(), PortMode.IMPORT, "still nothing changed");
        }).thenSucceed();
    }
}
