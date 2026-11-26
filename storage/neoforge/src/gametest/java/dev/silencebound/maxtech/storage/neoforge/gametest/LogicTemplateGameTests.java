package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.logic.CompileResult;
import dev.silencebound.maxtech.storage.logic.LogicCompiler;
import dev.silencebound.maxtech.storage.logic.controller.ControllerTexts;
import dev.silencebound.maxtech.storage.logic.controller.GraphSharing;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.logic.controller.SharePayloads;
import dev.silencebound.maxtech.storage.logic.template.BuiltInTemplates;
import dev.silencebound.maxtech.storage.logic.template.LogicTemplate;
import dev.silencebound.maxtech.storage.logic.template.LogicTemplates;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Datapack rule templates (M7L2 sharing): the built-ins and the GameTest resources' template load (a broken file is
 * left out), each built-in compiles in game, the list answer carries them, a picked template is added switched off, and
 * {@code /reload} through a world datapack replaces a template and adds one, both gone again when the pack is (in its
 * own environment, so no other batch sees the reload).
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicTemplateGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String LIST = "logic_template_list";
    private static final String RELOAD = "logic_template_reload";
    private static final Identifier TEST = MaxtechStorage.id("test/gametest");
    private static final Identifier EXTRA = MaxtechStorage.id("test/extra");
    private static final String PACK = "maxtech_template_test";

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(LIST, StorageTestEnvironment.clearing(LogicTemplateGameTests::list));
        StorageTests.function(RELOAD, StorageTestEnvironment.clearing(LogicTemplateGameTests::reload));
    }

    private LogicTemplateGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("logic_templates"), StorageTestEnvironment.plain());
        StorageTests.test(event, environment, LIST, STRUCTURE, 300);
        Holder<TestEnvironmentDefinition<?>> reload = event
                .registerEnvironment(MaxtechStorage.id("logic_template_reload"), StorageTestEnvironment.plain());
        // Reloads take wall-clock time while test ticks run unthrottled, so this one gets many ticks.
        StorageTests.test(event, reload, RELOAD, STRUCTURE, 6000);
    }

    private static LogicTemplate<LogicKey> loaded(GameTestHelper helper, Identifier id) {
        return LogicTemplates.get(id)
                .orElseThrow(() -> helper.assertionException(Component.literal("template " + id + " not loaded")));
    }

    /**
     * Every built-in and the test template are loaded, the broken one is not; each built-in compiles without errors;
     * the list answer names them; Add puts the test template in row 0 switched off; an unknown id is refused.
     */
    private static void list(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lt_owner");
        ServerPlayer owner = LogicShareGameTests.near(helper, "lt_owner");
        entity.setOwner(OwnerRecord.of(owner));
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            for (String path : BuiltInTemplates.all(item -> item).keySet()) {
                LogicTemplate<LogicKey> template = loaded(helper, MaxtechStorage.id(path));
                CompileResult<LogicKey> result = LogicCompiler.compile(template.graph(),
                        StorageServerConfig.LOGIC_MAX_NODES.getAsInt());
                helper.assertTrue(result.errors().isEmpty(), path + " compiles: " + result.errors());
            }
            helper.assertValueEqual(loaded(helper, TEST).name(), "Test lamp", "the test template");
            helper.assertTrue(LogicTemplates.get(MaxtechStorage.id("test/broken")).isEmpty(), "broken file left out");

            LogicShareGameTests.menu(owner, 3, entity);
            SharePayloads.TemplateListed listed = LogicShareGameTests.present(helper, GraphSharing.templates(owner, 3),
                    "a list");
            helper.assertValueEqual(listed.templates().size(), LogicTemplates.all().size(), "every template");
            SharePayloads.TemplateInfo info = listed.templates().stream().filter(t -> t.id().equals(TEST)).findFirst()
                    .orElseThrow(() -> helper.assertionException(Component.literal("test template listed")));
            helper.assertValueEqual(info.category(), "testing", "category");
            helper.assertValueEqual(info.nodes(), 2, "nodes");
            helper.assertTrue(GraphSharing.templates(owner, 3) == null, "a second list right away gets no answer");

            SharePayloads.Imported added = LogicShareGameTests.present(helper, GraphSharing.addTemplate(owner, 3, TEST),
                    "an answer");
            helper.assertTrue(added.added(), "added: " + added.message() + " " + added.args());
            helper.assertValueEqual(added.message(), ControllerTexts.SHARE_TEMPLATE_ADDED, "line");
            helper.assertValueEqual(entity.worker().graph(0), loaded(helper, TEST).graph(), "the template's graph");
            helper.assertValueEqual(entity.worker().name(0), "Test lamp", "the template's name");
            helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.DISABLED, "switched off");
        }).thenIdle(GraphSharing.IMPORT_INTERVAL).thenExecute(() -> {
            SharePayloads.Imported unknown = LogicShareGameTests.present(helper,
                    GraphSharing.addTemplate(owner, 3, MaxtechStorage.id("test/nothing")), "an answer");
            helper.assertValueEqual(unknown.message(), ControllerTexts.SHARE_NO_TEMPLATE, "unknown template");
            helper.assertValueEqual(entity.worker().size(), 1, "nothing more added");
        }).thenSucceed();
    }

    /** {@code /reload} with a world datapack replaces the test template and adds one; without the pack both go back. */
    private static void reload(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Path pack = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK);
        delete(pack);
        PackRepository packs = server.getPackRepository();
        String id = "file/" + PACK;
        List<String> before = new ArrayList<>(packs.getSelectedIds());
        before.remove(id);
        List<CompletableFuture<Void>> reloads = new ArrayList<>();
        // A run that failed half-way may have left the pack on: switch it off first.
        helper.startSequence().thenExecute(() -> {
            if (packs.getSelectedIds().contains(id)) {
                packs.reload();
                reloads.add(server.reloadResources(before));
            } else {
                reloads.add(CompletableFuture.completedFuture(null));
            }
        }).thenWaitUntil(() -> helper.assertTrue(reloads.get(0).isDone(), "clean-up reload running"))
                .thenExecute(() -> {
                    helper.assertValueEqual(loaded(helper, TEST).name(), "Test lamp", "the jar's test template");
                    String graph = "{\"version\": 1, \"nodes\": [{\"id\": 1, \"type\": \"timer\"}]}";
                    write(pack.resolve("pack.mcmeta"),
                            "{\"pack\": {\"description\": \"template test\", \"min_format\": "
                                    + SharedConstants.DATA_PACK_FORMAT_MAJOR + ", \"max_format\": "
                                    + SharedConstants.DATA_PACK_FORMAT_MAJOR + "}}");
                    write(pack.resolve("data/maxtech_storage/maxtech_storage/logic_templates/test/gametest.json"),
                            "{\"name\": \"Reloaded lamp\", \"graph\": " + graph + "}");
                    write(pack.resolve("data/maxtech_storage/maxtech_storage/logic_templates/test/extra.json"),
                            "{\"name\": \"Extra\", \"category\": \"alerts\", \"graph\": " + graph + "}");
                    packs.reload();
                    List<String> with = new ArrayList<>(before);
                    with.add(id);
                    reloads.add(server.reloadResources(with));
                })
                .thenWaitUntil(() -> helper.assertTrue(reloads.size() > 1 && reloads.get(1).isDone(), "reload running"))
                .thenExecute(() -> {
                    helper.assertTrue(packs.getSelectedIds().contains(id), "the test pack is not on");
                    helper.assertValueEqual(loaded(helper, TEST).name(), "Reloaded lamp", "replaced by the pack");
                    helper.assertValueEqual(loaded(helper, TEST).graph().nodes().size(), 1, "the pack's graph");
                    helper.assertValueEqual(loaded(helper, EXTRA).category(), "alerts", "added by the pack");
                    delete(pack);
                    packs.reload();
                    reloads.add(server.reloadResources(before));
                })
                .thenWaitUntil(() -> helper.assertTrue(reloads.size() > 2 && reloads.get(2).isDone(), "reload running"))
                .thenExecute(() -> {
                    helper.assertValueEqual(loaded(helper, TEST).name(), "Test lamp", "back to the jar's");
                    helper.assertTrue(LogicTemplates.get(EXTRA).isEmpty(), "the pack's extra template is gone");
                }).thenSucceed();
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path folder) {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
