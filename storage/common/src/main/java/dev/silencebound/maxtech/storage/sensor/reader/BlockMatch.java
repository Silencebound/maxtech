package dev.silencebound.maxtech.storage.sensor.reader;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The blocks a pack reader applies to: block ids ({@code mekanism:basic_energy_cube}) and block tags
 * ({@code #c:chests}), written as one list. An id of a block that is not installed never matches, so one reader can
 * name blocks of several versions of a mod. Immutable.
 *
 * @param ids
 *            the block ids, in file order
 * @param tags
 *            the block tags, in file order
 */
public record BlockMatch(List<Identifier> ids, List<TagKey<Block>> tags) {
    /** The list form; an entry that is neither an id nor {@code #} and a tag id fails with that entry. */
    public static final Codec<BlockMatch> CODEC = Codec.STRING.listOf(1, 256).comapFlatMap(BlockMatch::parse,
            BlockMatch::entries);

    public BlockMatch {
        ids = List.copyOf(ids);
        tags = List.copyOf(tags);
    }

    /** Reads the list form. */
    public static DataResult<BlockMatch> parse(List<String> entries) {
        List<Identifier> ids = new ArrayList<>();
        List<TagKey<Block>> tags = new ArrayList<>();
        for (String entry : entries) {
            boolean tag = entry.startsWith("#");
            Identifier id = Identifier.tryParse(tag ? entry.substring(1) : entry);
            if (id == null) {
                return DataResult.error(() -> "'" + entry + "' is not a block id or #tag");
            }
            if (tag) {
                tags.add(TagKey.create(Registries.BLOCK, id));
            } else {
                ids.add(id);
            }
        }
        return DataResult.success(new BlockMatch(ids, tags));
    }

    /** The list form: ids, then tags with {@code #}. */
    public List<String> entries() {
        List<String> entries = new ArrayList<>(ids.size() + tags.size());
        for (Identifier id : ids) {
            entries.add(id.toString());
        }
        for (TagKey<Block> tag : tags) {
            entries.add("#" + tag.location());
        }
        return entries;
    }

    /** Whether {@code state}'s block is one of the ids or in one of the tags. */
    public boolean matches(BlockState state) {
        if (!ids.isEmpty()) {
            Identifier key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            for (int i = 0; i < ids.size(); i++) {
                if (ids.get(i).equals(key)) {
                    return true;
                }
            }
        }
        for (int i = 0; i < tags.size(); i++) {
            if (state.is(tags.get(i))) {
                return true;
            }
        }
        return false;
    }
}
