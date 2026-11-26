package dev.silencebound.maxtech.storage.logic.template;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * A rule shipped in a datapack (M7L2 sharing): {@code data/<namespace>/maxtech_storage/logic_templates/<path>.json}
 * holds {@code name}, {@code description}, {@code category}, an optional {@code icon} item id and {@code graph}, the
 * graph exactly as the controller saves it. The controller's Templates list adds one switched off. Name and description
 * are the English text; a resource pack translates them with the keys {@link #nameKey} and {@link #descriptionKey},
 * which are derived from the file id, and a category with {@link #categoryKey}. Immutable.
 *
 * @param <K>
 *            what item settings hold
 * @param name
 *            the rule's name, 1 to {@link #MAX_NAME} characters; also the name of the rule it adds
 * @param description
 *            what the rule does, at most {@link #MAX_DESCRIPTION} characters
 * @param category
 *            the list's grouping, lower-case letters, digits and underscores
 * @param icon
 *            the item shown beside the name; empty for none (an id no item has shows none)
 * @param graph
 *            the rule
 */
public record LogicTemplate<K>(String name, String description, String category, Optional<Identifier> icon,
        LogicGraph<K> graph) {
    /** Longest name, the controller's name limit. */
    public static final int MAX_NAME = 32;
    /** Longest description. */
    public static final int MAX_DESCRIPTION = 256;
    /** Longest category id. */
    public static final int MAX_CATEGORY = 32;
    /** The category of a file that names none. */
    public static final String OTHER = "other";

    private static final Codec<String> CATEGORY = Codec.string(1, MAX_CATEGORY)
            .validate(value -> value.chars().allMatch(c -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')
                    ? DataResult.success(value)
                    : DataResult.error(() -> "Category '" + value + "' may use only a-z, 0-9 and _"));

    /** The codec of templates whose graphs use {@code graphCodec}. */
    public static <K> Codec<LogicTemplate<K>> codec(Codec<LogicGraph<K>> graphCodec) {
        return RecordCodecBuilder.create(instance -> instance
                .group(Codec.string(1, MAX_NAME).fieldOf("name").forGetter(LogicTemplate::name),
                        Codec.string(0, MAX_DESCRIPTION).optionalFieldOf("description", "")
                                .forGetter(LogicTemplate::description),
                        CATEGORY.optionalFieldOf("category", OTHER).forGetter(LogicTemplate::category),
                        Identifier.CODEC.optionalFieldOf("icon").forGetter(LogicTemplate::icon),
                        graphCodec.fieldOf("graph").forGetter(LogicTemplate::graph))
                .apply(instance, LogicTemplate::new));
    }

    /** The lang key that translates the name of the template in file {@code id}. */
    public static String nameKey(Identifier id) {
        return key(id) + ".name";
    }

    /** The lang key that translates the description of the template in file {@code id}. */
    public static String descriptionKey(Identifier id) {
        return key(id) + ".description";
    }

    /** The lang key of a category's name. */
    public static String categoryKey(String category) {
        return "gui.maxtech_storage.logic.template.category." + category;
    }

    private static String key(Identifier id) {
        return "logic_template." + id.getNamespace() + "." + id.getPath().replace('/', '.');
    }
}
