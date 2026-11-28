package dev.silencebound.maxtech.lib.shim.saving;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import net.minecraft.resources.ResourceKey;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 26.1's {@code net.minecraft.util.ProblemReporter} (1.21.1's takes strings instead of path elements and problems):
 * where serialization problems go, with the path of the field they happened at.
 */
public interface ProblemReporter {
    ProblemReporter DISCARDING = new ProblemReporter() {
        @Override
        public ProblemReporter forChild(PathElement path) {
            return this;
        }

        @Override
        public void report(Problem problem) {
        }
    };

    ProblemReporter forChild(PathElement path);

    void report(Problem problem);

    /** Collects problems with their paths; children share the parent's set. */
    class Collector implements ProblemReporter {
        public static final PathElement EMPTY_ROOT = () -> "";
        private final @Nullable Collector parent;
        private final PathElement element;
        private final Set<Entry> problems;

        public Collector() {
            this(EMPTY_ROOT);
        }

        public Collector(PathElement root) {
            this.parent = null;
            this.element = root;
            this.problems = new LinkedHashSet<>();
        }

        private Collector(Collector parent, PathElement element) {
            this.parent = parent;
            this.element = element;
            this.problems = parent.problems;
        }

        @Override
        public ProblemReporter forChild(PathElement path) {
            return new Collector(this, path);
        }

        @Override
        public void report(Problem problem) {
            problems.add(new Entry(this, problem));
        }

        public boolean isEmpty() {
            return problems.isEmpty();
        }

        /** Calls {@code output} with each problem's full path (root first) and the problem. */
        public void forEach(BiConsumer<String, Problem> output) {
            for (Entry entry : problems) {
                output.accept(entry.source.path(null), entry.problem);
            }
        }

        public String getReport() {
            Multimap<String, Problem> grouped = LinkedHashMultimap.create();
            forEach(grouped::put);
            return grouped.asMap().entrySet().stream()
                    .map(e -> " at " + e.getKey() + ": "
                            + e.getValue().stream().map(Problem::description).collect(Collectors.joining("; ")))
                    .collect(Collectors.joining("\n"));
        }

        /** The problems as an indented tree below this collector's element. */
        public String getTreeReport() {
            TreeNode root = new TreeNode(element);
            for (Entry entry : problems) {
                List<PathElement> path = new ArrayList<>();
                for (Collector c = entry.source; c != this && c != null; c = c.parent) {
                    path.add(c.element);
                }
                TreeNode node = root;
                for (int i = path.size() - 1; i >= 0; i--) {
                    node = node.children.computeIfAbsent(path.get(i), TreeNode::new);
                }
                node.problems.add(entry.problem);
            }
            return String.join("\n", root.lines());
        }

        private String path(@Nullable Collector stop) {
            List<PathElement> elements = new ArrayList<>();
            for (Collector c = this; c != stop && c != null; c = c.parent) {
                elements.add(c.element);
            }
            StringBuilder out = new StringBuilder();
            for (int i = elements.size() - 1; i >= 0; i--) {
                out.append(elements.get(i).get());
            }
            return out.toString();
        }

        private record Entry(Collector source, Problem problem) {
        }

        private record TreeNode(PathElement element, List<Problem> problems, Map<PathElement, TreeNode> children) {
            TreeNode(PathElement element) {
                this(element, new ArrayList<>(), new LinkedHashMap<>());
            }

            List<String> lines() {
                if (problems.isEmpty() && children.isEmpty()) {
                    return List.of();
                }
                if (problems.isEmpty() && children.size() == 1) {
                    List<String> lines = new ArrayList<>(children.values().iterator().next().lines());
                    lines.set(0, element.get() + lines.get(0));
                    return lines;
                }
                if (problems.size() == 1 && children.isEmpty()) {
                    return List.of(element.get() + ": " + problems.get(0).description());
                }
                List<String> lines = new ArrayList<>();
                lines.add(element.get() + ":");
                children.values().forEach(child -> child.lines().forEach(line -> lines.add("  " + line)));
                problems.forEach(problem -> lines.add("  " + problem.description()));
                return lines;
            }
        }
    }

    /** A collector that logs what it collected when closed. */
    class ScopedCollector extends Collector implements AutoCloseable {
        private final Logger logger;

        public ScopedCollector(Logger logger) {
            this.logger = logger;
        }

        public ScopedCollector(PathElement root, Logger logger) {
            super(root);
            this.logger = logger;
        }

        @Override
        public void close() {
            if (!isEmpty()) {
                logger.warn("[{}] Serialization errors:\n{}", logger.getName(), getTreeReport());
            }
        }
    }

    @FunctionalInterface
    interface PathElement {
        String get();
    }

    interface Problem {
        String description();
    }

    record ElementReferencePathElement(ResourceKey<?> id) implements PathElement {
        @Override
        public String get() {
            return "->{" + id.location() + "@" + id.registry() + "}";
        }
    }

    record FieldPathElement(String name) implements PathElement {
        @Override
        public String get() {
            return "." + name;
        }
    }

    record IndexedFieldPathElement(String name, int index) implements PathElement {
        @Override
        public String get() {
            return "." + name + "[" + index + "]";
        }
    }

    record IndexedPathElement(int index) implements PathElement {
        @Override
        public String get() {
            return "[" + index + "]";
        }
    }

    record MapEntryPathElement(String name, String key) implements PathElement {
        @Override
        public String get() {
            return "." + name + "[" + key + "]";
        }
    }

    record RootElementPathElement(ResourceKey<?> id) implements PathElement {
        @Override
        public String get() {
            return "{" + id.location() + "@" + id.registry() + "}";
        }
    }

    record RootFieldPathElement(String name) implements PathElement {
        @Override
        public String get() {
            return name;
        }
    }
}
