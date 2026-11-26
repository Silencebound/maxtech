package dev.silencebound.maxtech.storage.crafting.order;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import dev.silencebound.maxtech.storage.terminal.RemoteCost;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import dev.silencebound.maxtech.storage.crafting.job.JobToastPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * The order list (M7C item 6): many different items requested as one crafting job with one plan, from a recipe viewer's
 * missing items, a typed list or a {@code mtlist1:} list code ({@link ListCode}). The client sends the list in
 * {@link Plan}; the terminal's crafting session plans it and answers with the usual plan payload, and starting it is
 * the usual start. Also the conversion between list code entries and keys.
 */
public final class OrderList {
    public static final String TITLE_KEY = gui("title");
    public static final String HINT_KEY = gui("hint");
    public static final String PLAN_KEY = gui("plan");
    public static final String COPY_KEY = gui("copy");
    public static final String COPIED_KEY = gui("copied");
    public static final String CLEAR_KEY = gui("clear");
    public static final String EMPTY_KEY = gui("empty");
    public static final String UNKNOWN_KEY = gui("unknown");
    public static final String ORDER_TITLE_KEY = gui("order_title");
    public static final String TOO_MANY_KEY = gui("too_many");
    public static final String SUMMARY_KEY = gui("summary");
    public static final String SUMMARY_ENTRY_KEY = gui("summary_entry");
    /** Entries a summary names before it ends with an ellipsis. */
    public static final int SUMMARY_NAMED = 2;

    static {
        ModContent.CONTENT.translation(TITLE_KEY, "Order list");
        ModContent.CONTENT.translation(HINT_KEY, "Type or paste a list: stick*64, torch*16 or a mtlist1: code");
        ModContent.CONTENT.translation(PLAN_KEY, "Plan");
        ModContent.CONTENT.translation(COPY_KEY, "Copy code");
        ModContent.CONTENT.translation(COPIED_KEY, "List code copied");
        ModContent.CONTENT.translation(CLEAR_KEY, "Clear");
        ModContent.CONTENT.translation(EMPTY_KEY, "Nothing listed yet");
        ModContent.CONTENT.translation(UNKNOWN_KEY, "Not understood: %s");
        ModContent.CONTENT.translation(ORDER_TITLE_KEY, "Order of %s items");
        ModContent.CONTENT.translation(TOO_MANY_KEY, "Only the first %s entries are kept");
        ModContent.CONTENT.translation(SUMMARY_KEY, "Order: %s items (%s)");
        ModContent.CONTENT.translation(SUMMARY_ENTRY_KEY, "%s × %s");
    }

    private OrderList() {
    }

    /** One request of a list: {@code amount} of {@code key}. */
    static final StreamCodec<RegistryFriendlyByteBuf, CraftingPlanner.Root> ROOT_CODEC = StreamCodec.composite(
            StackKey.STREAM_CODEC, CraftingPlanner.Root::key, ByteBufCodecs.VAR_LONG, CraftingPlanner.Root::amount,
            CraftingPlanner.Root::new);

    /** Client to server: plan {@code roots} as one order for terminal menu {@code containerId}. */
    public record Plan(int containerId, int request, List<CraftingPlanner.Root> roots) implements CustomPacketPayload {
        public static final Type<Plan> TYPE = new Type<>(MaxtechStorage.id("order_plan"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Plan> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Plan::containerId, ByteBufCodecs.VAR_INT, Plan::request,
                ROOT_CODEC.apply(ByteBufCodecs.list(CraftingPlanner.MAX_ROOTS)), Plan::roots, Plan::new);

        @Override
        public Type<Plan> type() {
            return TYPE;
        }
    }

    /** Registers the payload; called once during mod construction. */
    public static void init() {
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Plan.TYPE, Plan.CODEC, (payload, player) -> {
            var session = RequestContent.session(player, payload.containerId());
            if (session != null) {
                // Pays the terminal's action cost (a wireless terminal's charge) when the plan starts.
                RemoteCost.run(RequestContent.source(player, payload.containerId()),
                        () -> session.planOrder(payload.request(), payload.roots()));
            }
        });
    }

    /** What a list code or typed list names, as keys; unknown ids join {@code errors}. */
    public record Resolved(List<CraftingPlanner.Root> roots, List<String> errors) {
    }

    /** Resolves {@code text} (a list code or typed list) against the registries. Any thread after registry load. */
    public static Resolved resolve(String text) {
        ListCode.Parsed parsed = ListCode.decode(text);
        List<CraftingPlanner.Root> roots = new ArrayList<>(parsed.entries().size());
        List<String> errors = new ArrayList<>(parsed.errors());
        for (ListCode.Entry entry : parsed.entries()) {
            Optional<StackKey> key = key(entry);
            if (key.isPresent()) {
                roots.add(new CraftingPlanner.Root(key.get(), entry.amount()));
            } else {
                errors.add((entry.fluid() ? ListCode.FLUID : "") + entry.id());
            }
        }
        return new Resolved(List.copyOf(roots), List.copyOf(errors));
    }

    /** The key an entry names, empty for an id no registry has (air and empty fluid included). */
    public static Optional<StackKey> key(ListCode.Entry entry) {
        Identifier id = Identifier.tryParse(entry.id());
        if (id == null) {
            return Optional.empty();
        }
        if (entry.fluid()) {
            Optional<Fluid> fluid = BuiltInRegistries.FLUID.getOptional(id);
            return fluid.isPresent() && !fluid.get().equals(Fluids.EMPTY)
                    ? Optional.of(FluidKey.of(fluid.get()))
                    : Optional.empty();
        }
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(id);
        return item.isPresent() && !item.get().equals(Items.AIR)
                ? Optional.of(ItemKey.of(item.get()))
                : Optional.empty();
    }

    /**
     * The whole order in one line, as the plan header and the finished toast show it: "Order: 3 items (Stick × 64,
     * Torch × 16, …)", naming the first {@link #SUMMARY_NAMED} requests; {@code total} is how many there are.
     */
    public static Component summary(List<CraftingPlanner.Root> named, int total) {
        MutableComponent list = Component.empty();
        for (int i = 0; i < named.size() && i < SUMMARY_NAMED; i++) {
            if (i > 0) {
                list.append(", ");
            }
            CraftingPlanner.Root root = named.get(i);
            list.append(Component.translatable(SUMMARY_ENTRY_KEY, JobToastPayload.name(root.key()), root.amount()));
        }
        if (total > SUMMARY_NAMED) {
            list.append(", …");
        }
        return Component.translatable(SUMMARY_KEY, total, list);
    }

    /** The list code for {@code roots}: items and fluids by id, components left out. */
    public static String encode(List<CraftingPlanner.Root> roots) {
        List<ListCode.Entry> entries = new ArrayList<>(roots.size());
        for (CraftingPlanner.Root root : roots) {
            switch (root.key()) {
                case ItemKey item -> entries.add(new ListCode.Entry(false,
                        BuiltInRegistries.ITEM.getKey(item.item()).toString(), root.amount()));
                case FluidKey fluid -> entries.add(new ListCode.Entry(true,
                        BuiltInRegistries.FLUID.getKey(fluid.fluid()).toString(), root.amount()));
            }
        }
        return ListCode.encode(entries);
    }

    private static String gui(String name) {
        return "gui." + MaxtechStorage.MOD_ID + ".order." + name;
    }
}
