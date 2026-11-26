package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.authlib.GameProfile;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.SecurityMenu;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.p2p.P2PBlock;
import dev.silencebound.maxtech.storage.p2p.P2PBlockEntity;
import dev.silencebound.maxtech.storage.p2p.P2PContent;
import dev.silencebound.maxtech.storage.p2p.P2PKind;
import dev.silencebound.maxtech.storage.p2p.P2PLinkerItem;
import dev.silencebound.maxtech.storage.p2p.P2PRole;
import dev.silencebound.maxtech.storage.tool.usage.UsageBook;
import dev.silencebound.maxtech.storage.tool.usage.UsageContent;
import dev.silencebound.maxtech.storage.tool.usage.UsageReport;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * M6e area F: the usage report book, the security screen's trust marks and added players, and P2P links under the
 * security module's rules.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ServerSecurityGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String USAGE = "server_usage_report";
    private static final String TRUST = "server_security_trust_and_add";
    private static final String P2P = "server_security_p2p_linker";

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(USAGE, StorageTestEnvironment.clearing(ServerSecurityGameTests::usageReport));
        StorageTests.function(TRUST, StorageTestEnvironment.clearing(ServerSecurityGameTests::trustAndAdd));
        StorageTests.function(P2P, StorageTestEnvironment.clearing(ServerSecurityGameTests::p2pLinker));
    }

    private ServerSecurityGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("server_security"),
                StorageTestEnvironment.plain());
        StorageTests.test(event, plain, USAGE, STRUCTURE, 400);
        StorageTests.test(event, plain, TRUST, STRUCTURE, 400);
        StorageTests.test(event, plain, P2P, STRUCTURE, 400);
    }

    /**
     * A controller of the owner's with two test drives (one the owner's, one a member's): the network's report counts
     * both drives and both owners and makes a summary, a network and an owners page. Using the book on the core gives
     * the owner a written book once per cooldown; a stranger without access gets none; an air use writes the owner's
     * team report, which finds the network as well.
     */
    private static void usageReport(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer owner = fake(level, "usage_owner");
        FakePlayer stranger = fake(level, "usage_stranger");
        ServerPlayer member = NetworkGameTests.player(level, "usage_member");
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        NetworkGameTests.power(helper, core, 10_000);
        NetworkGameTests.cables(helper, 2, 4, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice owned = NetworkGameTests.device(helper, NetworkGameTests.at(3).south());
        owned.setOwner(OwnerRecord.of(owner));
        TestStorage.TestDevice placed = NetworkGameTests.device(helper, NetworkGameTests.at(4).south());
        placed.setOwner(OwnerRecord.of(member));
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, core);
            helper.assertTrue(NetworkGameTests.net(helper, owned) == network, "owner's drive joined");
            helper.assertTrue(NetworkGameTests.net(helper, placed) == network, "member's drive joined");
        }).thenExecute(() -> {
            UsageReport report = UsageReport.forNetwork(NetworkGameTests.state(helper, core));
            helper.assertValueEqual(report.subject(), "usage_owner", "subject");
            helper.assertValueEqual(report.networks().size(), 1, "networks");
            UsageReport.NetworkRow row = report.networks().getFirst();
            helper.assertValueEqual(row.storage(), 2, "drives");
            helper.assertValueEqual(row.refused(), 0, "refused");
            helper.assertValueEqual(report.owners(), List.of(new UsageReport.OwnerCount("usage_member", 1),
                    new UsageReport.OwnerCount("usage_owner", 1)), "devices per owner");
            helper.assertValueEqual(UsageBook.pages(report).size(), 3, "pages");

            ItemStack book = new ItemStack(UsageContent.USAGE_REPORT_BOOK.get());
            owner.setItemInHand(InteractionHand.MAIN_HAND, book);
            useOn(book, owner, helper.absolutePos(core));
            useOn(book, owner, helper.absolutePos(core));
            helper.assertValueEqual(owner.getInventory().countItem(Items.WRITTEN_BOOK), 1, "one book per cooldown");
            ItemStack written = written(owner);
            WrittenBookContent content = written.get(DataComponents.WRITTEN_BOOK_CONTENT);
            helper.assertTrue(content != null && content.pages().size() == 3, "the book's pages");
            helper.assertValueEqual(written.getHoverName(), Component.translatable("book.maxtech_storage.usage.name"),
                    "the book's name");

            ItemStack strangers = new ItemStack(UsageContent.USAGE_REPORT_BOOK.get());
            useOn(strangers, stranger, helper.absolutePos(core));
            helper.assertValueEqual(stranger.getInventory().countItem(Items.WRITTEN_BOOK), 0, "stranger's report");

            owner.getCooldowns().removeCooldown(owner.getCooldowns().getCooldownGroup(book));
            book.use(level, owner, InteractionHand.MAIN_HAND);
            helper.assertValueEqual(owner.getInventory().countItem(Items.WRITTEN_BOOK), 2, "the team report");
            UsageReport team = UsageReport.forTeam(level, "usage_owner", OwnerRecord.of(owner));
            helper.assertValueEqual(team.networks().size(), 1, "the team's networks");
            helper.assertValueEqual(
                    UsageReport.forTeam(level, "usage_stranger", OwnerRecord.of(stranger)).networks().size(), 0,
                    "a stranger's team");
        }).thenSucceed();
    }

    /**
     * The security screen: a rule added for a player who is not online starts at view, nothing is added for the owner
     * or by a stranger, and an invalid name changes nothing. The owner's trust mark makes a player trusted, who may
     * then change the rules but not trust anyone; the mark shows through the row's data slot and the trusted player
     * keeps a row without a rule.
     */
    private static void trustAndAdd(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = NetworkGameTests.player(level, "trust_owner");
        ServerPlayer member = NetworkGameTests.player(level, "trust_member");
        ServerPlayer stranger = NetworkGameTests.player(level, "trust_stranger");
        BlockPos core = NetworkGameTests.controller(helper, 1);
        ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
        controller.setOwner(OwnerRecord.of(owner));
        BlockPos module = core.above();
        ControllerClusterGameTests.expansion(helper, module, ExpansionContent.SECURITY_MODULE);
        Vec3 at = helper.absolutePos(module).getCenter();
        owner.setPos(at);
        member.setPos(at);
        stranger.setPos(at);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(controller.secured(), "module"))
                .thenExecute(() -> {
                    SecurityMenu menu = menu(helper, controller, owner, module);
                    helper.assertTrue(!menu.addRule(stranger, member.getUUID(), "trust_member"), "stranger's add");
                    helper.assertTrue(controller.security().playerLevel(member.getUUID()) == null, "nothing added");
                    menu.addPlayer(owner, "not a name!");
                    helper.assertValueEqual(controller.security().players().size(), 0, "invalid name");
                    helper.assertTrue(menu.addRule(owner, owner.getUUID(), "trust_owner"), "owner's own row");
                    helper.assertValueEqual(controller.security().players().size(), 0, "no rule for the owner");
                    helper.assertTrue(menu.addRule(owner, member.getUUID(), "trust_member"), "owner's add");
                    helper.assertTrue(controller.security().playerLevel(member.getUUID()) == AccessLevel.VIEW,
                            "a view rule");

                    SecurityMenu rows = menu(helper, controller, owner, module);
                    int memberRow = row(helper, rows, member.getUUID());
                    helper.assertTrue(!rows.trusted(memberRow), "not trusted yet");
                    helper.assertTrue(!rows.clickMenuButton(owner, SecurityMenu.TRUST_BUTTON), "trusting everyone");
                    helper.assertTrue(!rows.clickMenuButton(member, SecurityMenu.TRUST_BUTTON + memberRow),
                            "untrusted member's mark");
                    helper.assertTrue(rows.clickMenuButton(owner, SecurityMenu.TRUST_BUTTON + memberRow), "trust");
                    helper.assertTrue(controller.ownerRecord().isTrusted(member.getUUID()), "trusted");
                    helper.assertTrue(rows.trusted(memberRow), "the mark");
                    helper.assertTrue(SecuritySettings.canEdit(member, controller.ownerRecord()), "member edits");
                    helper.assertTrue(rows.clickMenuButton(member, 0), "trusted member's click");
                    helper.assertValueEqual(controller.security().everyone(), AccessLevel.VIEW, "everyone cycled");
                    helper.assertTrue(!rows.clickMenuButton(member, SecurityMenu.TRUST_BUTTON + memberRow),
                            "a trusted player trusting");
                    // Cycle the member's rule away: from view through every higher level, then no rule. The count
                    // comes from the enum, so a new level does not break the test.
                    int clicks = AccessLevel.values().length - AccessLevel.VIEW.id();
                    for (int i = 0; i < clicks; i++) {
                        rows.clickMenuButton(owner, memberRow);
                    }
                    helper.assertTrue(controller.security().playerLevel(member.getUUID()) == null, "rule gone");
                    SecurityMenu again = menu(helper, controller, owner, module);
                    int kept = row(helper, again, member.getUUID());
                    helper.assertTrue(again.trusted(kept) && again.level(kept) == null, "a trusted row, no rule");
                    helper.assertTrue(again.clickMenuButton(owner, SecurityMenu.TRUST_BUTTON + kept), "distrust");
                    helper.assertTrue(!controller.ownerRecord().isTrusted(member.getUUID()), "distrusted");
                    helper.assertTrue(!again.stillValid(member), "the member's screen closes");
                }).thenSucceed();
    }

    /**
     * The P2P linker follows the security module's rules: a player with a deposit rule cannot link an owned end, one
     * with a full rule can, and without the module only the end's owner can.
     */
    private static void p2pLinker(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = NetworkGameTests.player(level, "p2p_owner");
        Player depositor = helper.makeMockPlayer(GameType.SURVIVAL);
        Player builder = helper.makeMockPlayer(GameType.SURVIVAL);
        helper.assertTrue(!depositor.getUUID().equals(builder.getUUID()), "mock players share an id");
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        BlockPos endPos = NetworkGameTests.at(2);
        helper.setBlock(endPos,
                P2PContent.block(P2PKind.ITEM).get().defaultBlockState().setValue(P2PBlock.FACING, Direction.SOUTH));
        P2PBlockEntity end = helper.getBlockEntity(endPos, P2PBlockEntity.class);
        end.setOwner(OwnerRecord.of(owner));
        BlockPos module = core.above();
        helper.startSequence()
                .thenWaitUntil(() -> helper
                        .assertTrue(end.deviceLink().network() == NetworkGameTests.state(helper, core), "end joined"))
                .thenExecute(() -> {
                    helper.assertValueEqual(link(end, builder), P2PRole.NONE, "builder without the module");
                    ControllerClusterGameTests.expansion(helper, module, ExpansionContent.SECURITY_MODULE);
                    NetworkGameTests.controllerEntity(helper, core).setSecurity(
                            SecuritySettings.DEFAULT.withPlayer(depositor.getUUID(), "d", AccessLevel.DEPOSIT)
                                    .withPlayer(builder.getUUID(), "b", AccessLevel.FULL));
                }).thenWaitUntil(() -> helper.assertTrue(NetworkGameTests.controllerEntity(helper, core).secured(),
                        "module active"))
                .thenExecute(() -> {
                    helper.assertValueEqual(link(end, depositor), P2PRole.NONE, "depositor links");
                    helper.assertValueEqual(link(end, builder), P2PRole.INPUT, "builder links");
                }).thenSucceed();
    }

    /** Sneak-uses a fresh linker on {@code end} as {@code player}; returns the end's role after. */
    private static P2PRole link(P2PBlockEntity end, Player player) {
        player.setShiftKeyDown(true);
        P2PLinkerItem.use(end, player, new ItemStack(P2PContent.LINKER.get()));
        return end.endpoint().role();
    }

    private static SecurityMenu menu(GameTestHelper helper, ControllerBlockEntity controller, ServerPlayer viewer,
            BlockPos module) {
        return SecurityMenu.forCore(1, controller, SecurityMenu.rows(controller, viewer), helper.absolutePos(module),
                Component.empty());
    }

    private static int row(GameTestHelper helper, SecurityMenu menu, UUID player) {
        for (int i = 0; i < menu.rows().size(); i++) {
            if (player.equals(menu.rows().get(i).player())) {
                return i;
            }
        }
        throw helper.assertionException(Component.literal("no row for " + player));
    }

    private static FakePlayer fake(ServerLevel level, String name) {
        return new FakePlayer(level, new GameProfile(UUID.randomUUID(), name));
    }

    private static void useOn(ItemStack book, ServerPlayer player, BlockPos at) {
        book.onItemUseFirst(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false)));
    }

    private static ItemStack written(Player player) {
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.is(Items.WRITTEN_BOOK)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
}
