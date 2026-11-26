package dev.silencebound.maxtech.storage.tool.probe;

import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.world.item.Item;

/** The signal probe's item, payload and texts (M6e area E). */
public final class ProbeContent {
    public static final String TITLE_KEY = "gui.maxtech_storage.probe.title";
    public static final String LOAD_KEY = "gui.maxtech_storage.probe.load";
    public static final String RATE_KEY = "gui.maxtech_storage.probe.rate";
    public static final String PEAK_KEY = "gui.maxtech_storage.probe.peak";
    public static final String NO_SIGNAL_KEY = "gui.maxtech_storage.probe.no_signal";
    public static final String SPAN_KEY = "gui.maxtech_storage.probe.span";
    public static final String ENERGY_KEY = "gui.maxtech_storage.probe.energy";

    public static final RegistryEntry<Item, SignalProbeItem> SIGNAL_PROBE = ModContent.CONTENT
            .item("signal_probe", p -> new SignalProbeItem(p.stacksTo(1)))
            .model(ItemModel.flat(MaxtechStorage.id("item/signal_probe"))).lang("Signal Probe")
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.TOOLS, item).pattern(" S ").pattern("PDP")
                    .pattern(" W ").define('S', MaterialTags.component("basic_signal_processor"))
                    .define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('D', MaterialTags.component("display_glass"))
                    .define('W', MaterialTags.COPPER_WIRE)
                    .unlockedBy("has_basic_signal_processor",
                            recipes.has(MaterialTags.component("basic_signal_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    static {
        ModContent.CONTENT.translation(SignalProbeItem.DESCRIPTION_KEY,
                "Use on a storage cable or device to bind it, then use in the air to graph how many of its channels are in use");
        ModContent.CONTENT.translation(SignalProbeItem.BOUND_KEY, "Probing %s %s %s in %s");
        ModContent.CONTENT.translation(SignalProbeItem.BOUND_MESSAGE_KEY, "Probe bound to %s %s %s");
        ModContent.CONTENT.translation(SignalProbeItem.UNBOUND_KEY,
                "Not bound; use the probe on a storage cable or device first");
        ModContent.CONTENT.translation(SignalProbeItem.NOT_STORAGE_KEY, "That is not a storage cable or device");
        ModContent.CONTENT.translation(SignalProbeItem.DENIED_KEY, "You have no access to that network");
        ModContent.CONTENT.translation(TITLE_KEY, "Signal Probe");
        ModContent.CONTENT.translation(LOAD_KEY, "Channels in use: %s%%");
        ModContent.CONTENT.translation(RATE_KEY, "Link: %s channels");
        ModContent.CONTENT.translation(PEAK_KEY, "Peak %s%%");
        ModContent.CONTENT.translation(NO_SIGNAL_KEY, "No signal: the block is unloaded or in no network");
        ModContent.CONTENT.translation(SPAN_KEY, "Last %s s, a sample every %s s");
        ModContent.CONTENT.translation(ENERGY_KEY, "Energy: %s of %s MU/t, %s MU/t supply");
    }

    private ProbeContent() {
    }

    /** Loads the class during mod construction so the entries above are queued, and registers the sample payload. */
    public static void init() {
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, ProbeSamplePayload.TYPE,
                ProbeSamplePayload.STREAM_CODEC, (payload, player) -> {
                    if (player.containerMenu instanceof ProbeMenu menu && menu.containerId == payload.containerId()) {
                        menu.accept(payload);
                    }
                });
    }
}
