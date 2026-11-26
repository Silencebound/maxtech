package dev.silencebound.maxtech.storage.controller.module;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import dev.silencebound.maxtech.storage.network.ClusterNode;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.storage.controller.recorder.RecordTexts;
import net.minecraft.server.level.ServerLevel;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * A controller expansion's block entity: a node of the storage network that links to the cluster's other blocks (and,
 * for a channel array, to cables and devices), and the position of the core whose cluster it is active in, set and
 * cleared by that core. It is no device for the device limit, holds nothing and never ticks. Breaking it asks its core
 * to find its cluster again; the network stays up meanwhile. The network devices that were expansions before M7R item 4
 * have their own block entity ({@link ServiceDeviceBlockEntity}).
 *
 * <p>
 * The telemetry recorder, ledger module and alarm module are built into the controller since M7R item 3; their blocks
 * are inert. An old ledger module still holds its rows and an old alarm module its settings until a controller's fill
 * meets the block and takes them ({@link #takeOldLedger}, {@link #takeOldAlarm}): once taken they are gone from the
 * block, so nothing moves twice, and the block's drop carries nothing more.
 */
public final class ExpansionBlockEntity extends StorageDeviceBlockEntity implements ClusterNode {
    /** {@link #core()} when the expansion is in no cluster or over its cap. */
    public static final long NO_CORE = Long.MIN_VALUE;
    private static final String CORE = "core";
    private static final String ALARM = "alarm";
    private static final String LEDGER = "ledger";
    private static final Codec<LedgerBook<StackKey>> LEDGER_CODEC = LedgerBook.codec(StackKey.CODEC);

    private long core = NO_CORE;
    /** An old alarm module's settings not yet moved into a controller; null once moved, and for other kinds. */
    private @Nullable AlarmSettings oldAlarm;
    /** An old ledger module's book not yet moved into a controller; null once moved, and for other kinds. */
    private @Nullable LedgerBook<StackKey> oldLedger;

    public ExpansionBlockEntity(BlockPos pos, BlockState state) {
        super(ExpansionContent.EXPANSION_BLOCK_ENTITY.get(), pos, state);
    }

    /** Whether this old ledger or alarm module still holds rows or settings for a controller to take. */
    public boolean holdsRecords() {
        return oldAlarm != null || oldLedger != null;
    }

    /** The old alarm module's settings not yet moved into a controller; null once moved, and for other kinds. */
    public @Nullable AlarmSettings oldAlarm() {
        return oldAlarm;
    }

    /** The old alarm module's settings, given up: null afterwards, and for other kinds. Server side. */
    public @Nullable AlarmSettings takeOldAlarm() {
        AlarmSettings settings = oldAlarm;
        if (settings != null) {
            oldAlarm = null;
            setChanged();
        }
        return settings;
    }

    /** The old ledger module's book, given up: null afterwards, and for other kinds. Server side. */
    public @Nullable LedgerBook<StackKey> takeOldLedger() {
        LedgerBook<StackKey> book = oldLedger;
        if (book != null) {
            oldLedger = null;
            setChanged();
        }
        return book;
    }

    public ModuleKind kind() {
        return getBlockState().getBlock() instanceof ModuleBlock block ? block.kind() : ModuleKind.CAPACITOR_BANK;
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook, through the base class.
    @Override
    public void onLoad() {
        super.onLoad();
        if (holdsRecords() && level instanceof ServerLevel server) {
            // An old module that still holds rows or settings: the controller it touches takes them on its next fill.
            ControllerBlockEntity entity = coreEntity();
            if (entity != null) {
                entity.requestDetect();
            } else {
                ExpansionBlock.askCore(server, worldPosition);
            }
        }
    }

    @Override
    public Role role() {
        return Role.OTHER;
    }

    @Override
    public boolean linksOutside() {
        // Retired blocks link to nothing outside the controller (M7R).
        return false;
    }

    /** The position of the core whose cluster this expansion is active in, or {@link #NO_CORE}. */
    public long core() {
        return core;
    }

    /** The loaded core of {@link #core()}, or null. */
    public @Nullable ControllerBlockEntity coreEntity() {
        Level current = level;
        if (core == NO_CORE || current == null) {
            return null;
        }
        BlockPos pos = BlockPos.of(core);
        return current.isLoaded(pos) && current.getBlockEntity(pos) instanceof ControllerBlockEntity entity
                ? entity
                : null;
    }

    /** The core at {@code corePos} took this expansion into its cluster: it lights up. Server side. */
    public void join(long corePos) {
        if (core != corePos) {
            core = corePos;
            setChanged();
        }
        Level current = level;
        if (current != null && getBlockState().getBlock() instanceof ExpansionBlock
                && getBlockState().getValue(ExpansionBlock.LOAD) == 0) {
            ExpansionBlock.showLoad(current, worldPosition, 1);
        }
    }

    /** The core let this expansion go (it broke, or the cluster shrank): it goes dark. Server side. */
    public void leave() {
        if (core != NO_CORE) {
            core = NO_CORE;
            setChanged();
        }
        Level current = level;
        if (current != null) {
            ExpansionBlock.showLoad(current, worldPosition, 0);
        }
    }

    /** Broken (not unloaded): the core finds its cluster again on the next tick. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        ControllerBlockEntity entity = coreEntity();
        if (entity != null && level != null && !level.isClientSide()) {
            entity.requestDetect();
        }
    }

    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        ModuleKind kind = kind();
        String key = kind.builtIn()
                ? RecordTexts.RETIRED_INFO
                : kind.card() ? ExpansionContent.RETIRED_CARD_KEY : ExpansionContent.RETIRED_KEY;
        sink.line(Component.translatable(key));
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        AlarmSettings settings = oldAlarm;
        if (settings != null && !settings.equals(AlarmSettings.DEFAULT)) {
            // Not moved into a controller yet: the item keeps it for the next one it is placed against.
            components.set(ModDataComponents.ALARM.get(), settings);
        }
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        AlarmSettings settings = components.get(ModDataComponents.ALARM.get());
        if (settings != null && kind() == ModuleKind.ALARM_MODULE) {
            oldAlarm = settings;
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (core != NO_CORE) {
            output.putLong(CORE, core);
        }
        AlarmSettings settings = oldAlarm;
        if (settings != null) {
            output.store(ALARM, AlarmSettings.CODEC, settings);
        }
        LedgerBook<StackKey> book = oldLedger;
        if (book != null) {
            output.store(LEDGER, LEDGER_CODEC, book);
        }
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        core = input.getLongOr(CORE, NO_CORE);
        // Only an old alarm or ledger module holds these, until a controller takes them.
        oldAlarm = input.read(ALARM, AlarmSettings.CODEC).orElse(null);
        oldLedger = input.read(LEDGER, LEDGER_CODEC).filter(book -> book.rows() > 0 || book.unrecorded() > 0)
                .orElse(null);
    }
}
