package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitEntry;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.Conduits;
import dev.silencebound.maxtech.lib.neoforge.gametest.TubeFx.Effect;
import java.util.ArrayDeque;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.minecraft.server.level.ServerPlayer;
import java.util.Set;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

/**
 * PROTOTYPE (proto/tube-fx only): {@code /maxtech_lib_demo fx_showcase [clear]} builds a row of stations next to the
 * player, one per effect and three combinations. Each station holds one tube system per fluid (water, lava, milk, test
 * nutrient gel, test cryo fluid): a machine block, a straight run into it, a T with a vertical branch ending in an L,
 * and an L turning up; the front row has Lib's integrated junctions and flows out of the machine, the row behind has
 * the moulded fittings and flows into the machine (fed through the open top of the last L). The built box is kept in
 * the world's command storage, so {@code clear} works after a restart.
 */
@EventBusSubscriber(modid = TubeFx.MOD_ID)
public final class TubeFxShowcase {
    private static final Identifier STORAGE = Identifier.fromNamespaceAndPath(TubeFx.MOD_ID, "fx_showcase");
    private static final int STATION_WIDTH = 12;
    private static final int ROW_DEPTH = 11;
    private static final int HEIGHT = 5;

    /** The stations: the effects of each, by id. */
    private static final int[][] STATIONS = {{1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9}, {10}, {1, 3}, {2, 5}, {8, 3}};

    /** Tube pieces of one system, relative to its machine block, and the links between them (index pairs). */
    private static final BlockPos[] PIECES = {new BlockPos(0, 0, 1), new BlockPos(0, 0, 2), new BlockPos(0, 0, 3),
            new BlockPos(0, 0, 4), new BlockPos(0, 1, 4), new BlockPos(0, 2, 4), new BlockPos(0, 3, 4),
            new BlockPos(0, 3, 5), new BlockPos(0, 0, 5), new BlockPos(0, 0, 6), new BlockPos(0, 1, 6)};
    private static final int[][] LINKS = {{0, 1}, {1, 2}, {2, 3}, {3, 4}, {4, 5}, {5, 6}, {6, 7}, {3, 8}, {8, 9},
            {9, 10}};

    private TubeFxShowcase() {
    }

