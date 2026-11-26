package dev.silencebound.maxtech.storage.textures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The committed casing PNGs are what {@code storage/neoforge/src/textures/texture_kit.py} draws (MTX item 1), so a hand
 * edit or a forgotten regeneration fails the build. The kit is plain Python without Pillow; the test is skipped where
 * no Python is installed. Later MTX generators that support {@code --check} join {@link #SCRIPTS}.
 */
class TextureKitTest {
    static final List<String> SCRIPTS = List.of("texture_kit.py", "mtx_controller.py", "mtx_storage_blocks.py",
            "mtx_screens.py", "mtx_devices.py", "mtx_ports.py", "mtx_info.py");
    // python3 is a Microsoft Store stub on Windows and python is missing on some Linux images: take the first that
    // runs.
    private static final List<String> PYTHONS = List.of("python3", "python", "py");

    @Test
    void committedTexturesMatchTheGenerators() throws IOException, InterruptedException {
        Path root = repositoryRoot();
        Optional<String> python = python();
        assumeTrue(python.isPresent(), "no Python installed");
        for (String script : SCRIPTS) {
            Path file = root.resolve("storage/neoforge/src/textures").resolve(script);
            assertTrue(Files.isRegularFile(file), file.toString());
            Process process = new ProcessBuilder(python.get(), file.toString(), "--check").redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), script + " timed out");
            assertEquals(0, process.exitValue(), script + " --check:\n" + output);
        }
    }

    /**
     * The item icons meant to be symmetric (modules, cards, chips, cells and the rest of {@code generate_items.py}'s
     * {@code SYMMETRIC} list) mirror left to right, ignoring only the top-left light (owner, 2026-10-17).
     */
    @Test
    void symmetricItemIconsMirror() throws IOException, InterruptedException {
        Optional<String> python = python();
        assumeTrue(python.isPresent(), "no Python installed");
        Path file = repositoryRoot().resolve("storage/neoforge/src/textures/generate_items.py");
        assertTrue(Files.isRegularFile(file), file.toString());
        Process process = new ProcessBuilder(python.get(), "-B", file.toString(), "--check-symmetry")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "generate_items.py timed out");
        assertEquals(0, process.exitValue(), "generate_items.py --check-symmetry:\n" + output);
    }

    static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("settings.gradle.kts"))) {
                return p;
            }
        }
        throw new IllegalStateException("no settings.gradle.kts above " + dir);
    }

    static Optional<String> python() throws InterruptedException {
        for (String candidate : PYTHONS) {
            try {
                Process process = new ProcessBuilder(candidate, "-c", "import sys").redirectErrorStream(true).start();
                process.getInputStream().readAllBytes();
                if (process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    return Optional.of(candidate);
                }
                process.destroy();
            } catch (IOException e) {
                // Not installed: try the next name.
            }
        }
        return Optional.empty();
    }
}
