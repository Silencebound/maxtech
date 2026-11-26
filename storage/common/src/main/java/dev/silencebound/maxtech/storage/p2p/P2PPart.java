package dev.silencebound.maxtech.storage.p2p;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.part.PartType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * A P2P link end as a part on a storage cable's face: the same {@link P2PEndpoint} as the block, working on the block
 * beyond its face. Its handlers are offered on that face (Storage's NeoForge glue asks the cable's part on the queried
 * side), and a redstone output's signal goes out through the cable's {@link #signal()}. The link stays in the item when
 * the part comes off. Never ticks.
 */
public final class P2PPart extends CablePart implements P2PDevice {
    private final P2PEndpoint endpoint;

    public P2PPart(PartType type, CableParts host, Direction face, P2PKind kind) {
        super(type, host, face);
        this.endpoint = new P2PEndpoint(kind, this, host::changed, this::signalChanged);
    }

    @Override
    public Role role() {
        return Role.TRANSFER;
    }

    /** As the block: one operation a second at rest. */
    @Override
    public int operationsPerSecond() {
        return 1;
    }

    @Override
    public P2PEndpoint endpoint() {
        return endpoint;
    }

    /** Joins the link's channel and makes the faced block ask the cable for handlers again. */
    @Override
    protected void start(ServerLevel level) {
        endpoint.start(level, pos(), face());
        // PLATFORM-LEAK: NeoForge's capability cache; the cable now offers handlers on this face.
        level.invalidateCapabilities(pos());
    }

    @Override
    public void networkChanged(@Nullable StorageNetwork network) {
        endpoint.networkChanged();
    }

    @Override
    public void neighbourChanged(@Nullable BlockPos from) {
        if (from == null || from.equals(facedPos())) {
            endpoint.neighbourChanged();
        }
    }

    @Override
    public int signal() {
        return endpoint.outputSignal();
    }

    /** The linking tool sets the link. */
    @Override
    public InteractionResult useItemOn(ItemStack stack, Player player, InteractionHand hand) {
        if (stack.getItem() instanceof P2PLinkerItem) {
            return P2PLinkerItem.use(this, player, stack);
        }
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        endpoint.addGlance(sink);
    }

    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        endpoint.addDetails(sink);
    }

    /** The part's item with its link. */
    @Override
    public ItemStack toItem() {
        ItemStack stack = super.toItem();
        DataComponentMap.Builder components = DataComponentMap.builder();
        P2PItems.write(components, endpoint.settings());
        stack.applyComponents(components.build());
        return stack;
    }

    @Override
    protected void readItem(ItemStack stack) {
        endpoint.read(P2PItems.read(stack));
    }

    @Override
    protected void save(ValueOutput output) {
        super.save(output);
        endpoint.save(output);
    }

    @Override
    protected void load(ValueInput input) {
        super.load(input);
        endpoint.load(input);
    }
}
