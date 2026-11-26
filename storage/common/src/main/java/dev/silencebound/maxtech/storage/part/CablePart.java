package dev.silencebound.maxtech.storage.part;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.DeviceLink;
import dev.silencebound.maxtech.storage.network.LinkLook;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import dev.silencebound.maxtech.storage.network.SwitchSource;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * One part on one face of a storage cable: a single-face Storage device living in the cable's block. It is a network
 * device on the cable's node ({@link NetworkDevice}): it joins the cable's network with the cable, holds a channel
 * along the cable's route (none if it is a {@link dev.silencebound.maxtech.storage.network.ChannelFree}) and keeps its
 * tasks in its {@link DeviceLink}, exactly like a device block. The cable's face it sits on links to nothing else.
 *
 * <p>
 * Subclasses give their {@link #role()}, and override what their block form does: {@link #use} (open a menu),
 * {@link #save}/{@link #load} for their own data, {@link #lookVariant()} when their look changes. Instances exist on
 * both sides; on the client only the type and the synced data are meaningful. Never ticks.
 */
public abstract class CablePart implements NetworkDevice {
    private static final String OWNER = "owner";
    private static final String LABEL = "label";
    private static final String SWITCHED_OFF = "switched_off";
    private static final String SWITCHED_OFF_BY = "switched_off_by";
    private static final String POWER_PRIORITY = "power_priority";
    private static final String JOIN_SEQ = "join_seq";

    private final PartType type;
    private final CableParts host;
    private final Direction face;
    private final DeviceLink link = new DeviceLink(this);
    private OwnerRecord owner = OwnerRecord.UNOWNED;
    private boolean removed;
    private boolean started;

    protected CablePart(PartType type, CableParts host, Direction face) {
        this.type = type;
        this.host = host;
        this.face = face;
    }

    public final PartType type() {
        return type;
    }

    /** The cable's face this part sits on. */
    public final Direction face() {
        return face;
    }

    /** The parts of the cable this part sits on. */
    public final CableParts host() {
        return host;
    }

    /** The cable's position. */
    public final BlockPos pos() {
        return host.pos();
    }

    @Override
    public final DeviceLink deviceLink() {
        return link;
    }

    @Override
    public final long nodePos() {
        return host.pos().asLong();
    }

    @Override
    public List<StorageProvider> providers() {
        return List.of();
    }

    @Override
    public boolean isRemoved() {
        return removed || host.isRemoved();
    }

    @Override
    public OwnerRecord ownerRecord() {
        return owner;
    }

    /** Sets the owner, normally the player who put the part on. Server side. */
    public void setOwner(OwnerRecord owner) {
        this.owner = owner;
        host.changed();
    }

    /**
     * A number the cable model draws different looks by (a port's mode); 0 by default. Synced with the part, so a
     * change must go through {@link CableParts#lookChanged()}.
     */
    public int lookVariant() {
        return 0;
    }

    /** Sends the cable to clients so the part's front shows its new line ({@link LinkLook}). Server side. */
    @Override
    public void linkLookChanged(LinkLook look) {
        if (!removed) {
            host.linkChanged();
        }
    }

    /**
     * The weak redstone signal (0 to 15) the part gives the block it faces; 0 by default. A part whose signal changes
     * tells its neighbours with {@link #signalChanged()}.
     */
    public int signal() {
        return 0;
    }

    /**
     * Whether the cable links past this part on its face now. Only a part type that sits in the cable's line
     * ({@link PartType#inLine()}) may say true; false by default. A change must go through
     * {@link CableParts#lookChanged()} so the network re-reads the cable's links.
     */
    public boolean linksThrough() {
        return false;
    }

    /**
     * A tick the part asked for with {@link CableParts#scheduleTick} came due, or one another part on the same cable
     * asked for (they share the cable block's scheduled tick). Server side; nothing by default.
     */
    public void scheduledTick() {
    }

    /** Tells the blocks around the cable that {@link #signal()} changed. Server side. */
    protected final void signalChanged() {
        Level level = host.level();
        if (level != null && !level.isClientSide()) {
            level.updateNeighborsAt(host.pos(), level.getBlockState(host.pos()).getBlock());
        }
    }

    /** Right-click with an empty hand (or an item the part does not use) on the part. Both sides. */
    public InteractionResult use(Player player) {
        return InteractionResult.PASS;
    }

    /**
     * A left-click on the part as the player starts breaking it (vanilla's block {@code attack}: server side, survival
     * and adventure only). Nothing by default.
     */
    public void attack(Player player) {
    }

    /** Right-click with {@code stack} on the part; {@code TRY_WITH_EMPTY_HAND} falls through to {@link #use}. */
    public InteractionResult useItemOn(ItemStack stack, Player player, InteractionHand hand) {
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    /** Sets the label (see {@link NetworkDevice#setLabel}) and saves the cable when it changed. */
    @Override
    public void setLabel(DeviceLabel label) {
        if (link.setLabel(label)) {
            host.changed();
        }
    }

    /** Turns the part off or on for logic (see {@link DeviceLink#setSwitchedOff}) and saves the cable. */
    @Override
    public void setSwitchedOff(boolean off, @Nullable SwitchSource by) {
        if (link.setSwitchedOff(off, by)) {
            host.changed();
        }
    }

    /** Sets the power priority (see {@link DeviceLink#setPowerPriority}) and saves the cable when it changed. */
    @Override
    public void setPowerPriority(int priority) {
        if (link.setPowerPriority(priority)) {
            host.changed();
        }
    }

    /**
     * The item the part drops and pick-block gives; its data goes in here like the block form's. The base carries the
     * label, the logic on/off state and the power priority; subclasses start from {@code super.toItem()}.
     */
    public ItemStack toItem() {
        ItemStack stack = new ItemStack(type.item());
        DeviceLabel label = link.label();
        if (!label.isEmpty()) {
            stack.set(ModDataComponents.DEVICE_LABEL.get(), label);
        }
        if (link.switchedOff()) {
            stack.set(ModDataComponents.SWITCHED_OFF.get(), true);
        }
        if (link.savedPowerPriority() != 0) {
            stack.set(ModDataComponents.POWER_PRIORITY.get(), link.savedPowerPriority());
        }
        return stack;
    }

    /**
     * Takes the label, on/off state and power priority from a placed item, whatever the subclass reads in
     * {@link #readItem}.
     */
    final void readLabel(ItemStack stack) {
        link.setLabel(stack.getOrDefault(ModDataComponents.DEVICE_LABEL.get(), DeviceLabel.NONE));
        link.setSwitchedOff(stack.getOrDefault(ModDataComponents.SWITCHED_OFF.get(), false));
        link.loadPowerPriority(stack.getOrDefault(ModDataComponents.POWER_PRIORITY.get(), 0));
    }

    /**
     * The part comes off for good: takes out the items it holds that do not travel in {@link #toItem()} (a crafting
     * terminal's grid), for the caller to drop or hand over with the part's item. Empty by default. Server side.
     */
    public List<ItemStack> takeContents() {
        return List.of();
    }

    /** Reads what {@link #toItem()} put in a placed item. Server side, before the part joins a network. */
    protected void readItem(ItemStack stack) {
    }

    /**
     * A neighbour of the cable changed: the block at {@code from}, or an unknown one when null (a block update names no
     * position). Server side.
     */
    public void neighbourChanged(@Nullable BlockPos from) {
    }

    /**
     * The part is in a loaded level: placed, or its cable's chunk loaded. Called once per part instance, on the server,
     * before the part's first network work; start caches and schedule tasks here.
     */
    protected void start(ServerLevel level) {
    }

    final void startOnce(ServerLevel level) {
        if (!started && !isRemoved()) {
            started = true;
            start(level);
        }
    }

    /** The block the part faces: the cable's neighbour on its face. */
    public final BlockPos facedPos() {
        return host.pos().relative(face);
    }

    /** The part's info lines for the scanner, the analyzer and Jade. */
    public void addInfo(BlockInfoRequest request, BlockInfoSink sink) {
        NetworkInfo.part(this, type.item().getName(toItem()), request, sink);
    }

    /** Writes the part's own data for saving; subclasses call {@code super}. */
    protected void save(ValueOutput output) {
        if (owner.isOwned()) {
            output.store(OWNER, OwnerRecord.CODEC, owner);
        }
        DeviceLabel label = link.label();
        if (!label.isEmpty()) {
            output.store(LABEL, DeviceLabel.CODEC, label);
        }
        if (link.switchedOff()) {
            output.putBoolean(SWITCHED_OFF, true);
            link.switchedOffBy().ifPresent(by -> output.store(SWITCHED_OFF_BY, SwitchSource.CODEC, by));
        }
        if (link.savedPowerPriority() != 0) {
            output.putInt(POWER_PRIORITY, link.savedPowerPriority());
        }
        if (link.joinSeq() > 0) {
            output.putLong(JOIN_SEQ, link.joinSeq());
        }
    }

    /** Reads what {@link #save} wrote. */
    protected void load(ValueInput input) {
        owner = input.read(OWNER, OwnerRecord.CODEC).orElse(OwnerRecord.UNOWNED);
        link.setLabel(input.read(LABEL, DeviceLabel.CODEC).orElse(DeviceLabel.NONE));
        link.setSwitchedOff(input.getBooleanOr(SWITCHED_OFF, false),
                input.read(SWITCHED_OFF_BY, SwitchSource.CODEC).orElse(null));
        link.loadPowerPriority(input.getIntOr(POWER_PRIORITY, 0));
        // A part's item never carries this (toItem), so a part placed again joins as the newest device.
        link.loadJoinSeq(input.getLongOr(JOIN_SEQ, 0L));
    }

    /** Writes what clients need besides the type and {@link #lookVariant()}; nothing by default. */
    protected void saveSynced(ValueOutput output) {
    }

    /** Reads what {@link #saveSynced} wrote, on both sides. */
    protected void loadSynced(ValueInput input) {
    }

    /** The part came off the cable or the cable is gone: it leaves the network and does nothing more. */
    void markRemoved() {
        removed = true;
        removed();
    }

    /**
     * The part came off its cable or the cable was broken; server and client. A part whose cable's chunk unloads is not
     * told: it only reads {@link #isRemoved()} as true from then on.
     */
    protected void removed() {
    }

    @Override
    public String toString() {
        return type.id() + "@" + face;
    }
}
