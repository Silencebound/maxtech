package dev.silencebound.maxtech.storage.mail;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.content.ConventionTags;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.board.BoardContent;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;

/**
 * The mail port (M6e area G): block, recipe, the send payload and the texts; also the one block entity type the mail
 * port and the request board share ({@link ServiceBlockEntity}). Inboxes are {@link MailData}.
 */
public final class MailContent {
    // A chest for the outbox, a signal processor to address it, wire to the network.
    public static final BlockEntry<ServiceBlock, BlockItem> MAIL_PORT = ModContent.CONTENT
            .block("mail_port", p -> new ServiceBlock(ServiceBlock.Service.MAIL_PORT, p))
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(2.0F, 6.0F).sound(SoundType.METAL)).simpleItem()
            .model(BlockModel.orientable(MaxtechStorage.id("block/mail_port_front"),
                    MaxtechStorage.id("block/network_chest_side"), MaxtechStorage.id("block/network_chest_top")))
            .lang("Mail Port").loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE)
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.MISC, item).pattern("PSP").pattern("WXW")
                    .pattern("PPP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('S', MaterialTags.component("basic_signal_processor"))
                    .define('W', MaterialTags.component("insulated_copper_wire")).define('X', ConventionTags.CHESTS)
                    .unlockedBy("has_signal_processor", recipes.has(MaterialTags.component("basic_signal_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<ServiceBlockEntity>> SERVICE_BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("network_service", () -> new BlockEntityType<>(ServiceBlockEntity::new,
                    Set.of(MAIL_PORT.get(), BoardContent.REQUEST_BOARD.get())));

    public static final String INBOX_KEY = "gui." + MaxtechStorage.MOD_ID + ".mail.inbox";
    public static final String COST_KEY = "gui." + MaxtechStorage.MOD_ID + ".mail.cost";
    public static final String TO_KEY = "gui." + MaxtechStorage.MOD_ID + ".mail.to";
    public static final String SEND_KEY = "gui." + MaxtechStorage.MOD_ID + ".mail.send";
    public static final String COLLECT_KEY = "gui." + MaxtechStorage.MOD_ID + ".mail.collect";

    static {
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, MailSendPayload.TYPE,
                MailSendPayload.STREAM_CODEC, MailSendPayload::handle);
        ModContent.CONTENT.translation(ServiceBlock.DENIED_KEY, "The network's owner has not given you access");
        ModContent.CONTENT.translation(MailService.SENT_KEY, "Sent to %s");
        ModContent.CONTENT.translation(MailService.PARTLY_KEY,
                "Sent part of it to %s; their inbox is full or the network ran out of MU");
        ModContent.CONTENT.translation(MailService.UNKNOWN_KEY, "No player called %s has used a mail port");
        ModContent.CONTENT.translation(MailService.FULL_KEY, "The inbox of %s is full");
        ModContent.CONTENT.translation(MailService.NO_POWER_KEY, "The network has too little MU to send this");
        ModContent.CONTENT.translation(MailService.OFFLINE_KEY, "This mail port is in no working network");
        ModContent.CONTENT.translation(MailService.DENIED_KEY,
                "You need deposit access to this network to send mail from it");
        ModContent.CONTENT.translation(MailService.EMPTY_KEY, "Put what you want to send in the outbox first");
        ModContent.CONTENT.translation(MailService.RECEIVED_KEY,
                "You have mail: %s stacks from %s. Collect them at a mail port.");
        ModContent.CONTENT.translation(MailService.COLLECTED_KEY, "Collected %s stacks");
        ModContent.CONTENT.translation(MailService.NO_BRIDGE_KEY,
                "The recipient is in another dimension; this network needs a dimension bridge to send there");
        ModContent.CONTENT.translation(INBOX_KEY, "Inbox: %s of %s stacks");
        ModContent.CONTENT.translation(COST_KEY, "%s MU per stack");
        ModContent.CONTENT.translation(TO_KEY, "To:");
        ModContent.CONTENT.translation(SEND_KEY, "Send");
        ModContent.CONTENT.translation(COLLECT_KEY, "Collect");
    }

    private MailContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
