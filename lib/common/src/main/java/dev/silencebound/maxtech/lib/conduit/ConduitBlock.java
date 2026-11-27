package dev.silencebound.maxtech.lib.conduit;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.conduit.ConduitShapes;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.facade.Facades;
import dev.silencebound.maxtech.lib.info.ConduitInfo;
import java.util.ArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * The block of one conduit type (ADR-0012): every cable is one of these, generated from its type through the content
 * builder ({@code Content.conduit}), with the shared {@link ConduitBlockEntity}. It is drawn at the type's thickness,
 * drops itself through its loot table and registers no ticker.
 */
// Name clash: vanilla has net.minecraft.world.level.block.ConduitBlock (the underwater beacon); this is not it.
public class ConduitBlock extends Block implements EntityBlock {
    /** Action-bar line when a wrench cannot change a face: the type offers no other mode there. */
    public static final String FACE_FIXED_KEY = "message.maxtech.wrench.face_fixed";

    /**
     * Whether the conduit has a facade. Mirrors the block entity's facade ({@link Facades} keeps them in step), because
     * light is cached per block state: a facaded conduit blocks light like the full block it shows.
     */
    public static final BooleanProperty FACADE = BooleanProperty.create("facade");

    // Filled on first use, after registration: conduit types register after blocks, so a block cannot know its type
    // while blocks register.
    private static volatile @Nullable Reference2ObjectOpenHashMap<ConduitType, ConduitBlock> byType;

    private final Supplier<? extends ConduitType> type;
    private final MapCodec<ConduitBlock> codec;
    private @Nullable VoxelShape bareShape;

    /**
     * @param type
     *            resolved lazily, because conduit types register after blocks
     */
    public ConduitBlock(Properties properties, Supplier<? extends ConduitType> type) {
        super(properties);
        this.type = type;
        this.codec = simpleCodec(p -> new ConduitBlock(p, type));
        registerDefaultState(stateDefinition.any().setValue(FACADE, false));
    }

    /** This block's conduit type. Call only after conduit types are registered. */
    public ConduitType type() {
        return type.get();
    }

