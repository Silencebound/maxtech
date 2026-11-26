package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NodeTypesTest {
    @Test
    void everyOpHasANodeType() {
        Set<Op> ops = new HashSet<>();
        for (NodeType type : NodeTypes.all()) {
            ops.add(type.op());
        }
        // Script node types come from packs (ScriptNodesTest), so no built-in type has that op.
        Set<Op> expected = new HashSet<>(Set.of(Op.values()));
        expected.remove(Op.SCRIPT);
        assertEquals(expected, ops);
    }

    @Test
    void portNamesAreUniqueAndInputSettingsMatchTheirPort() {
        for (NodeType type : NodeTypes.all()) {
            Set<String> inputs = new HashSet<>();
            for (Port port : type.inputs()) {
                assertTrue(inputs.add(port.name()), type + " has two inputs called " + port.name());
                Optional<SettingSpec> spec = type.setting(port.name());
                if (spec.isPresent() && port.type() == PortType.ITEM) {
                    // An item input falls back to an item, fluid or tag setting.
                    assertTrue(spec.get().kind().holdsKey(), type + "." + port.name());
                } else if (spec.isPresent()) {
                    SettingKind expected = switch (port.type()) {
                        case NUMBER -> SettingKind.NUMBER;
                        case ITEM -> SettingKind.ITEM;
                        case LABEL -> SettingKind.LABEL;
                        default ->
                            throw new AssertionError(type + "." + port.name() + " cannot fall back to a setting");
                    };
                    assertEquals(expected, spec.get().kind(), type + "." + port.name());
                }
            }
            if (type.op() != Op.CONSTANT) {
                Set<String> outputs = new HashSet<>();
                for (Port port : type.outputs(NodeSettings.empty())) {
                    assertTrue(outputs.add(port.name()), type + " has two outputs called " + port.name());
                }
            }
            assertTrue(type.op().watch() == null || type.inputs().stream().noneMatch(p -> !p.type().fixed()),
                    type + " reads the network, so it takes no runtime inputs");
            assertEquals(type.op().acts(), type.outputs(NodeSettings.empty()).isEmpty(),
                    type + ": actions and only actions have no outputs");
        }
    }

    @Test
    void constantOutputFollowsItsKind() {
        NodeSettings<String> settings = NodeSettings.empty();
        assertEquals(PortType.NUMBER, NodeTypes.CONSTANT.outputs(settings).getFirst().type());
        for (PortType type : new PortType[]{PortType.YES_NO, PortType.ITEM, PortType.LABEL}) {
            assertEquals(type, NodeTypes.CONSTANT.outputs(settings.withText("kind", type.id())).getFirst().type());
        }
    }

    @Test
    void everyKeyHasPlainEnglish() {
        Map<String, String> english = LogicLang.english();
        for (NodeType type : NodeTypes.all()) {
            assertNotNull(english.get(type.nameKey()), type.id());
            assertNotNull(english.get(type.category().nameKey()), type.id());
            for (Port port : type.inputs()) {
                assertNotNull(english.get(port.nameKey()), port.name());
            }
            for (SettingSpec spec : type.settings()) {
                assertNotNull(english.get(spec.nameKey()), spec.name());
                for (String choice : spec.choices()) {
                    assertNotNull(english.get(SettingSpec.choiceKey(choice)), choice);
                }
            }
        }
        for (PortType type : PortType.values()) {
            assertNotNull(english.get(type.connectKey()));
            assertNotNull(english.get(type.wrongTypeKey()));
        }
        for (String text : english.values()) {
            assertFalse(text.isBlank());
        }
    }

    @Test
    void everyTypeHasADescriptionAndASentenceThatFormats() {
        Map<String, String> english = LogicLang.english();
        java.util.function.UnaryOperator<String> lang = key -> english.getOrDefault(key, key);
        for (NodeType type : NodeTypes.all()) {
            assertNotNull(english.get(type.descriptionKey()), type + " has no description");
            String template = english.get(type.sentenceKey());
            assertNotNull(template, type + " has no sentence");
            Set<String> names = new HashSet<>();
            type.settings().forEach(spec -> names.add(spec.name()));
            type.inputs().forEach(port -> names.add(port.name()));
            for (String name : NodeSentence.placeholders(template)) {
                assertTrue(names.contains(name), type + "'s sentence names " + name + ", which it does not have");
            }
            String sentence = NodeSentence.of(type, NodeSettings.<String>empty(), lang, item -> item);
            assertFalse(sentence.contains("{") || sentence.contains("}"), type + ": " + sentence);
            assertFalse(sentence.contains("null"), type + ": " + sentence);
        }
        assertEquals("every 30 seconds", NodeSentence.of(NodeTypes.TIMER,
                NodeSettings.<String>empty().withNumber("interval", 30), lang, item -> item));
        assertEquals("7 Divide 2", NodeSentence.of(NodeTypes.MATH,
                NodeSettings.<String>empty().withText("operation", "divide").withNumber("a", 7).withNumber("b", 2),
                lang, item -> item));
        assertEquals("when the count of cobble in Label goes Above 0", NodeSentence.of(NodeTypes.COUNT_CROSSES,
                NodeSettings.<String>empty().withItem("item", "cobble"), lang, item -> item));
    }

    @Test
    void theCommonSetIsSmallAndCoversEveryKindOfWork() {
        Set<NodeCategory> categories = new HashSet<>();
        int common = 0;
        for (NodeType type : NodeTypes.all()) {
            if (type.common()) {
                common++;
                categories.add(type.category());
            }
        }
        assertTrue(common >= 12 && common <= 18, "about 15 common types, not " + common);
        assertEquals(Set.of(NodeCategory.values()), categories, "every palette group has a common type");
        assertTrue(NodeTypes.TIMER.common() && NodeTypes.COUNT_CROSSES.common() && NodeTypes.NOTIFY.common());
        assertFalse(NodeTypes.STATS.common());
    }

    @Test
    void memoryFitsTheSavedFormat() {
        for (NodeType type : NodeTypes.all()) {
            assertTrue(type.memory() <= LogicMemory.MAX_VALUES, type + " remembers more than a save holds");
        }
    }

    @Test
    void idsResolve() {
        for (NodeType type : NodeTypes.all()) {
            assertEquals(type, NodeTypes.byId(type.id()).orElseThrow());
        }
        assertTrue(NodeTypes.byId("nope").isEmpty());
    }
}
