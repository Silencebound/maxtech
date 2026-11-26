package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.conduit.ConduitData;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.dev.GuideScenes;
import dev.silencebound.maxtech.storage.dev.SceneSites;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The guide's scene structures ({@link GuideScenes}) against the game.
 * <ul>
 * <li>{@code guide_scene_build_<scene>}: builds the scene with the real game, waits until it has settled and compares
 * the capture with the shipped file. With the environment variable {@code MAXTECH_GUIDE_SCENES=write} it writes the
 * file into the checkout instead; rerun without it to check.</li>
 * <li>{@code guide_scene_files}: every structure file beside the pages is a known scene, loads as a vanilla structure,
 * fits the scene viewer, names only Storage and vanilla blocks and holds no UUIDs or owners.</li>
 * <li>{@code guide_scene_cables}: a scene's level never runs a conduit's {@code onLoad} or the network logic, so a
 * cable draws only the masks saved in the file; this places each file's blocks without that data, lets the game work
 * the masks out, and checks they equal the saved ones.</li>
 * </ul>
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class GuideSceneGameTests {
    private static final Identifier ROOM = MaxtechStorage.id("empty_16x12x16");
    private static final String CABLES = "guide_scene_cables";
    private static final String FILES = "guide_scene_files";
    private static final String[] MASKS = {"connections", "conduit_faces", "block_faces"};
    /** Where a scene is built in the test room; the energy source goes one block under it. */
    private static final BlockPos ORIGIN = new BlockPos(2, 3, 2);
    /** Ticks a scene's capture must stay the same before it counts as settled. */
    private static final int SETTLED_TICKS = 40;
    /** The biggest box a scene may fill, so it stays readable in the guide's scene viewer. */
    private static final int MAX_SIDE = 8;
    private static final Set<String> NAMESPACES = Set.of("minecraft", MaxtechStorage.MOD_ID);
    private static final String GUIDE_FOLDER = "storage/common/src/main/resources/assets/maxtech_storage/guides/"
            + "maxtech_storage/guide";

    static {
        StorageTestEnvironment.init();
        StorageTests.function(CABLES, StorageTestEnvironment.clearing(GuideSceneGameTests::cables));
        StorageTests.function(FILES, GuideSceneGameTests::files);
        for (GuideScenes.Scene scene : GuideScenes.Scene.values()) {
            StorageTests.function(name(scene), StorageTestEnvironment.clearing(helper -> generate(helper, scene)));
        }
    }

    private GuideSceneGameTests() {
    }

    private static String name(GuideScenes.Scene scene) {
        return "guide_scene_build_" + scene.name().toLowerCase(Locale.ROOT);
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        var environment = event.registerEnvironment(MaxtechStorage.id("guide_scenes"), StorageTestEnvironment.plain());
        StorageTests.test(event, environment, CABLES, ROOM, 200);
        StorageTests.test(event, environment, FILES, StorageTests.SMALL, 20);
        for (GuideScenes.Scene scene : GuideScenes.Scene.values()) {
            StorageTests.test(event, environment, name(scene), ROOM, 600);
        }
    }

    /** The structure files shipped beside the guide pages, by file name. */
    private static Map<String, byte[]> shipped(GameTestHelper helper) {
        Map<Identifier, byte[]> assets = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        GuideMeGameTests.readGuideFolder(assets, problems);
        helper.assertTrue(problems.isEmpty(), "guide folder: " + problems);
        Map<String, byte[]> files = new TreeMap<>();
        for (Map.Entry<Identifier, byte[]> asset : assets.entrySet()) {
            if (asset.getKey().getPath().endsWith(".nbt")) {
                files.put(asset.getKey().getPath(), asset.getValue());
            }
        }
        return files;
    }

    private static CompoundTag read(byte[] bytes) {
        try {
            return NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void generate(GameTestHelper helper, GuideScenes.Scene scene) {
        BlockPos origin = helper.absolutePos(ORIGIN);
        List<String> warnings = GuideScenes.build(helper.getLevel(), scene, origin);
        helper.assertTrue(warnings.isEmpty(), scene.file() + " build warnings: " + warnings);
        CompoundTag[] last = {new CompoundTag()};
        int[] same = {0};
        helper.startSequence().thenWaitUntil(() -> {
            CompoundTag now = GuideScenes.capture(helper.getLevel(), scene, origin);
            same[0] = now.equals(last[0]) ? same[0] + 1 : 0;
            last[0] = now;
            if (same[0] < SETTLED_TICKS) {
                throw helper.assertionException(Component.literal(scene.file() + " has not settled"));
            }
        }).thenExecute(() -> {
            CompoundTag built = last[0];
            List<String> problems = new ArrayList<>();
            check(built, problems);
            helper.assertValueEqual(List.of(), problems, scene.file() + " problems");
            if ("write".equals(System.getenv("MAXTECH_GUIDE_SCENES"))) {
                write(helper, scene, built);
                return;
            }
            byte[] file = shipped(helper).get(scene.file());
            if (file == null) {
                throw helper.assertionException(
                        Component.literal(scene.file() + " is not shipped; run with MAXTECH_GUIDE_SCENES=write"));
            }
            helper.assertTrue(read(file).equals(built), scene.file() + " differs from what the game builds now: "
                    + NbtUtils.prettyPrint(built, false) + "; run with MAXTECH_GUIDE_SCENES=write and commit it");
        }).thenSucceed();
    }

    private static void write(GameTestHelper helper, GuideScenes.Scene scene, CompoundTag built) {
        Optional<Path> root = SceneSites.repoRoot(Path.of(""));
        helper.assertTrue(root.isPresent(), "no checkout above " + Path.of("").toAbsolutePath());
        Path file = root.orElseThrow().resolve(GUIDE_FOLDER).resolve(scene.file());
        try {
            NbtIo.writeCompressed(built, file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void files(GameTestHelper helper) {
        Map<String, byte[]> files = shipped(helper);
        List<String> problems = new ArrayList<>();
        for (GuideScenes.Scene scene : GuideScenes.Scene.values()) {
            if (!files.containsKey(scene.file())) {
                problems.add(scene.file() + " is not shipped");
            }
        }
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String name = entry.getKey();
            boolean known = false;
            for (GuideScenes.Scene scene : GuideScenes.Scene.values()) {
                known |= scene.file().equals(name);
            }
            if (!known) {
                problems.add(name + " is no scene GuideScenes builds");
            }
            CompoundTag root = read(entry.getValue());
            List<String> found = new ArrayList<>();
            check(root, found);
            for (String problem : found) {
                problems.add(name + ": " + problem);
            }
            StructureTemplate template = new StructureTemplate();
            template.load(helper.getLevel().holderLookup(Registries.BLOCK), root);
            int blocks = root.getListOrEmpty("blocks").size();
            if (blocks == 0 || template.getSize().getX() < 1) {
                problems.add(name + " is empty");
            }
        }
        helper.assertValueEqual(List.of(), problems, "guide scene files");
        helper.succeed();
    }

    /** A structure's size, block ids and block entity data: small, only Storage and vanilla, no UUIDs or owners. */
    private static void check(CompoundTag root, List<String> problems) {
        ListTag size = root.getListOrEmpty("size");
        for (int i = 0; i < 3; i++) {
            int side = size.getIntOr(i, 0);
            if (side < 1 || side > MAX_SIDE) {
                problems.add("size " + size + " is outside 1.." + MAX_SIDE);
                break;
            }
        }
        if (!root.getListOrEmpty("entities").isEmpty()) {
            problems.add("holds entities");
        }
        ListTag palette = root.getListOrEmpty("palette");
        for (int i = 0; i < palette.size(); i++) {
            String name = palette.getCompoundOrEmpty(i).getStringOr("Name", "");
            Identifier id = Identifier.tryParse(name);
            if (id == null || !NAMESPACES.contains(id.getNamespace())
                    || BuiltInRegistries.BLOCK.getOptional(id).isEmpty()) {
                problems.add("block " + name + " is no Storage or vanilla block");
            }
        }
        ListTag blocks = root.getListOrEmpty("blocks");
        for (int i = 0; i < blocks.size(); i++) {
            String path = "blocks[" + i + "].nbt";
            blocks.getCompoundOrEmpty(i).getCompound("nbt").ifPresent(nbt -> noPlayers(nbt, path, problems));
        }
    }

    /** Fails on anything that looks like a player or owner: a UUID (four ints) or a key naming an owner or UUID. */
    private static void noPlayers(Tag tag, String path, List<String> problems) {
        if (tag instanceof IntArrayTag ints && ints.size() == 4) {
            problems.add(path + " looks like a UUID");
        } else if (tag instanceof CompoundTag compound) {
            for (String key : compound.keySet()) {
                String lower = key.toLowerCase(Locale.ROOT);
                if (lower.contains("owner") || lower.contains("uuid") || lower.contains("player")) {
                    problems.add(path + "." + key + " names a player or owner");
                }
                Tag child = compound.get(key);
                if (child != null) {
                    noPlayers(child, path + "." + key, problems);
                }
            }
        } else if (tag instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                noPlayers(list.get(i), path + "[" + i + "]", problems);
            }
        }
    }

    /**
     * Places every file's blocks without their data, side by side in the room, and once the conduits have worked out
     * their masks compares them with the saved ones. Slot {@code i} sits at x 0 or 8, z 0, 4, 8 or 12 and y 1 or 6, so
     * no two files touch while each is at most 7 by 4 by 3.
     */
    private static void cables(GameTestHelper helper) {
        Map<String, byte[]> files = shipped(helper);
        helper.assertTrue(!files.isEmpty() && files.size() <= 16, "scene files: " + files.keySet());
        Map<BlockPos, String> names = new TreeMap<>();
        Map<BlockPos, CompoundTag> saved = new TreeMap<>();
        int slot = 0;
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            BlockPos base = new BlockPos(slot % 2 * 8, 1 + slot / 8 * 5, slot / 2 % 4 * 4);
            slot++;
            CompoundTag root = read(entry.getValue());
            ListTag palette = root.getListOrEmpty("palette");
            List<BlockState> states = new ArrayList<>();
            for (int i = 0; i < palette.size(); i++) {
                states.add(NbtUtils.readBlockState(helper.getLevel().holderLookup(Registries.BLOCK),
                        palette.getCompoundOrEmpty(i)));
            }
            ListTag blocks = root.getListOrEmpty("blocks");
            for (int i = 0; i < blocks.size(); i++) {
                CompoundTag block = blocks.getCompoundOrEmpty(i);
                ListTag list = block.getListOrEmpty("pos");
                BlockPos pos = base.offset(list.getIntOr(0, 0), list.getIntOr(1, 0), list.getIntOr(2, 0));
                helper.setBlock(pos, states.get(block.getIntOr("state", 0)));
                names.put(pos, entry.getKey() + " " + pos.subtract(base).toShortString());
                saved.put(pos, block.getCompoundOrEmpty("nbt"));
            }
        }
        helper.succeedWhen(() -> {
            List<String> wrong = new ArrayList<>();
            int cables = 0;
            for (Map.Entry<BlockPos, CompoundTag> entry : saved.entrySet()) {
                if (!(helper.getLevel()
                        .getBlockEntity(helper.absolutePos(entry.getKey())) instanceof ConduitBlockEntity be)) {
                    continue;
                }
                cables++;
                ConduitData conduit = be.conduit();
                int[] live = {conduit.connections(), conduit.conduitFaces(), conduit.blockFaces()};
                for (int m = 0; m < MASKS.length; m++) {
                    int file = entry.getValue().getByteOr(MASKS[m], (byte) 0);
                    if (file != live[m]) {
                        wrong.add(names.get(entry.getKey()) + " " + MASKS[m] + " saved " + file + ", game " + live[m]);
                    }
                }
            }
            helper.assertTrue(cables > 0, "the scenes have cables");
            helper.assertTrue(wrong.isEmpty(), "scene cable masks: " + wrong);
        });
    }
}
