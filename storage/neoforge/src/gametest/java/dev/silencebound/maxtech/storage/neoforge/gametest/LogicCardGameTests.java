package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.logic.GraphText;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.logic.controller.ControllerTexts;
import dev.silencebound.maxtech.storage.logic.controller.GraphSharing;
import dev.silencebound.maxtech.storage.logic.controller.LogicCard;
import dev.silencebound.maxtech.storage.logic.controller.LogicCardItem;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.logic.controller.SharePayloads;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Logic cards and the in-game clipboard (M7L2 sharing): a rule written to a card from one controller and loaded on
 * another is the same rule, added switched off, and the card keeps it; loading needs the logic access level and a
 * written card, writing needs a blank one; Copy and Paste between controllers go through the import's rate limit.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicCardGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("logic_card_round_trip", LogicCardGameTests::roundTrip);
        register("logic_card_access", LogicCardGameTests::access);
        register("logic_card_limits", LogicCardGameTests::limits);
        register("logic_paste_rate_limit", LogicCardGameTests::pasteRateLimit);
    }

    private LogicCardGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("logic_card"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 300);
        }
    }

    private static int count(ServerPlayer player, boolean written) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.is(LogicContent.CARD.get()) && (LogicCardItem.card(stack) != null) == written) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * "To card" on graph 0 of one controller takes one of three blank cards and writes it (it no longer stacks); "From
     * card" on another controller adds the same graph and name, switched off, and the card keeps the rule.
     */
    private static void roundTrip(GameTestHelper helper) {
        LogicControllerBlockEntity from = LogicControllerGameTests.setUp(helper, "lc_owner");
        ServerPlayer owner = LogicShareGameTests.near(helper, "lc_owner");
        from.setOwner(OwnerRecord.of(owner));
        LogicControllerBlockEntity to = LogicShareGameTests.second(helper, owner);
        owner.getInventory().add(new ItemStack(LogicContent.CARD.get(), 3));
        helper.startSequence().thenWaitUntil(() -> {
            LogicControllerGameTests.network(helper, from);
            LogicControllerGameTests.network(helper, to);
        }).thenExecute(() -> {
            from.worker().setGraph(0, "Cobble alarm", LogicShareGameTests.graph());
            LogicShareGameTests.menu(owner, 3, from);
            SharePayloads.Exported written = LogicShareGameTests.present(helper,
                    GraphSharing.export(owner, 3, 0, SharePayloads.TO_CARD), "an answer");
            helper.assertTrue(written.ok(), "written: " + written.message());
            helper.assertValueEqual(written.message(), ControllerTexts.SHARE_CARD_WRITTEN, "line");
            helper.assertTrue(written.text().isEmpty(), "no text for a card");
            helper.assertValueEqual(count(owner, false), 2, "two blank cards left");
            helper.assertValueEqual(count(owner, true), 1, "one written card");
            ItemStack card = GraphSharing.findCard(owner, true);
            helper.assertValueEqual(card.getMaxStackSize(), 1, "a written card does not stack");
            LogicCard data = LogicCardItem.card(card);
            helper.assertTrue(data != null && data.name().equals("Cobble alarm"), "the card names the rule");

            LogicShareGameTests.menu(owner, 4, to);
            SharePayloads.Imported loaded = LogicShareGameTests.present(helper, GraphSharing.loadCard(owner, 4),
                    "an answer");
            helper.assertTrue(loaded.added(), "loaded: " + loaded.message() + " " + loaded.args());
            helper.assertValueEqual(loaded.message(), ControllerTexts.SHARE_CARD_LOADED, "line");
            helper.assertValueEqual(to.worker().size(), 1, "added");
            helper.assertValueEqual(to.worker().graph(0), from.worker().graph(0), "the same graph");
            helper.assertValueEqual(to.worker().name(0), "Cobble alarm", "the same name");
            helper.assertValueEqual(to.worker().status(0), LogicWorker.Status.DISABLED, "switched off");
            helper.assertValueEqual(count(owner, true), 1, "the card keeps its rule");
            LogicCardItem.wipe(card);
            helper.assertValueEqual(count(owner, false), 3, "wiped blank again");
        }).thenSucceed();
    }

    /**
     * Loading a card needs the logic access level (a crafter gets no answer and nothing is added); without a written
     * card, or writing without a blank one, the player is told so.
     */
    private static void access(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lc_boss");
        ServerPlayer owner = LogicShareGameTests.near(helper, "lc_boss");
        ServerPlayer crafter = LogicShareGameTests.near(helper, "lc_crafter");
        entity.setOwner(OwnerRecord.of(owner));
        BlockPos core = NetworkGameTests.at(1);
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        ControllerClusterGameTests.expansion(helper, core.above(), ExpansionContent.SECURITY_MODULE);
        NetworkGameTests.controllerEntity(helper, core).setSecurity(SecuritySettings.DEFAULT
                .withPlayer(crafter.getUUID(), crafter.getGameProfile().name(), AccessLevel.CRAFT));
        helper.startSequence().thenWaitUntil(() -> {
            LogicControllerGameTests.network(helper, entity);
            helper.assertTrue(NetworkGameTests.controllerEntity(helper, core).secured(), "module active");
        }).thenExecute(() -> {
            entity.worker().setGraph(0, "shared", LogicShareGameTests.graph());
            LogicShareGameTests.menu(owner, 5, entity);
            SharePayloads.Exported noBlank = LogicShareGameTests.present(helper,
                    GraphSharing.export(owner, 5, 0, SharePayloads.TO_CARD), "an answer");
            helper.assertTrue(!noBlank.ok(), "no blank card");
            helper.assertValueEqual(noBlank.message(), ControllerTexts.SHARE_NO_BLANK_CARD, "line");
            SharePayloads.Imported noCard = LogicShareGameTests.present(helper, GraphSharing.loadCard(owner, 5),
                    "an answer");
            helper.assertValueEqual(noCard.message(), ControllerTexts.SHARE_NO_CARD, "no written card");

            owner.getInventory().add(new ItemStack(LogicContent.CARD.get()));
        }).thenIdle(GraphSharing.EXPORT_INTERVAL + 1).thenExecute(() -> {
            helper.assertTrue(
                    LogicShareGameTests
                            .present(helper, GraphSharing.export(owner, 5, 0, SharePayloads.TO_CARD), "an answer").ok(),
                    "written");
            // Hand the written card to the crafter.
            ItemStack card = GraphSharing.findCard(owner, true);
            crafter.getInventory().add(card.copyAndClear());
            LogicShareGameTests.menu(crafter, 6, entity);
            helper.assertTrue(GraphSharing.loadCard(crafter, 6) == null, "craft access loads nothing");
            helper.assertTrue(GraphSharing.export(crafter, 6, 0, SharePayloads.TO_CARD) == null,
                    "craft access writes nothing");
            helper.assertValueEqual(entity.worker().size(), 1, "nothing added");

            // The positive control: with logic access the same card loads.
            NetworkGameTests.controllerEntity(helper, core).setSecurity(SecuritySettings.DEFAULT
                    .withPlayer(crafter.getUUID(), crafter.getGameProfile().name(), AccessLevel.LOGIC));
            SharePayloads.Imported loaded = LogicShareGameTests.present(helper, GraphSharing.loadCard(crafter, 6),
                    "logic access loads");
            helper.assertTrue(loaded.added(), "loaded: " + loaded.message() + " " + loaded.args());
            helper.assertValueEqual(entity.worker().size(), 2, "added");
            helper.assertValueEqual(entity.worker().graph(1), entity.worker().graph(0), "the card's graph");
            helper.assertValueEqual(entity.worker().status(1), LogicWorker.Status.DISABLED, "switched off");
        }).thenSucceed();
    }

    /**
     * A card whose data has more nodes than {@code logic.maxNodes}, or that would be longer as text than
     * {@code logic.maxImportLength}, is refused with the text import's lines and adds nothing.
     */
    private static void limits(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lc_limits");
        ServerPlayer owner = LogicShareGameTests.near(helper, "lc_limits");
        entity.setOwner(OwnerRecord.of(owner));
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            LogicShareGameTests.menu(owner, 2, entity);
            CompoundTag data = GraphText.encode(LogicKey.GRAPH_CODEC,
                    owner.registryAccess().createSerializationContext(NbtOps.INSTANCE), LogicShareGameTests.graph())
                    .orElseThrow();
            // Random letters hardly compress: well over the import length as text, ignored by the graph codec.
            StringBuilder padding = new StringBuilder();
            java.util.Random random = new java.util.Random(7);
            for (int i = 0; i < 2 * StorageServerConfig.LOGIC_MAX_IMPORT_LENGTH.getAsInt(); i++) {
                padding.append((char) ('!' + random.nextInt(90)));
            }
            data.putString("padding", padding.toString());
            ItemStack card = new ItemStack(LogicContent.CARD.get());
            LogicCardItem.write(card, new LogicCard("long", data));
            owner.getInventory().add(card);
            SharePayloads.Imported tooLong = LogicShareGameTests.present(helper, GraphSharing.loadCard(owner, 2),
                    "an answer");
            helper.assertValueEqual(tooLong.message(), GraphText.TOO_LONG, "too long as text");
            helper.assertValueEqual(entity.worker().size(), 0, "nothing added");
        }).thenIdle(GraphSharing.IMPORT_INTERVAL).thenExecute(() -> {
            ItemStack card = GraphSharing.findCard(owner, true);
            ListTag nodes = new ListTag();
            for (int i = 0; i <= StorageServerConfig.LOGIC_MAX_NODES.getAsInt(); i++) {
                CompoundTag node = new CompoundTag();
                node.putInt("id", i + 1);
                node.putString("type", "constant");
                nodes.add(node);
            }
            CompoundTag data = new CompoundTag();
            data.putInt("version", 1);
            data.put("nodes", nodes);
            LogicCardItem.write(card, new LogicCard("many", data));
            SharePayloads.Imported tooMany = LogicShareGameTests.present(helper, GraphSharing.loadCard(owner, 2),
                    "an answer");
            helper.assertValueEqual(tooMany.message(), GraphText.TOO_MANY_NODES, "too many nodes");
            helper.assertValueEqual(entity.worker().size(), 0, "nothing added");
        }).thenSucceed();
    }

    /**
     * Copy puts the graph's text on the in-game clipboard's answer; Paste on another controller adds it, a second Paste
     * right away is refused as too fast and adds nothing, and one after {@link GraphSharing#IMPORT_INTERVAL} ticks is
     * added.
     */
    private static void pasteRateLimit(GameTestHelper helper) {
        LogicControllerBlockEntity from = LogicControllerGameTests.setUp(helper, "lc_paster");
        ServerPlayer owner = LogicShareGameTests.near(helper, "lc_paster");
        from.setOwner(OwnerRecord.of(owner));
        LogicControllerBlockEntity to = LogicShareGameTests.second(helper, owner);
        String[] text = new String[1];
        helper.startSequence().thenWaitUntil(() -> {
            LogicControllerGameTests.network(helper, from);
            LogicControllerGameTests.network(helper, to);
        }).thenExecute(() -> {
            from.worker().setGraph(0, "Copied", LogicShareGameTests.graph());
            LogicShareGameTests.menu(owner, 7, from);
            SharePayloads.Exported copied = LogicShareGameTests.present(helper,
                    GraphSharing.export(owner, 7, 0, SharePayloads.TO_GAME), "an answer");
            helper.assertValueEqual(copied.target(), SharePayloads.TO_GAME, "for the in-game clipboard");
            helper.assertValueEqual(copied.message(), ControllerTexts.SHARE_COPIED_GAME, "line");
            text[0] = copied.text();

            LogicShareGameTests.menu(owner, 8, to);
            SharePayloads.Imported first = LogicShareGameTests.present(helper,
                    GraphSharing.importGraph(owner, 8, text[0]), "an answer");
            helper.assertTrue(first.added(), "pasted: " + first.message());
            SharePayloads.Imported second = LogicShareGameTests.present(helper,
                    GraphSharing.importGraph(owner, 8, text[0]), "an answer");
            helper.assertValueEqual(second.message(), ControllerTexts.SHARE_TOO_FAST, "too fast");
            SharePayloads.Imported card = LogicShareGameTests.present(helper, GraphSharing.loadCard(owner, 8),
                    "an answer");
            helper.assertValueEqual(card.message(), ControllerTexts.SHARE_TOO_FAST, "a card load shares the limit");
            helper.assertValueEqual(to.worker().size(), 1, "one pasted");
            helper.assertValueEqual(to.worker().graph(0), from.worker().graph(0), "the same graph");
        }).thenIdle(GraphSharing.IMPORT_INTERVAL).thenExecute(() -> {
            SharePayloads.Imported later = LogicShareGameTests.present(helper,
                    GraphSharing.importGraph(owner, 8, text[0]), "an answer");
            helper.assertTrue(later.added(), "pasted again: " + later.message());
            helper.assertValueEqual(to.worker().size(), 2, "two pasted");
            helper.assertValueEqual(to.worker().status(1), LogicWorker.Status.DISABLED, "switched off");
        }).thenSucceed();
    }
}
