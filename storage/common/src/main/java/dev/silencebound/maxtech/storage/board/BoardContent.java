package dev.silencebound.maxtech.storage.board;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.mail.ServiceBlock;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;

/**
 * The request board (M6e area G): block, recipe, the list payload and the texts. Its block entity type is the mail
 * port's ({@code MailContent.SERVICE_BLOCK_ENTITY}); requests are {@link BoardData}.
 */
public final class BoardContent {
    // Display glass shows the requests, a logic processor keeps them, wire to the network.
    public static final BlockEntry<ServiceBlock, BlockItem> REQUEST_BOARD = ModContent.CONTENT
            .block("request_board", p -> new ServiceBlock(ServiceBlock.Service.REQUEST_BOARD, p))
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(2.0F, 6.0F).sound(SoundType.METAL)).simpleItem()
            .model(BlockModel.orientable(MaxtechStorage.id("block/request_board_front"),
                    MaxtechStorage.id("block/terminal/bezel"), MaxtechStorage.id("block/terminal/bezel")))
            .lang("Request Board").loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE)
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.MISC, item).pattern("PDP").pattern("WLW")
                    .pattern("PPP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('D', MaterialTags.component("display_glass"))
                    .define('W', MaterialTags.component("insulated_copper_wire"))
                    .define('L', MaterialTags.component("basic_logic_processor"))
                    .unlockedBy("has_logic_processor", recipes.has(MaterialTags.component("basic_logic_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    public static final String POST_KEY = "gui." + MaxtechStorage.MOD_ID + ".board.post";
    public static final String FULFIL_KEY = "gui." + MaxtechStorage.MOD_ID + ".board.fulfil";
    public static final String CANCEL_KEY = "gui." + MaxtechStorage.MOD_ID + ".board.cancel";
    public static final String ROW_KEY = "gui." + MaxtechStorage.MOD_ID + ".board.row";
    public static final String EMPTY_KEY = "gui." + MaxtechStorage.MOD_ID + ".board.empty";
    public static final String PICK_HINT_KEY = "gui." + MaxtechStorage.MOD_ID + ".board.pick_hint";
    public static final String SLOT_HINT_KEY = "gui." + MaxtechStorage.MOD_ID + ".board.slot_hint";
    public static final String HELD_KEY = "gui." + MaxtechStorage.MOD_ID + ".board.held";

    static {
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, BoardView.TYPE, BoardView.STREAM_CODEC,
                BoardView::handle);
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, BoardMenu.Post.TYPE, BoardMenu.Post.CODEC,
                BoardMenu.Post::handle);
        ModContent.CONTENT.translation(BoardService.POSTED_KEY, "Request posted");
        ModContent.CONTENT.translation(BoardService.NOTHING_HELD_KEY, "Hold the item you want to ask for");
        ModContent.CONTENT.translation(BoardService.FULL_KEY, "The board is full; take a request down first");
        ModContent.CONTENT.translation(BoardService.FULFILLED_KEY, "Sent to the poster's inbox");
        ModContent.CONTENT.translation(BoardService.NONE_STORED_KEY, "The network holds none of that item");
        ModContent.CONTENT.translation(BoardService.INBOX_FULL_KEY, "The poster's inbox is full");
        ModContent.CONTENT.translation(BoardService.POST_DENIED_KEY,
                "You need view access to this board to post requests");
        ModContent.CONTENT.translation(BoardService.DENIED_KEY,
                "You need full access to this network to fulfil requests from it");
        ModContent.CONTENT.translation(BoardService.NO_BRIDGE_KEY,
                "The poster is in another dimension; this network needs a dimension bridge to deliver there");
        ModContent.CONTENT.translation(POST_KEY, "Ask for:");
        ModContent.CONTENT.translation(PICK_HINT_KEY, "Click: ask for this item");
        ModContent.CONTENT.translation(SLOT_HINT_KEY,
                "Post asks for this: an item clicked or dragged here, else the one in your hand. Click: clear");
        ModContent.CONTENT.translation(HELD_KEY, "The network holds %s");
        ModContent.CONTENT.translation(FULFIL_KEY, "Fulfil");
        ModContent.CONTENT.translation(CANCEL_KEY, "x");
        ModContent.CONTENT.translation(ROW_KEY, "%s of %s for %s");
        ModContent.CONTENT.translation(EMPTY_KEY, "No open requests");
    }

    private BoardContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
