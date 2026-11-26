package dev.silencebound.maxtech.storage.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.tool.blueprint.DeviceSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A world device's settings in its item ({@code maxtech_storage:world_settings}), the same for the block and the part,
 * so breaking the device, taking a part off and the 1:1 conversion recipes keep them: the filter's nine slots and their
 * tags ({@code ""} for an exact slot), the mode, the kept tool and the action by its fixed id ({@code ""} for the
 * first, {@link WorldKind#actionId}). Written only when something differs from a new device, so a fresh device stacks
 * with others.
 */
public record WorldSettings(List<ItemStack> filter, List<String> tags, int mode, ItemStack tool, String action) {
    public static final String FILTER_KEY = "tooltip." + MaxtechStorage.MOD_ID + ".world.filter";
    public static final String MODE_KEY = "tooltip." + MaxtechStorage.MOD_ID + ".world.mode";
    public static final String TOOL_KEY = "tooltip." + MaxtechStorage.MOD_ID + ".world.tool";
    public static final String ACTION_KEY = "tooltip." + MaxtechStorage.MOD_ID + ".world.action";

    public static final Codec<WorldSettings> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(ItemStack.OPTIONAL_CODEC.sizeLimitedListOf(WorldFilter.SIZE).optionalFieldOf("filter", List.of())
                    .forGetter(WorldSettings::filter),
                    Codec.STRING.sizeLimitedListOf(WorldFilter.SIZE).optionalFieldOf("tags", List.of()).forGetter(
                            WorldSettings::tags),
                    Codec.INT.optionalFieldOf("mode", 0).forGetter(WorldSettings::mode),
                    ItemStack.OPTIONAL_CODEC.optionalFieldOf("tool", ItemStack.EMPTY).forGetter(WorldSettings::tool),
                    Codec.STRING.optionalFieldOf("action", "").forGetter(WorldSettings::action))
            .apply(instance, WorldSettings::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, WorldSettings> STREAM_CODEC = StreamCodec.composite(
            ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(WorldFilter.SIZE)), WorldSettings::filter,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(WorldFilter.SIZE)), WorldSettings::tags,
            ByteBufCodecs.VAR_INT, WorldSettings::mode, ItemStack.OPTIONAL_STREAM_CODEC, WorldSettings::tool,
            ByteBufCodecs.STRING_UTF8, WorldSettings::action, WorldSettings::new);

    /**
     * Compares the stacks by item, count and components (a record compares them by identity), so two items with the
     * same settings stack.
     */
    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof WorldSettings settings && mode == settings.mode
                && tags.equals(settings.tags) && action.equals(settings.action)
                && ItemStack.listMatches(filter, settings.filter) && ItemStack.matches(tool, settings.tool));
    }

    @Override
    public int hashCode() {
        return (((ItemStack.hashStackList(filter) * 31 + tags.hashCode()) * 31 + mode) * 31
                + ItemStack.hashItemAndComponents(tool)) * 31 + action.hashCode();
    }

    /** {@code device}'s settings, or null when they are a new device's. */
    public static @Nullable WorldSettings of(WorldDevice device) {
        WorldFilter filter = device.filter();
        Container tool = device.tool();
        ItemStack kept = tool == null ? ItemStack.EMPTY : tool.getItem(0);
        if (filter.isEmpty() && device.mode() == 0 && kept.isEmpty() && device.action() == 0) {
            return null;
        }
        List<ItemStack> slots = new ArrayList<>(WorldFilter.SIZE);
        List<String> tags = new ArrayList<>(WorldFilter.SIZE);
        for (int i = 0; i < WorldFilter.SIZE; i++) {
            slots.add(filter.getItem(i).copy());
            TagKey<Item> tag = filter.tag(i);
            tags.add(tag == null ? "" : tag.location().toString());
        }
        String action = device.action() == 0 ? "" : device.kind().actionId(device.action());
        return new WorldSettings(List.copyOf(slots), List.copyOf(tags), device.mode(), kept.copy(), action);
    }

    /** Writes {@code device}'s settings into {@code components}, or nothing when they are a new device's. */
    public static void write(DataComponentMap.Builder components, WorldDevice device) {
        WorldSettings settings = of(device);
        if (settings != null) {
            components.set(ModDataComponents.WORLD_SETTINGS.get(), settings);
        }
    }

    /** Puts {@code device}'s settings on {@code stack}. */
    public static void write(ItemStack stack, WorldDevice device) {
        DataComponentMap.Builder components = DataComponentMap.builder();
        write(components, device);
        stack.applyComponents(components.build());
    }

    /**
     * The settings {@code components} carry, or null for a new device's. Reading it in {@code applyImplicitComponents}
     * marks the component as taken, so it does not linger in the block entity's leftovers.
     */
    public static @Nullable WorldSettings read(DataComponentGetter components) {
        return components.get(ModDataComponents.WORLD_SETTINGS.get());
    }

    /**
     * Fills {@code filter} and {@code tool} from {@code settings} (a new device's when null) without saving or waking,
     * and returns the mode.
     */
    public static int apply(@Nullable WorldSettings settings, WorldFilter filter, @Nullable Container tool) {
        NonNullList<ItemStack> slots = filter.getItems();
        slots.clear();
        if (tool != null) {
            tool.removeItemNoUpdate(0);
        }
        if (settings == null) {
            filter.rebuild();
            return 0;
        }
        for (int i = 0; i < Math.min(WorldFilter.SIZE, settings.filter.size()); i++) {
            slots.set(i, settings.filter.get(i).copyWithCount(Math.min(1, settings.filter.get(i).getCount())));
        }
        for (int i = 0; i < Math.min(WorldFilter.SIZE, settings.tags.size()); i++) {
            filter.loadTag(i, Identifier.tryParse(settings.tags.get(i)));
        }
        filter.rebuild();
        if (tool != null && !settings.tool.isEmpty()) {
            tool.setItem(0, settings.tool.copy());
        }
        return settings.mode;
    }

    /**
     * {@code worker}'s filter, tags, mode and action for the blueprint card, as the item holds them; the tool is
     * contents and stays in the device. A paste keeps the device's own tool and runs {@code saved}.
     */
    public static DeviceSettings copyable(WorldWorker worker, Runnable saved) {
        return new DeviceSettings(DeviceSettings.kind(worker.kind().getSerializedName()), () -> {
            DataComponentMap.Builder components = DataComponentMap.builder();
            WorldSettings settings = of(worker);
            if (settings != null) {
                components.set(ModDataComponents.WORLD_SETTINGS.get(), new WorldSettings(settings.filter, settings.tags,
                        settings.mode, ItemStack.EMPTY, settings.action));
            }
            return components.build();
        }, components -> {
            WorldSettings read = read(components);
            Container tool = worker.tool();
            ItemStack kept = tool == null ? ItemStack.EMPTY : tool.getItem(0).copy();
            worker.apply(read == null
                    ? new WorldSettings(List.of(), List.of(), 0, kept, "")
                    : new WorldSettings(read.filter, read.tags, read.mode, kept, read.action));
            saved.run();
        });
    }

    /** The tooltip summary of the settings {@code stack} carries, for a device of {@code kind}. */
    public static void appendTooltip(ItemStack stack, WorldKind kind, Consumer<Component> lines) {
        WorldSettings settings = read(stack);
        if (settings == null) {
            return;
        }
        MutableComponent names = Component.empty();
        int named = 0;
        for (int i = 0; i < settings.filter.size(); i++) {
            ItemStack slot = settings.filter.get(i);
            if (slot.isEmpty()) {
                continue;
            }
            if (named++ > 0) {
                names.append(", ");
            }
            String tag = i < settings.tags.size() ? settings.tags.get(i) : "";
            names.append(tag.isEmpty() ? slot.getHoverName() : Component.literal("#" + tag));
        }
        if (named > 0) {
            lines.accept(Component.translatable(FILTER_KEY, names).withStyle(ChatFormatting.GRAY));
        }
        if (kind.modeCount() > 1) {
            lines.accept(Component.translatable(MODE_KEY, Component.translatable(kind.modeKey(settings.mode)))
                    .withStyle(ChatFormatting.GRAY));
        }
        if (kind.actionCount() > 1) {
            lines.accept(Component
                    .translatable(ACTION_KEY, Component.translatable(kind.actionKey(kind.actionIndex(settings.action))))
                    .withStyle(ChatFormatting.GRAY));
        }
        if (!settings.tool.isEmpty()) {
            lines.accept(Component.translatable(TOOL_KEY, settings.tool.getHoverName()).withStyle(ChatFormatting.GRAY));
        }
    }
}
