package dev.silencebound.maxtech.storage.tool.tracepen;

import dev.silencebound.maxtech.lib.highlight.HighlightRequest;
import dev.silencebound.maxtech.lib.highlight.Highlights;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.analyzer.AnalyzerReport;
import dev.silencebound.maxtech.storage.network.AnalyzerItem;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The trace pen (M6e): traces the route data takes between two blocks of one storage network. Sneak-use on a storage
 * cable or device marks the start ({@code maxtech_storage:trace_start}); use on another block of the same network
 * prints the trace (segments, slowest and busiest segment, zone borders crossed) and lights it through walls, coloured
 * by load. A use without a start marks one; sneak-use in the air drops it. The start stays, so one start can be traced
 * to several ends. Needs view access to the network.
 */
public final class TracePenItem extends Item {
    /** The trace highlight's channel; a new trace replaces the last. */
    public static final Identifier CHANNEL = MaxtechStorage.id("trace_pen");
    /** Ticks a trace stays lit: 15 seconds, as the analyzer's route. */
    public static final int HIGHLIGHT_TICKS = AnalyzerReport.HIGHLIGHT_TICKS;
    static final int END_COLOUR = 0xFF46B2BD;
    static final int MEET_COLOUR = 0xFFFFFFFF;
    /** Holding use repeats every 4 ticks; this keeps it from flooding the chat. */
    private static final int COOLDOWN = 10;

    public TracePenItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    /** What one use did; the message key says it to the player. */
    public enum Outcome {
        STARTED(TracePenContent.STARTED_KEY), TRACED(""), CLEARED(TracePenContent.CLEARED_KEY), NOT_STORAGE(
                TracePenContent.NOT_STORAGE_KEY), OTHER_NETWORK(TracePenContent.OTHER_NETWORK_KEY), NO_ROUTE(
                        TracePenContent.NO_ROUTE_KEY), DENIED(TracePenContent.DENIED_KEY);

        private final String key;

        Outcome(String key) {
            this.key = key;
        }

        /** The message key; empty for {@link #TRACED}, which prints the trace instead. */
        public String key() {
            return key;
        }
    }