    /**
     * The block of {@code type}, or {@code null} if it has none (a type registered without the content builder). Any
     * thread, after registration.
     */
    public static @Nullable ConduitBlock of(ConduitType type) {
        Reference2ObjectOpenHashMap<ConduitType, ConduitBlock> blocks = byType;
        if (blocks == null) {
            blocks = new Reference2ObjectOpenHashMap<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                if (block instanceof ConduitBlock conduit) {
                    blocks.put(conduit.type(), conduit);
                }
            }
            byType = blocks;
        }
        return blocks.get(type);
    }

    /** Every conduit block of every mod, in registry order; for client registrations. After registration only. */
    public static List<ConduitBlock> all() {
        List<ConduitBlock> blocks = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block instanceof ConduitBlock conduit) {
                blocks.add(conduit);
            }
        }
        return blocks;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACADE);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return codec;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ConduitBlockEntity(pos, state);
    }

    /**
     * Places a conduit of {@code type} at {@code pos} on the server, as a player placing its item would, and colours it
     * {@code colour}. Returns false if the type has no block or the position is not replaceable. Connections are worked
     * out at once, so callers can check them in the same tick.
     */
    public static boolean placeConduit(Level level, BlockPos pos, ConduitType type, @Nullable DyeColor colour) {
        ConduitBlock block = of(type);
        if (block == null || !level.getBlockState(pos).canBeReplaced()) {
            return false;
        }
        level.setBlock(pos, block.defaultBlockState(), UPDATE_ALL);
        if (!(level.getBlockEntity(pos) instanceof ConduitBlockEntity conduit)) {
            return false;
        }
        conduit.updateConnections();
        if (colour != null) {
            conduit.setColour(colour);
        }
        return true;
    }

    /**
     * Removes the conduit of {@code type} at {@code pos} on the server, e.g. after an overload burnt it; its facade, if
     * any, drops. Does not drop the conduit; callers that want a drop use {@link ConduitType#item()}. Returns false if
     * no conduit of {@code type} is there.
     */
    public static boolean removeConduit(Level level, BlockPos pos, ConduitType type) {
        if (!(level.getBlockEntity(pos) instanceof ConduitBlockEntity conduit) || !conduit.type().equals(type)) {
            return false;
        }
        BlockState facade = conduit.facade();
        if (facade != null) {
            popResource(level, pos, Facades.stack(facade));
        }
        level.removeBlock(pos, false);
        return true;
    }

    /**
     * A wrench used on the conduit at {@code pos} (ADR-0005): cycles the face that was hit or, with {@code dismantle},
     * takes the conduit into the player's inventory. A facade is taken off by a dismantle before the conduit, and a
     * plain use reaches the conduit behind it. Returns {@code SUCCESS} on both sides so the arm swings; only the server
     * changes anything.
     */
    public static InteractionResult useWrench(Level level, BlockPos pos, Player player, BlockHitResult hit,
            boolean dismantle) {
        if (!(level.getBlockEntity(pos) instanceof ConduitBlockEntity conduit)) {
            return InteractionResult.PASS;
        }
        if (conduit.getBlockState().getBlock() instanceof ConduitBlock block) {
            InteractionResult handled = block.wrench(level, pos, player, hit, dismantle);
            if (handled != null) {
                return handled;
            }
        }
        if (dismantle && conduit.facade() != null) {
            // The facade comes off before the conduit, as when mining.
            if (!level.isClientSide()) {
                BlockState facade = Facades.remove(level, pos, conduit);
                if (facade != null) {
                    player.getInventory().placeItemBackInInventory(Facades.stack(facade));
                }
            }
            return InteractionResult.SUCCESS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        ConduitType type = conduit.type();
        if (dismantle) {
            // The same removal as an overload's, so networks and ConduitState.onRemoved behave alike. Silent, as
            // wrench dismantling is (PRD 5.3).
            if (removeConduit(level, pos, type)) {
                player.getInventory().placeItemBackInInventory(new ItemStack(type.item()));
            }
            return InteractionResult.SUCCESS;
        }
        Direction side = conduit.faceAt(behindFacade(conduit, player, hit));
        ConduitFaceMode mode = conduit.cycleFaceMode(side);
        Component sideName = Component.translatable(ConduitInfo.sideKey(side));
        if (mode == null) {
            player.sendOverlayMessage(Component.translatable(FACE_FIXED_KEY, sideName));
        } else {
            player.sendOverlayMessage(Component.translatable(ConduitInfo.FACE_KEY, sideName,
                    Component.translatable(ConduitInfo.faceModeKey(mode))));
            level.playSound(null, pos, SoundEvents.COMPARATOR_CLICK, SoundSource.BLOCKS, 0.5F, 1.0F);
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Lets a conduit block subclass handle a wrench itself before {@link #useWrench} does (Storage's cable parts take a
     * wrench on a part). Returns the result, or {@code null} (the default) to leave the wrench to the conduit. Called
     * on both sides; only the server should change anything.
     */
    protected @Nullable InteractionResult wrench(Level level, BlockPos pos, Player player, BlockHitResult hit,
            boolean dismantle) {
        return null;
    }

    /**
     * With a facade the conduit's shape is a full cube, so a click lands on the facade's surface. For tools that act on
     * a face this finds the hit on the conduit behind it along the player's view, or keeps {@code hit} if the view
     * passes the conduit by.
     */
    private static BlockHitResult behindFacade(ConduitBlockEntity conduit, Player player, BlockHitResult hit) {
        if (conduit.facade() == null) {
            return hit;
        }
        Vec3 from = player.getEyePosition();
        Vec3 to = from.add(player.getViewVector(1.0F).scale(player.blockInteractionRange() + 1.0));
        BlockHitResult inner = conduit.shape().clip(from, to, conduit.getBlockPos());
        return inner == null ? hit : inner;
    }

    /** Breaks the facade first, if there is one; then the conduit through the normal block removal and its loot. */
    // PLATFORM-LEAK: NeoForge's IBlockExtension hook; vanilla has no way to keep the block when breaking a part of it.
    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, ItemStack toolStack,
            boolean willHarvest, FluidState fluid) {
        if (level.getBlockEntity(pos) instanceof ConduitBlockEntity covered && covered.facade() != null) {
            if (!level.isClientSide()) {
                BlockState facade = Facades.remove(level, pos, covered);
                if (facade != null && willHarvest) {
                    popResource(level, pos, Facades.stack(facade));
                }
            }
            return false;
        }
        return super.onDestroyedByPlayer(state, level, pos, player, toolStack, willHarvest, fluid);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit) {
        DyeColor dye = stack.get(DataComponents.DYE);
        if (dye == null || !(level.getBlockEntity(pos) instanceof ConduitBlockEntity conduit)
                || conduit.conduit().colour() == dye) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (!level.isClientSide()) {
            conduit.setColour(dye);
            stack.consume(1, player);
            level.playSound(null, pos, SoundEvents.DYE_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Sneak-use with an empty hand makes the conduit uncoloured again. Vanilla has no dye remover item, and a water
     * bucket would place water whenever the click misses the conduit, so the empty hand is the one gesture that cannot
     * do anything else by accident.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!player.isSecondaryUseActive() || !(level.getBlockEntity(pos) instanceof ConduitBlockEntity conduit)
                || conduit.conduit().colour() == null) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            conduit.setColour(null);
            level.playSound(null, pos, SoundEvents.BRUSH_GENERIC, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
            @Nullable Orientation orientation, boolean movedByPiston) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof ConduitBlockEntity conduit) {
            conduit.updateConnections();
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (state.getValue(FACADE)) {
            // Solid for collision and selection like the block it shows; face tools look behind it (behindFacade).
            return Shapes.block();
        }
        if (level.getBlockEntity(pos) instanceof ConduitBlockEntity conduit) {
            return conduit.shape();
        }
        // No block entity yet (placement checks): the bare core.
        VoxelShape bare = bareShape;
        if (bare == null) {
            bare = ConduitShapes.shape(type().thickness(), 0, 0);
            bareShape = bare;
        }
        return bare;
    }

    /** The loot table's drop (the conduit itself), plus the facade if there is one. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        BlockState facade = params.getOptionalParameter(
                LootContextParams.BLOCK_ENTITY) instanceof ConduitBlockEntity conduit ? conduit.facade() : null;
        if (facade != null) {
            List<ItemStack> all = new ArrayList<>(drops);
            all.add(Facades.stack(facade));
            return all;
        }
        return drops;
    }

    /** A facaded conduit blocks light like the full, solid block it shows ({@link Facades#canMimic} demands one). */
    @Override
    protected int getLightDampening(BlockState state) {
        return state.getValue(FACADE) ? 15 : super.getLightDampening(state);
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state) {
        return !state.getValue(FACADE) && super.propagatesSkylightDown(state);
    }
}
