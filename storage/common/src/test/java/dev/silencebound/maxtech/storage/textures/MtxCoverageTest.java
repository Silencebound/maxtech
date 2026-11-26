package dev.silencebound.maxtech.storage.textures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every Storage block texture is in the family style 2a (MTX final pass, owner 2026-10-17): each
 * {@code maxtech_storage:block/...} texture a generated model references, and each PNG under {@code textures/block}, is
 * drawn by one of the MTX generators ({@link TextureKitTest#SCRIPTS}, asked with {@code --list}) or is on
 * {@link #ALLOWED}, the cables, ores and crystals {@code generate.py} still draws. So an old light grey texture can't
 * come back unnoticed. Skipped where no Python is installed, like {@link TextureKitTest}.
 */
class MtxCoverageTest {
    private static final String STORAGE = "storage/common/src";
    private static final Path MODELS = Path.of(STORAGE, "generated/resources/assets/maxtech_storage/models");
    private static final Path TEXTURES = Path.of(STORAGE, "main/resources/assets/maxtech_storage/textures");
    /** Cable sprites: the cable look is outside MTX. */
    private static final String CABLE_PREFIX = "block/cable/";
    /** Ore overlays and crystals, drawn by {@code generate.py}: natural blocks, outside MTX. */
    private static final Set<String> ALLOWED = Set.of("block/lumen_quartz_ore_overlay", "block/tantalite_ore_overlay",
            "block/ember_quartz_ore_overlay", "block/data_crystal_block", "block/budding_data_crystal",
            "block/void_crystal_block", "block/budding_void_crystal", "block/small_data_crystal_bud",
            "block/medium_data_crystal_bud", "block/large_data_crystal_bud", "block/data_crystal_cluster",
            "block/small_void_crystal_bud", "block/medium_void_crystal_bud", "block/large_void_crystal_bud",
            "block/void_crystal_cluster");
    /** A texture value in a model: any key but the model references {@code parent} and {@code model}. */
    private static final Pattern TEXTURE = Pattern
            .compile("\"(\\w+)\"\\s*:\\s*\"maxtech_storage:(block/[a-z0-9_/]+)\"");

    @Test
    void everyBlockTextureIsDrawnByAnMtxGenerator() throws IOException, InterruptedException {
        Path root = TextureKitTest.repositoryRoot();
        Optional<String> python = TextureKitTest.python();
        assumeTrue(python.isPresent(), "no Python installed");
        Set<String> owned = new HashSet<>();
        for (String script : TextureKitTest.SCRIPTS) {
            owned.addAll(list(python.get(), root.resolve("storage/neoforge/src/textures").resolve(script)));
        }
        Set<String> bad = new TreeSet<>();
        List<Path> models;
        try (Stream<Path> files = Files.walk(root.resolve(MODELS))) {
            models = files.filter(f -> f.toString().endsWith(".json")).toList();
        }
        assertTrue(models.size() > 100, "generated models not found under " + root.resolve(MODELS));
        for (Path model : models) {
            Matcher m = TEXTURE.matcher(Files.readString(model, StandardCharsets.UTF_8));
            while (m.find()) {
                if (!m.group(1).equals("parent") && !m.group(1).equals("model") && !allowed(m.group(2), owned)) {
                    bad.add(m.group(2) + " (in " + root.resolve(MODELS).relativize(model) + ")");
                }
            }
        }
        Path textures = root.resolve(TEXTURES);
        try (Stream<Path> files = Files.walk(textures.resolve("block"))) {
            for (Path png : files.filter(f -> f.toString().endsWith(".png")).toList()) {
                String path = textures.relativize(png).toString().replace('\\', '/');
                path = path.substring(0, path.length() - ".png".length());
                if (!allowed(path, owned)) {
                    bad.add(path + ".png");
                }
            }
        }
        assertEquals(Set.of(), bad,
                "block textures not drawn by an MTX script (storage/neoforge/src/textures/mtx_*.py, texture_kit.py)"
                        + " and not on the cable, ore and crystal allow-list");
    }

    private static boolean allowed(String path, Set<String> owned) {
        return owned.contains(path) || path.startsWith(CABLE_PREFIX) || ALLOWED.contains(path);
    }

    private static List<String> list(String python, Path script) throws IOException, InterruptedException {
        assertTrue(Files.isRegularFile(script), script.toString());
        Process process = new ProcessBuilder(python, script.toString(), "--list").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), script + " timed out");
        assertEquals(0, process.exitValue(), script + " --list:\n" + output);
        return output.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }
}
