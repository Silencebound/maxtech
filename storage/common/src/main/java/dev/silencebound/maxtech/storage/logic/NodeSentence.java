package dev.silencebound.maxtech.storage.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Fills in a node type's sentence template ({@link NodeType#sentenceKey()}): each {@code {name}} names one of the
 * type's settings or inputs. The controller's card view ({@link RuleCards}) fills them its own way. Pure; safe on any
 * thread.
 */
public final class NodeSentence {
    private NodeSentence() {
    }

    /** The placeholder names in {@code template}, in order, repeats included. */
    public static List<String> placeholders(String template) {
        List<String> names = new ArrayList<>();
        int at = 0;
        while (true) {
            int open = template.indexOf('{', at);
            if (open < 0) {
                return names;
            }
            int close = template.indexOf('}', open);
            if (close < 0) {
                return names;
            }
            names.add(template.substring(open + 1, close));
            at = close + 1;
        }
    }

    /** {@code template} with every {@code {name}} replaced by {@code value.apply(name)}; unclosed braces stay text. */
    public static String fill(String template, Function<String, String> value) {
        StringBuilder out = new StringBuilder(template.length() + 16);
        int at = 0;
        while (true) {
            int open = template.indexOf('{', at);
            int close = open < 0 ? -1 : template.indexOf('}', open);
            if (close < 0) {
                out.append(template, at, template.length());
                return out.toString();
            }
            out.append(template, at, open);
            out.append(value.apply(template.substring(open + 1, close)));
            at = close + 1;
        }
    }

    /**
     * The sentence of a node of {@code type} with {@code settings}: settings by their value (a choice by its name, an
     * unset item or text as the setting's name) and inputs without a setting by the input's name. {@code lang} turns a
     * lang key into text; {@code item} names an item.
     */
    public static <K> String of(NodeType type, NodeSettings<K> settings, UnaryOperator<String> lang,
            Function<K, String> item) {
        return fill(lang.apply(NodeTypes.sentenceKey(type, settings)), name -> value(type, settings, name, lang, item));
    }

    private static <K> String value(NodeType type, NodeSettings<K> settings, String name, UnaryOperator<String> lang,
            Function<K, String> item) {
        int channel = NodeTypes.channel(type, settings);
        if (channel >= 0 && name.equals(NodeTypes.LABEL_NAME)) {
            return lang.apply(NodeTypes.channelPhraseKey(channel));
        }
        SettingSpec spec = type.setting(name).orElse(null);
        if (spec == null) {
            for (Port port : type.inputs()) {
                if (port.name().equals(name)) {
                    return lang.apply(port.nameKey());
                }
            }
            return name;
        }
        return switch (spec.kind()) {
            case NUMBER -> Long.toString(spec.clamp(settings.number(name).orElse(spec.defaultValue())));
            case FLAG -> settings.flag(name).orElse(spec.defaultValue() != 0)
                    ? lang.apply(LogicLang.WORD_YES)
                    : lang.apply(LogicLang.WORD_NO);
            case CHOICE -> {
                int index = spec.choiceIndex(settings.text(name).orElse(""));
                yield lang.apply(
                        SettingSpec.choiceKey(spec.choices().get(Math.clamp(index, 0, spec.choices().size() - 1))));
            }
            case TEXT, LABEL -> settings.text(name).filter(text -> !text.isBlank()).orElse(lang.apply(spec.nameKey()));
            case ITEM, FLUID, TAG -> settings.item(name).map(item).orElse(lang.apply(spec.nameKey()));
        };
    }
}
