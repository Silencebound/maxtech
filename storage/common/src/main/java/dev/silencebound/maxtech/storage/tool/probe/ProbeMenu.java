package dev.silencebound.maxtech.storage.tool.probe;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.registry.ModMenus;
import java.util.function.Consumer;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The signal probe's screen, slotless. The server side belongs to one viewer: while it is open it reads the probed
 * block every {@code tools.probe.sampleTicks} ticks and sends the sample ({@link ProbeSamplePayload}); nothing is read
 * when no screen is open. A sample carries the block's energy network readout too, when it has one. The client keeps
 * the {@link ProbeSeries} window, sized from the numbers the opening sends. It stays valid while the player holds a
 * probe.
 */
public final class ProbeMenu extends AbstractContainerMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 140;

    private final ProbeSeries series;
    private final int sampleTicks;
    private final @Nullable GlobalPos target;
    private final @Nullable ServerPlayer viewer;
    private final @Nullable Consumer<ProbeSamplePayload> sender;
    private long rate;
    private ProbeSampler.Sample energy = ProbeSampler.Sample.NONE;
    private long nextSample;

    private ProbeMenu(int containerId, ProbeSeries series, int sampleTicks, @Nullable GlobalPos target,
            @Nullable ServerPlayer viewer, @Nullable Consumer<ProbeSamplePayload> sender) {
        super(ModMenus.PROBE.get(), containerId);
        this.series = series;
        this.sampleTicks = sampleTicks;
        this.target = target;
        this.viewer = viewer;
        this.sender = sender;
    }

    /** The client's menu; samples arrive as payloads. */
    public static ProbeMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        int capacity = buffer.readVarInt();
        int ticks = buffer.readVarInt();
        return new ProbeMenu(containerId, new ProbeSeries(capacity), ticks, null, null, null);
    }

    /** The server's menu for {@code viewer}, probing {@code target}. */
    public static ProbeMenu server(int containerId, GlobalPos target, ServerPlayer viewer,
            Consumer<ProbeSamplePayload> sender, int capacity, int sampleTicks) {
        return new ProbeMenu(containerId, new ProbeSeries(capacity), sampleTicks, target, viewer, sender);
    }

    /** Opens the probe screen for {@code player}, window and cadence from the config. */
    public static void open(ServerPlayer player, GlobalPos target, Component title, int windowSeconds,
            int sampleTicks) {
        int capacity = ProbeSeries.capacity(windowSeconds, sampleTicks);
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> server(id, target, player,
                        payload -> Platform.get().network().sendToPlayer(player, payload), capacity, sampleTicks),
                        title),
                buffer -> {
                    buffer.writeVarInt(capacity);
                    buffer.writeVarInt(sampleTicks);
                });
    }

    public ProbeSeries series() {
        return series;
    }

    /** Operations per second of the probed link at the last sample. */
    public long rate() {
        return rate;
    }

    /**
     * The last sample, for its energy network's readout ({@link ProbeSampler.Sample#energy()} false while there is
     * none).
     */
    public ProbeSampler.Sample energy() {
        return energy;
    }

    /** Ticks between two samples. */
    public int sampleTicks() {
        return sampleTicks;
    }

    /** Takes a sample the server sent. Client side. */
    public void accept(ProbeSamplePayload sample) {
        series.add(sample.load());
        rate = sample.rate();
        energy = new ProbeSampler.Sample(sample.load(), sample.rate(), sample.energy(), sample.supply(),
                sample.demand(), sample.received());
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        ServerPlayer to = viewer;
        Consumer<ProbeSamplePayload> out = sender;
        GlobalPos at = target;
        if (to == null || out == null || at == null) {
            return;
        }
        long now = to.level().getGameTime();
        if (now < nextSample) {
            return;
        }
        nextSample = now + sampleTicks;
        ServerLevel level = to.level().getServer().getLevel(at.dimension());
        ProbeSampler.Sample sample = level == null || !ProbeSampler.mayRead(level, at.pos(), to)
                ? ProbeSampler.Sample.NONE
                : ProbeSampler.sample(level, at.pos());
        series.add(sample.load());
        rate = sample.rate();
        energy = sample;
        out.accept(new ProbeSamplePayload(containerId, sample.load(), sample.rate(), sample.energy(), sample.supply(),
                sample.demand(), sample.received()));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (viewer == null) {
            return true;
        }
        return player.getItemInHand(InteractionHand.MAIN_HAND).getItem() instanceof SignalProbeItem
                || player.getItemInHand(InteractionHand.OFF_HAND).getItem() instanceof SignalProbeItem;
    }
}
