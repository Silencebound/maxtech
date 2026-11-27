package dev.silencebound.maxtech.lib.content;

import dev.silencebound.maxtech.lib.api.conduit.ConduitHost;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.Conduits;
import dev.silencebound.maxtech.lib.api.network.NodeConnectivity;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.conduit.ConduitConnectivity;
import dev.silencebound.maxtech.lib.conduit.ConduitHooks;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Lib's side of the api's {@link Conduits}: addons' conduits go through the same {@link ConduitBuilder} and
 * {@link ConduitBlock} as Storage's cables, with their hooks in the block instead of a subclass. Nothing is declared
 * for datagen that anyone reads (addons ship their resources by hand), so the item model is a placeholder.
 */
public final class LibConduits implements Conduits.Factory {
    // One content builder per addon, so its registrars exist once.
    private final Map<String, Content> contents = new ConcurrentHashMap<>();

    @Override
    public <T extends ConduitType> dev.silencebound.maxtech.lib.api.conduit.ConduitEntry<T> register(
            Conduits.Registration<T> registration) {
        Content content = contents.computeIfAbsent(registration.modId(), Content::create);
        ConduitHooks hooks = new ConduitHooks(registration.wrench(), registration.connectionRule());
        ConduitEntry<T> entry = content.conduit(registration.name(), registration.type())
                .model(ItemModel
                        .existing(Identifier.fromNamespaceAndPath(registration.modId(), "item/" + registration.name())))
                .block((properties, type) -> new ConduitBlock(properties, type, hooks)).register();
        registration.creativeTab().ifPresent(tab -> CreativeTabAdditions.add(tab, entry.item()));
        return entry;
    }

    @Override
    public Optional<ConduitHost> host(Level level, BlockPos pos) {
        if (!ConduitBlockEntity.isChunkPresent(level, pos.getX(), pos.getZ())
                || !(level.getBlockEntity(pos) instanceof ConduitBlockEntity conduit)) {
            return Optional.empty();
        }
        return Optional.of(conduit);
    }

    @Override
    public Optional<Block> blockOf(ConduitType type) {
        return Optional.ofNullable(ConduitBlock.of(type));
    }

    @Override
    public NodeConnectivity connectivity(ServerLevel level, Identifier networkType) {
        return new ConduitConnectivity(level, networkType);
    }

    @Override
    public boolean isChunkPresent(Level level, int x, int z) {
        return ConduitBlockEntity.isChunkPresent(level, x, z);
    }

    @Override
    public boolean place(Level level, BlockPos pos, ConduitType type, @Nullable DyeColor colour) {
        return ConduitBlock.placeConduit(level, pos, type, colour);
    }

    @Override
    public InteractionResult useWrench(Level level, BlockPos pos, Player player, BlockHitResult hit,
            boolean dismantle) {
        return ConduitBlock.useWrench(level, pos, player, hit, dismantle);
    }
}
