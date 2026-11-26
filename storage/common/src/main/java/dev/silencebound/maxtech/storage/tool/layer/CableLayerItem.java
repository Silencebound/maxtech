package dev.silencebound.maxtech.storage.tool.layer;

import dev.silencebound.maxtech.storage.energy.ItemEnergy;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.conduit.ConduitItem;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cable.StorageCableConduitType;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.tool.uplink.PocketUplinkItem;
import dev.silencebound.maxtech.storage.tool.uplink.UplinkReach;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
// PLATFORM-LEAK: the cables leave the network in one NeoForge transaction, like every Storage transfer (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * The cable layer (M6f, decision F8): use on a block marks the start of a run, use on a second block lays storage cable
 * along {@link LayerPath} between the two, taking the cables from the bound network in one operation. It binds like the
 * pocket tools (sneak-use on a controller core) and reaches the network on the same terms ({@link UplinkReach}, full
 * access, since it takes items out). Sneak-use on a storage cable picks that cable's kind and colour; sneak-use in the
 * air drops the start. Each placement goes through {@link ItemStack#useOn}, so it is a player placement that protection
 * mods can refuse. Holds FE ({@code maxtech_storage:layer_energy}), charged in any FE charger, and spends
 * {@code tools.layer.energyPerCable} per cable laid. Runs are at most {@code tools.layer.maxLength} blocks.
 */
public final class CableLayerItem extends Item {
    private static final int BAR_COLOUR = 0x46B2BD;

    /** Why a run ended. */
    public enum Outcome {
        /** Every position of the run holds a storage cable now. */
        DONE,
        /** A block, an entity, protection or an unloaded chunk stopped the run at {@link Result#stop()}. */
        BLOCKED,
        /** The run is longer than {@code tools.layer.maxLength}; nothing laid. */
        TOO_LONG,
        /** No bound network in reach with full access; nothing laid. */
        NO_NETWORK,
        /** The layer's charge ran out at {@link Result#stop()}. */
        NO_POWER,
        /** The network ran out of the cable at {@link Result#stop()}. */
        NO_CABLE
    }

    /**
     * What one run did: {@code laid} cables placed, and the first position left without a cable, or null when the run
     * was completed or never started.
     */
    public record Result(Outcome outcome, int laid, @Nullable BlockPos stop) {
    }

    public CableLayerItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    /** FE the layer holds when full, from config; any thread. */
    public static int capacity() {
        return (int) StorageServerConfig.loadedOr(() -> StorageServerConfig.LAYER_ENERGY_CAPACITY.getAsInt(), 0);
    }

    /** FE per transfer a charger may put in, from config; any thread. */
    public static int maxInput() {
        return (int) StorageServerConfig.loadedOr(() -> StorageServerConfig.LAYER_MAX_INPUT.getAsInt(), 0);
    }

    /** FE per cable laid, from config; any thread. */
    public static int energyPerCable() {
        return (int) StorageServerConfig.loadedOr(() -> StorageServerConfig.LAYER_ENERGY_PER_CABLE.getAsInt(), 0);
    }

    /** Most positions in one run, from config; any thread. */
    public static int maxLength() {
        return (int) StorageServerConfig.loadedOr(() -> StorageServerConfig.LAYER_MAX_LENGTH.getAsInt(), 0);
    }

    /** MU in {@code stack}; 0 when never charged. */
    public static int energy(ItemStack stack) {
        return ItemEnergy.getInt(stack, LayerContent.ENERGY.get());
    }

    /** The storage cable item {@code stack} lays; tier 1 plain when never picked. */
    public static ConduitItem cable(ItemStack stack) {
        Item item = stack.get(LayerContent.CABLE.get());
        return item instanceof ConduitItem conduit && conduit.conduitType() instanceof StorageCableConduitType
                ? conduit
                : CableContent.STORAGE_CABLE_TIER_1.item().get();
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player.isSecondaryUseActive()) {
            ItemStack layer = player.getItemInHand(hand);
            if (layer.has(LayerContent.START.get()) && player instanceof ServerPlayer serverPlayer) {
                layer.remove(LayerContent.START.get());
                PocketUplinkItem.tell(serverPlayer, Component.translatable(LayerContent.START_CLEARED_KEY));
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        BlockPos clicked = context.getClickedPos();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (player.isSecondaryUseActive()) {
            if (level.getBlockEntity(clicked) instanceof ControllerBlockEntity) {
                PocketUplinkItem.bind(context);
                return InteractionResult.SUCCESS;
            }
            if (level.getBlockEntity(clicked) instanceof ConduitBlockEntity conduit
                    && conduit.type() instanceof StorageCableConduitType type) {
                if (player instanceof ServerPlayer serverPlayer) {
                    pick(context.getItemInHand(), type, conduit.conduit().colour());
                    PocketUplinkItem.tell(serverPlayer,
                            Component.translatable(LayerContent.CABLE_SET_KEY, name(type.item().asItem())));
                }
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        }
        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel server)) {
            return InteractionResult.SUCCESS;
        }
        ItemStack layer = context.getItemInHand();
        BlockPos target = level.getBlockState(clicked).canBeReplaced()
                ? clicked
                : clicked.relative(context.getClickedFace());
        GlobalPos start = layer.get(LayerContent.START.get());
        if (start == null || !start.dimension().equals(server.dimension())) {
            if (start != null) {
                PocketUplinkItem.tell(serverPlayer, Component.translatable(LayerContent.OTHER_LEVEL_KEY));
            }
            layer.set(LayerContent.START.get(), GlobalPos.of(server.dimension(), target));
            PocketUplinkItem.tell(serverPlayer,
                    Component.translatable(LayerContent.START_SET_KEY, target.getX(), target.getY(), target.getZ()));
            return InteractionResult.SUCCESS;
        }
        Result result = lay(serverPlayer, layer, context.getHand(), start.pos(), target);
        if (result.outcome() != Outcome.TOO_LONG && result.outcome() != Outcome.NO_NETWORK) {
            layer.remove(LayerContent.START.get());
        }
        PocketUplinkItem.tell(serverPlayer, message(result, cable(layer), LayerPath.length(start.pos(), target)));
        return InteractionResult.SUCCESS;
    }

    /** Sets the cable {@code layer} lays to {@code type}'s, in {@code colour} (null for uncoloured). */
    public static void pick(ItemStack layer, StorageCableConduitType type, @Nullable DyeColor colour) {
        layer.set(LayerContent.CABLE.get(), type.item().asItem());
        if (colour == null) {
            layer.remove(LayerContent.COLOUR.get());
        } else {
            layer.set(LayerContent.COLOUR.get(), colour);
        }
    }

    /**
     * Lays {@code layer}'s cable from {@code from} to {@code to} for {@code player}, who holds the layer in
     * {@code hand}. Positions already holding a storage cable are passed over; the run stops at the first position that
     * is not replaceable, is obstructed, protected or not loaded. The cables for every free position up to there (as
     * many as the charge pays for) leave the network in one transaction; any the world then refuses go back to the
     * network, or to the player when the network takes them no more. Server thread.
     */
    public static Result lay(ServerPlayer player, ItemStack layer, InteractionHand hand, BlockPos from, BlockPos to) {
        int length = LayerPath.length(from, to);
        if (length > maxLength()) {
            return new Result(Outcome.TOO_LONG, 0, null);
        }
        NetworkState network = UplinkReach.network(player, layer, AccessLevel.FULL);
        if (network == null) {
            return new Result(Outcome.NO_NETWORK, 0, null);
        }
        ServerLevel level = player.level();
        ConduitItem cable = cable(layer);
        BlockState placed = cable.getBlock().defaultBlockState();
        CollisionContext collision = CollisionContext.placementContext(player);
        List<BlockPos> free = new ArrayList<>();
        BlockPos blocked = null;
        for (BlockPos pos : LayerPath.between(from, to)) {
            if (!level.isLoaded(pos)) {
                blocked = pos;
                break;
            }
            if (level.getBlockEntity(pos) instanceof ConduitBlockEntity conduit
                    && conduit.type() instanceof StorageCableConduitType) {
                continue;
            }
            if (!player.mayBuild() || !level.mayInteract(player, pos) || !level.getBlockState(pos).canBeReplaced()
                    || !level.isUnobstructed(placed, pos, collision)) {
                blocked = pos;
                break;
            }
            free.add(pos);
        }
        if (free.isEmpty()) {
            return new Result(blocked == null ? Outcome.DONE : Outcome.BLOCKED, 0, blocked);
        }
        int cost = energyPerCable();
        int energy = energy(layer);
        int affordable = cost <= 0 ? free.size() : Math.min(free.size(), energy / cost);
        if (affordable == 0) {
            return new Result(Outcome.NO_POWER, 0, free.getFirst());
        }
        ItemKey key = ItemKey.of(cable);
        long taken;
        try (Transaction transaction = Transaction.openRoot()) {
            taken = network.extract(key, affordable, transaction, player);
            transaction.commit();
        }
        int laid = 0;
        int i = 0;
        DyeColor colour = layer.get(LayerContent.COLOUR.get());
        for (; i < taken; i++) {
            BlockPos pos = free.get(i);
            if (!place(player, hand, cable, pos, colour)) {
                break;
            }
            laid++;
        }
        long left = taken - laid;
        if (left > 0) {
            giveBack(player, network, key, left);
        }
        if (cost > 0 && laid > 0) {
            ItemEnergy.setInt(layer, LayerContent.ENERGY.get(), energy - laid * cost);
        }
        if (laid < taken) {
            return new Result(Outcome.BLOCKED, laid, free.get(laid));
        }
        if (laid < free.size()) {
            return new Result(taken < affordable ? Outcome.NO_CABLE : Outcome.NO_POWER, laid, free.get(laid));
        }
        return new Result(blocked == null ? Outcome.DONE : Outcome.BLOCKED, laid, blocked);
    }

    /** Places one cable at {@code pos} as {@code player} would; true when the cable is there afterwards. */
    private static boolean place(ServerPlayer player, InteractionHand hand, ConduitItem cable, BlockPos pos,
            @Nullable DyeColor colour) {
        ServerLevel level = player.level();
        ItemStack one = new ItemStack(cable);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        one.useOn(new UseOnContext(level, player, hand, one, hit));
        if (!(level.getBlockEntity(pos) instanceof ConduitBlockEntity conduit)
                || !conduit.type().equals(cable.conduitType())) {
            return false;
        }
        if (colour != null) {
            conduit.setColour(colour);
        }
        return true;
    }

    /** Returns cables that were taken but not laid: to the network, else to the player. */
    private static void giveBack(ServerPlayer player, NetworkState network, ItemKey key, long amount) {
        long stored;
        try (Transaction transaction = Transaction.openRoot()) {
            stored = network.insert(key, amount, transaction, player);
            transaction.commit();
        }
        long rest = amount - stored;
        while (rest > 0) {
            int count = (int) Math.min(rest, key.item().getDefaultMaxStackSize());
            player.getInventory().placeItemBackInInventory(key.toStack(count));
            rest -= count;
        }
    }

    private static Component message(Result result, ConduitItem cable, int length) {
        BlockPos stop = result.stop();
        return switch (result.outcome()) {
            case DONE -> Component.translatable(LayerContent.LAID_KEY, result.laid());
            case BLOCKED -> stop == null
                    ? Component.translatable(LayerContent.LAID_KEY, result.laid())
                    : Component.translatable(LayerContent.BLOCKED_KEY, result.laid(), stop.getX(), stop.getY(),
                            stop.getZ());
            case TOO_LONG -> Component.translatable(LayerContent.TOO_LONG_KEY, length, maxLength());
            case NO_NETWORK -> Component.translatable(LayerContent.NO_NETWORK_KEY);
            case NO_POWER -> Component.translatable(LayerContent.NO_POWER_KEY, result.laid());
            case NO_CABLE -> Component.translatable(LayerContent.NO_CABLE_KEY, result.laid(), name(cable));
        };
    }

    private static Component name(Item item) {
        return new ItemStack(item).getHoverName();
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        int capacity = capacity();
        return capacity <= 0 ? 0 : Mth.clamp(Math.round(13.0F * energy(stack) / capacity), 0, 13);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return BAR_COLOUR;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        lines.accept(Component.translatable(LayerContent.DESCRIPTION_KEY).withStyle(ChatFormatting.GRAY));
        PocketUplinkItem.appendLink(stack, lines);
        Component cableName = name(cable(stack));
        DyeColor colour = stack.get(LayerContent.COLOUR.get());
        lines.accept(Component
                .translatable(LayerContent.CABLE_KEY, colour == null
                        ? cableName
                        : Component.translatable("color.minecraft." + colour.getName()).append(" ").append(cableName))
                .withStyle(ChatFormatting.GRAY));
        GlobalPos start = stack.get(LayerContent.START.get());
        if (start != null) {
            lines.accept(Component
                    .translatable(LayerContent.START_KEY, start.pos().getX(), start.pos().getY(), start.pos().getZ())
                    .withStyle(ChatFormatting.GRAY));
        }
        lines.accept(Component.translatable(LayerContent.ENERGY_KEY, energy(stack), capacity())
                .withStyle(ChatFormatting.GRAY));
    }
}