    @SubscribeEvent
    static void register(RegisterCommandsEvent event) {
        event.getDispatcher()
                .register(Commands.literal("maxtech_lib_demo")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("fx_showcase").executes(context -> build(context.getSource()))
                                .then(Commands.literal("clear").executes(context -> clear(context.getSource())))));
    }

    /**
     * With the environment variable {@code MAXTECH_FX_SHOWCASE} set, a player joining builds the showcase and is put in
     * front of it, so an unattended client run meshes every part (used to check the prototype bakes).
     */
    @SubscribeEvent
    static void autoBuild(PlayerEvent.PlayerLoggedInEvent event) {
        if (System.getenv("MAXTECH_FX_SHOWCASE") == null || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        BlockPos at = player.blockPosition();
        build(player.createCommandSourceStack());
        if (System.getenv("MAXTECH_FX_VIEW") != null) {
            // Straight down onto station 10's straight runs, game slowed so the sprite frames can be told apart.
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            if ("side".equals(System.getenv("MAXTECH_FX_VIEW"))) {
                // Between the water and lava systems, looking east at lava's straight run.
                player.teleportTo(player.level(), at.getX() + 2 + 9 * STATION_WIDTH + 1.5, at.getY(),
                        at.getZ() + 2 + 1 + 2.5, Set.of(), -90, 40, true);
            } else {
                player.teleportTo(player.level(), at.getX() + 2 + 9 * STATION_WIDTH + 4.5, at.getY() + 6,
                        at.getZ() + 2 + 1 + 2.5, Set.of(), 0, 90, true);
            }
            player.level().getServer().getCommands().performPrefixedCommand(
                    player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.OWNER), "tick rate 1");
        } else {
            player.teleportTo(player.level(), player.getX() + 8.5, player.getY() + 3, player.getZ() - 3, Set.of(), -35,
                    25, true);
        }
    }

    private record FluidChoice(Fluid fluid, String name) {
    }

    private static List<FluidChoice> fluids() {
        return List.of(new FluidChoice(Fluids.WATER, "Water"), new FluidChoice(Fluids.LAVA, "Lava"),
                new FluidChoice(NeoForgeMod.MILK.value(), "Milk"),
                new FluidChoice(TubeFx.GEL.source.get(), "Test nutrient gel"),
                new FluidChoice(TubeFx.CRYO.source.get(), "Test cryo fluid"));
    }

    private static int build(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        clear(source);
        BlockPos origin = BlockPos.containing(source.getPosition()).offset(2, 0, 2);
        BlockPos max = origin.offset(STATIONS.length * STATION_WIDTH, HEIGHT, 2 * ROW_DEPTH);
        fillAir(level, origin.offset(-1, 0, -1), max);
        CompoundTag tag = new CompoundTag();
        tag.putLong("from", origin.offset(-1, 0, -1).asLong());
        tag.putLong("to", max.asLong());
        level.getServer().getCommandStorage().set(STORAGE, tag);
        List<FluidChoice> fluids = fluids();
        for (int s = 0; s < STATIONS.length; s++) {
            int[] effects = STATIONS[s];
            boolean partial = effects.length > 1 ? effects[1] == Effect.MENISCUS.id : effects[0] == Effect.MENISCUS.id;
            for (int row = 0; row < 2; row++) {
                ConduitEntry<ConduitType> tube = row == 0 ? TubeFx.TUBE : TubeFx.FITTED_TUBE;
                for (int i = 0; i < fluids.size(); i++) {
                    BlockPos base = origin.offset(s * STATION_WIDTH + 2 * i, 0, 1 + row * ROW_DEPTH);
                    int fill = partial ? 1 + i % 3 : 1 + (s + i) % 4;
                    boolean flowing = (s + i) % 3 != 2;
                    FluidChoice fluid = fluids.get(i);
                    buildSystem(level, base, tube.type().get(), fluid.fluid(), fill, flowing, effects, row == 0);
                    String flow = (row == 0 ? "out of machine" : "into machine") + (flowing ? "" : " (idle)");
                    sign(level, base.north(), label(effects), fluid.name(), flow,
                            "fill " + fill + "/4 " + (row == 0 ? "integrated" : "fitted"));
                }
            }
        }
        source.sendSuccess(() -> Component.literal("Built " + STATIONS.length + " tube-fx stations"), true);
        return 1;
    }

    private static String label(int[] effects) {
        StringBuilder out = new StringBuilder();
        for (int id : effects) {
            Effect effect = Effect.byId(id);
            out.append(out.isEmpty() ? "" : " + ").append(id).append(' ')
                    .append(effect == null ? "?" : effects.length > 1 ? shortLabel(effect) : effect.label);
        }
        return out.toString();
    }

    private static String shortLabel(Effect effect) {
        String label = effect.label;
        int space = label.indexOf(' ');
        return space < 0 ? label : label.substring(space + 1);
    }

    /**
     * One system: the machine block at {@code base}, then the pieces. The fluid comes from the machine when
     * {@code fromMachine}, else in through the open top of the last L, so flow runs both ways across a station.
     */
    static void buildSystem(ServerLevel level, BlockPos base, ConduitType type, Fluid fluid, int fill, boolean flowing,
            int[] effects, boolean fromMachine) {
        level.setBlock(base, Blocks.IRON_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        for (BlockPos piece : PIECES) {
            Conduits.place(level, base.offset(piece), type);
        }
        // The first piece links into the machine, as a network's logic would mark it.
        Conduits.host(level, base.offset(PIECES[0]))
                .ifPresent(host -> host.setBlockFaces(TubeFx.NETWORK_ID, ConduitConnections.bit(Direction.NORTH)));
        Direction[] upstream = new Direction[PIECES.length];
        boolean[] seen = new boolean[PIECES.length];
        int root = fromMachine ? 0 : PIECES.length - 1;
        upstream[root] = fromMachine ? Direction.NORTH : Direction.UP;
        seen[root] = true;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            int at = queue.poll();
            for (int[] link : LINKS) {
                int next = link[0] == at ? link[1] : link[1] == at ? link[0] : -1;
                if (next < 0 || seen[next]) {
                    continue;
                }
                seen[next] = true;
                BlockPos step = PIECES[at].subtract(PIECES[next]);
                upstream[next] = Direction.getApproximateNearest(step.getX(), step.getY(), step.getZ());
                queue.add(next);
            }
        }
        int fluidId = BuiltInRegistries.FLUID.getId(fluid);
        for (int p = 0; p < PIECES.length; p++) {
            int value = new TubeFx.Value(fluidId, fill, upstream[p], flowing, effects[0],
                    effects.length > 1 ? effects[1] : 0).encode();
            Conduits.host(level, base.offset(PIECES[p])).ifPresent(host -> host.setRenderValue(value));
        }
    }

    private static void sign(ServerLevel level, BlockPos pos, String... lines) {
        BlockState state = Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION,
                RotationSegment.convertToSegment(Direction.NORTH));
        level.setBlock(pos, state, Block.UPDATE_ALL);
        if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            SignText text = new SignText();
            for (int i = 0; i < lines.length && i < 4; i++) {
                text = text.setMessage(i, Component.literal(lines[i]));
            }
            sign.setText(text, true);
            sign.setWaxed(true);
            sign.setChanged();
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
        }
    }

    private static int clear(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        CompoundTag tag = level.getServer().getCommandStorage().get(STORAGE);
        if (tag.getLong("from").isEmpty() || tag.getLong("to").isEmpty()) {
            return 0;
        }
        fillAir(level, BlockPos.of(tag.getLong("from").get()), BlockPos.of(tag.getLong("to").get()));
        level.getServer().getCommandStorage().set(STORAGE, new CompoundTag());
        source.sendSuccess(() -> Component.literal("Cleared the tube-fx stations"), true);
        return 1;
    }

    private static void fillAir(ServerLevel level, BlockPos from, BlockPos to) {
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            if (!level.getBlockState(pos).isAir()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }
}
