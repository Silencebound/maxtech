package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.network.PowerPriority;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.part.PartType;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * A display panel as a part on a storage cable's face: one screen of its own (parts do not merge into walls; they sit 4
 * px apart on neighbouring cables), with the block's settings and the same {@link ScreenWorker}. Its settings stay in
 * its item and reach clients with the cable's part data. Never ticks.
 */
public final class DisplayPart extends CablePart implements ScreenWorker.Host, DisplayDevice {
    private static final String SETTINGS = "display";

    private DisplaySettings settings = DisplaySettings.DEFAULT;
    private final ScreenWorker worker = new ScreenWorker(this);
    private @Nullable DisplayView view;

    public DisplayPart(PartType type, CableParts host, Direction face) {
        super(type, host, face);
    }

    @Override
    public Role role() {
        return Role.ACCESS;
    }

    /** Load shedding pauses displays first (M7L2): their screens stop updating until the power is back. */
    @Override
    public boolean sheddable() {
        return true;
    }

    @Override
    public int defaultPowerPriority() {
        return PowerPriority.OPTIONAL;
    }

    @Override
    public DisplaySettings settings() {
        return settings;
    }

    @Override
    public int side() {
        return face().get3DDataValue();
    }

    public ScreenWorker worker() {
        return worker;
    }

    /** What the client knows of this screen; client side. */
    public DisplayView view() {
        DisplayView current = view;
        if (current == null) {
            current = new DisplayView();
            view = current;
        }
        return current;
    }

    @Override
    public boolean stillValid(Player player) {
        return !isRemoved() && player.isWithinBlockInteractionRange(pos(), DisplayPanelBlockEntity.REACH_BUFFER);
    }

    /**
     * The item the screen shows, in hand: stores it in the network ({@link DisplayTerminal}); anything else falls
     * through to {@link #use}.
     */
    @Override
    public InteractionResult useItemOn(ItemStack stack, Player player, InteractionHand hand) {
        if (!DisplayTerminal.shows(settings, stack)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        // A refused store falls through to use, so the settings still open.
        return player.level().isClientSide() || DisplayTerminal.store(player, hand, this, settings, pos()) != 0
                ? InteractionResult.SUCCESS
                : InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    /**
     * On a screen showing a list, a click on a row goes to the list unless the player sneaks ({@link #clickRow}). Right
     * after storing on this screen, stores every matching stack of the inventory (a double-click); otherwise opens the
     * settings GUI for players who may configure it ({@link NetworkAccess}).
     */
    @Override
    public InteractionResult use(Player player) {
        if (player instanceof ServerPlayer serverPlayer && !player.isShiftKeyDown() && clickRow(serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer && !DisplayTerminal.storeAll(player, this, settings, pos())
                && NetworkAccess.canConfigure(player, this)) {
            DisplayMenu.open(serverPlayer, this, pos(), face().get3DDataValue(), type().item().getName(toItem()));
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * When the screen shows a list and {@code player} looks at one of its rows within reach, the feed handles the
     * click. Returns whether it did. Server side.
     */
    public boolean clickRow(ServerPlayer player) {
        if (!settings.source().rows() || !player.isWithinBlockInteractionRange(pos(), 1.0)) {
            return false;
        }
        int row = ScreenGeometry.part(pos(), face()).rowAt(player);
        return row >= 0 && worker.clickRow(player, row);
    }

    /** A left-click takes one of the shown item, sneaking a stack ({@link DisplayTerminal}); server side. */
    @Override
    public void attack(Player player) {
        DisplayTerminal.take(player, this, settings, pos(), player.isShiftKeyDown());
    }

    /** Sets the screen's settings and sends them to clients; server side. */
    @Override
    public void applySettings(DisplaySettings settings) {
        if (this.settings.equals(settings)) {
            return;
        }
        this.settings = settings;
        host().changed();
        host().lookChanged();
        worker.settingsChanged();
    }

    @Override
    protected void start(ServerLevel level) {
        worker.start(level);
    }

    @Override
    public void networkChanged(@Nullable StorageNetwork network) {
        worker.networkChanged(network);
    }

    @Override
    public ItemStack toItem() {
        ItemStack stack = super.toItem();
        DisplayItems.write(stack, settings);
        return stack;
    }

    @Override
    protected void readItem(ItemStack stack) {
        DisplaySettings read = DisplayItems.read(stack);
        settings = read == null ? DisplaySettings.DEFAULT : read;
    }

    @Override
    protected void save(ValueOutput output) {
        super.save(output);
        output.store(SETTINGS, DisplaySettings.CODEC, settings);
        worker.chart().save(output);
    }

    @Override
    protected void load(ValueInput input) {
        super.load(input);
        settings = input.read(SETTINGS, DisplaySettings.CODEC).orElse(DisplaySettings.DEFAULT);
        worker.chart().load(input);
    }

    /**
     * The recorded chart changed; the cable saves it, and clients get it by payload only. Only marks the chunk unsaved,
     * without the comparator update of the cable's {@code setChanged()}.
     */
    @Override
    public void recordChanged() {
        Level level = host().level();
        if (level != null) {
            level.blockEntityChanged(host().pos());
        }
    }

    @Override
    protected void saveSynced(ValueOutput output) {
        output.store(SETTINGS, DisplaySettings.CODEC, settings);
    }

    @Override
    protected void loadSynced(ValueInput input) {
        settings = input.read(SETTINGS, DisplaySettings.CODEC).orElse(DisplaySettings.DEFAULT);
    }
}