    // PLATFORM-LEAK: NeoForge's IItemExtension hook runs before the block's own use, as for the analyzer.
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (player.getCooldowns().isOnCooldown(stack)) {
            return InteractionResult.FAIL;
        }
        if (context.getLevel() instanceof ServerLevel level && player instanceof ServerPlayer serverPlayer) {
            player.getCooldowns().addCooldown(stack, COOLDOWN);
            List<Component> lines = new ArrayList<>();
            Outcome outcome = use(stack, level, serverPlayer, context.getClickedPos(), player.isSecondaryUseActive(),
                    lines);
            if (outcome != Outcome.TRACED) {
                BlockPos pos = context.getClickedPos();
                lines.add(Component.translatable(outcome.key(), pos.getX(), pos.getY(), pos.getZ()));
            }
            for (Component line : lines) {
                tell(serverPlayer, line);
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isSecondaryUseActive() || !stack.has(ModDataComponents.TRACE_START.get())) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            stack.remove(ModDataComponents.TRACE_START.get());
            tell(serverPlayer, Component.translatable(TracePenContent.CLEARED_KEY));
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * One use of the pen in {@code stack} on the block at {@code pos}: marks the start ({@code mark}, or no start yet)
     * or traces from it, adding the trace's lines to {@code lines} and sending its highlight. Server thread.
     */
    public static Outcome use(ItemStack stack, ServerLevel level, ServerPlayer player, BlockPos pos, boolean mark,
            List<Component> lines) {
        NetworkState end = AnalyzerItem.networkAt(level, pos);
        if (end == null) {
            return Outcome.NOT_STORAGE;
        }
        if (!end.canAccess(player)) {
            return Outcome.DENIED;
        }
        GlobalPos start = stack.get(ModDataComponents.TRACE_START.get());
        if (mark || start == null || !start.dimension().equals(level.dimension()) || !level.isLoaded(start.pos())) {
            stack.set(ModDataComponents.TRACE_START.get(), GlobalPos.of(level.dimension(), pos.immutable()));
            return Outcome.STARTED;
        }
        // Networks are compared by identity: the same graph network has one state.
        @SuppressWarnings("ReferenceEquality")
        boolean same = AnalyzerItem.networkAt(level, start.pos()) == end;
        if (!same) {
            return Outcome.OTHER_NETWORK;
        }
        long from = AnalyzerItem.nodeAt(level, start.pos());
        long to = AnalyzerItem.nodeAt(level, pos);
        if (!routed(end, from) || !routed(end, to)) {
            return Outcome.NO_ROUTE;
        }
        TracePath path = TracePath.between(from, to, end::routeParent);
        lines.addAll(report(end, path, from, to));
        Highlights.send(player, highlight(end, path, from, to));
        return Outcome.TRACED;
    }

    /** Whether the node at {@code pos} has a route: a node leading somewhere, or a route root itself. */
    private static boolean routed(NetworkState network, long pos) {
        return network.routeParent(pos) != Long.MIN_VALUE || network.isRouteRoot(pos);
    }

    /** The chat lines of a trace. */
    public static List<Component> report(NetworkState network, TracePath path, long from, long to) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(TracePenContent.TRACE_KEY, position(from), position(to),
                InfoFormat.number(path.segments())));
        long slowest = Long.MIN_VALUE;
        long slowestRate = Long.MAX_VALUE;
        long busiest = Long.MIN_VALUE;
        int busiestLoad = -1;
        int borders = 0;
        for (LongList side : List.of(path.up(), path.down())) {
            for (int i = 0; i < side.size(); i++) {
                long node = side.getLong(i);
                long parent = network.routeParent(node);
                if (parent == Long.MIN_VALUE) {
                    continue;
                }
                long rate = network.segmentCapacity(node);
                int load = network.segmentLoad(node);
                if (rate < slowestRate) {
                    slowestRate = rate;
                    slowest = node;
                }
                if (load > busiestLoad) {
                    busiestLoad = load;
                    busiest = node;
                }
                if (network.zone(node) != network.zone(parent)) {
                    borders++;
                }
            }
        }
        if (slowest != Long.MIN_VALUE && busiest != Long.MIN_VALUE) {
            lines.add(Component.translatable(TracePenContent.SLOWEST_KEY, InfoFormat.number(slowestRate),
                    position(slowest)));
            lines.add(Component.translatable(TracePenContent.BUSIEST_KEY, InfoFormat.number(busiestLoad),
                    InfoFormat.number(network.segmentCapacity(busiest)), position(busiest)));
        }
        if (path.throughRoot()) {
            lines.add(Component.translatable(TracePenContent.THROUGH_ROOT_KEY));
        }
        lines.add(Component.translatable(TracePenContent.BORDERS_KEY, InfoFormat.number(borders)));
        return lines;
    }

    /**
     * The trace as lines coloured by load, a box on each end and a white box where the two sides meet (on the
     * controller for a path through it).
     */
    public static HighlightRequest highlight(NetworkState network, TracePath path, long from, long to) {
        HighlightRequest.Builder builder = HighlightRequest.builder(CHANNEL).duration(HIGHLIGHT_TICKS)
                .throughWalls(true);
        builder.block(BlockPos.of(from), END_COLOUR);
        builder.block(BlockPos.of(to), END_COLOUR);
        long meet = path.throughRoot() ? network.routeRoot() : path.meet();
        if (meet != Long.MIN_VALUE) {
            builder.block(BlockPos.of(meet), MEET_COLOUR);
        }
        for (LongList side : List.of(path.up(), path.down())) {
            for (int i = 0; i < side.size() && builder.size() < HighlightRequest.MAX_SHAPES; i++) {
                long node = side.getLong(i);
                long parent = network.routeParent(node);
                if (parent != Long.MIN_VALUE) {
                    builder.line(centre(node), centre(parent), AnalyzerReport.loadColour(network.segmentLoad(node)));
                }
            }
        }
        return builder.build();
    }

    private static void tell(ServerPlayer player, Component message) {
        // A GameTest player has no connection to send the message to.
        if (player.connection != null) {
            player.sendSystemMessage(message);
        }
    }

    private static String position(long pos) {
        return BlockPos.getX(pos) + ", " + BlockPos.getY(pos) + ", " + BlockPos.getZ(pos);
    }

    private static Vec3 centre(long pos) {
        return new Vec3(BlockPos.getX(pos) + 0.5, BlockPos.getY(pos) + 0.5, BlockPos.getZ(pos) + 0.5);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        lines.accept(Component.translatable(TracePenContent.DESCRIPTION_KEY).withStyle(ChatFormatting.GRAY));
        GlobalPos start = stack.get(ModDataComponents.TRACE_START.get());
        if (start != null) {
            lines.accept(Component.translatable(TracePenContent.START_TOOLTIP_KEY, start.pos().getX(),
                    start.pos().getY(), start.pos().getZ()).withStyle(ChatFormatting.GRAY));
        }
    }
}
