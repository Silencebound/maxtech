package dev.silencebound.maxtech.lib.neoforge.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.silencebound.maxtech.lib.api.conduit.ConduitContents;
import dev.silencebound.maxtech.lib.api.conduit.ConduitEntry;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.Conduits;
import dev.silencebound.maxtech.lib.api.network.Network;
import dev.silencebound.maxtech.lib.api.network.NetworkLogic;
import dev.silencebound.maxtech.lib.api.network.NetworkType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.jspecify.annotations.Nullable;

/**
 * A showcase of pipe looks for picking one by eye (dev runs only: the GameTest source set, which no jar contains).
 * {@code /maxtech_lib_demo pipes} (gamemaster) builds, in an area in front of the player that must be clear, labelled
 * rows of demo pipes there, Lib's shell look with frame bars as the lab tube has it, each variant as a straight run, an
 * L, a T and a crossing filled with water or lava: bar colours, glass tints, bar thickness, textured bar patterns and
 * three ways to mark tiers 1 to 4. {@code /maxtech_lib_demo pipes clear} removes every showcase in loaded chunks. Each
 * variant is a demo conduit type of its own, registered here only.
 */
@EventBusSubscriber(modid = ConduitApiGameTests.MOD_ID)
public final class PipeShowcase {
    /** On every label; the title also carries {@link #AREA} with the showcase's box. */
    static final String TAG = "maxtech_lib_demo_pipes";
    private static final String AREA = TAG + "_area=";
    private static final String MOD_ID = ConduitApiGameTests.MOD_ID;
    private static final Identifier NETWORK_ID = Identifier.fromNamespaceAndPath(MOD_ID, "test_pipe_demo");

    static {
        // Holds nothing: the showcase sets each pipe's contents itself.
        NetworkType.register(NETWORK_ID, level -> Conduits.connectivity(level, NETWORK_ID),
                level -> new NetworkLogic() {
                    @Override
                    public void tick(Network network) {
                    }
                });
    }

    private static final float THICKNESS = 0.5F;
    private static final int GUNMETAL = 0x2C3238;
    private static final int OWN_COLOURS = 0xFFFFFF;
    private static final Identifier CYAN = demo("glass_cyan");
    private static final ConduitLook.Shell LAB = ConduitLook.shell(CYAN).withBorderColour(GUNMETAL).withBorder(1)
            .withFrameBars();

    // Shapes as cells of a 3 x 3 square (across, forward), centre (1, 1).
    private static final int[][] STRAIGHT = {{0, 1}, {1, 1}, {2, 1}};
    private static final int[][] L = {{1, 1}, {2, 1}, {1, 2}};
    private static final int[][] T = {{0, 1}, {1, 1}, {2, 1}, {1, 2}};
    private static final int[][] CROSS = {{0, 1}, {1, 1}, {2, 1}, {1, 0}, {1, 2}};
    private static final int[][][] SHAPES = {STRAIGHT, L, T, CROSS};
    /** Blocks from one shape (or tier) to the next, and from one variant's row to the next. */
    private static final int STEP = 4;
    /** Cells left of the first shape, under the row's label. */
    private static final int LABEL_CELLS = 2;
    private static final int COLUMN_WIDTH = LABEL_CELLS + STEP * SHAPES.length + 3;
    private static final int HEIGHT = 4;

    static final List<Section> SECTIONS = List.of(
            new Section(0, "Bar colours (1 px bars, clear cyan glass)",
                    List.of(row("gunmetal (lab tube)", "colour_gunmetal", LAB),
                            row("copper", "colour_copper", LAB.withBorderColour(0xB87333)),
                            row("brass", "colour_brass", LAB.withBorderColour(0xC9A447)),
                            row("white", "colour_white", LAB.withBorderColour(0xEDEFF0)),
                            row("near-black", "colour_black", LAB.withBorderColour(0x141618)))),
            new Section(0, "Glass tints (gunmetal bars)",
                    List.of(row("clear cyan (lab tube)", "glass_cyan", LAB),
                            row("smoky", "glass_smoky", glass("glass_smoky")),
                            row("amber", "glass_amber", glass("glass_amber")),
                            row("frosted", "glass_frosted", glass("glass_frosted")))),
            new Section(1, "Bar thickness (gunmetal, 8 px pipes)",
                    List.of(row("1 px (lab tube)", "bar_1px", LAB), row("2 px", "bar_2px", LAB.withBorder(2)))),
            new Section(1, "Bar patterns (textured bars, own colours)",
                    List.of(row("striped", "pattern_striped", textured("bar_striped")),
                            row("riveted", "pattern_riveted", textured("bar_riveted")),
                            row("hazard", "pattern_hazard", textured("bar_hazard")),
                            row("striped, 2 px", "pattern_striped_2px", textured("bar_striped").withBorder(2)),
                            row("riveted, 2 px", "pattern_riveted_2px", textured("bar_riveted").withBorder(2)),
                            row("hazard, 2 px", "pattern_hazard_2px", textured("bar_hazard").withBorder(2)))),
            new Section(1, "Tiers 1 to 4, left to right",
                    List.of(tiers("shade per tier", "tier_shade", LAB.withBorderColour(0x8C959D),
                            LAB.withBorderColour(0x5F686F), LAB.withBorderColour(0x394148),
                            LAB.withBorderColour(0x16191C)),
                            tiers("band count per tier", "tier_bands", textured("bar_bands_1"), textured("bar_bands_2"),
                                    textured("bar_bands_3"), textured("bar_bands_4")),
                            tiers("pattern per tier", "tier_pattern", LAB, textured("bar_striped"),
                                    textured("bar_riveted"), textured("bar_hazard")),
                            tiers("shade per tier, 2 px", "tier_shade_2px",
                                    LAB.withBorder(2).withBorderColour(0x8C959D),
                                    LAB.withBorder(2).withBorderColour(0x5F686F),
                                    LAB.withBorder(2).withBorderColour(0x394148),
                                    LAB.withBorder(2).withBorderColour(0x16191C)),
                            tiers("band count per tier, 2 px", "tier_bands_2px", textured("bar_bands_1").withBorder(2),
                                    textured("bar_bands_2").withBorder(2), textured("bar_bands_3").withBorder(2),
                                    textured("bar_bands_4").withBorder(2)),
                            tiers("pattern per tier, 2 px", "tier_pattern_2px", LAB.withBorder(2),
                                    textured("bar_striped").withBorder(2), textured("bar_riveted").withBorder(2),
                                    textured("bar_hazard").withBorder(2)))));

    private PipeShowcase() {
    }

    /** A section of rows, in column {@code column} (0 left, 1 right) under a heading. */
    record Section(int column, String title, List<Row> rows) {
    }

    /** One labelled row: a variant shown as the four shapes, or tiers 1 to 4 shown as a T each. */
    record Row(String label, List<ConduitEntry<ConduitType>> types, boolean tiers) {
    }

    @SubscribeEvent
    static void register(RegisterCommandsEvent event) {
        event.getDispatcher()
                .register(Commands.literal("maxtech_lib_demo")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("pipes").executes(context -> build(context.getSource()))
                                .then(Commands.literal("clear").executes(context -> {
                                    int removed = clear(context.getSource().getLevel());
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Removed " + removed + " pipe showcase(s)"), false);
                                    return removed;
                                }))));
    }

    private static int build(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Direction forward = player.getDirection();
        BlockPos origin = player.blockPosition().relative(forward, 3);
        BoundingBox area;
        try {
            area = build(source.getLevel(), origin, forward);
        } catch (IllegalStateException e) {
            throw new SimpleCommandExceptionType(Component.literal(String.valueOf(e.getMessage()))).create();
        }
        source.sendSuccess(() -> Component.literal("Pipe showcase built from " + area.getCenter().toShortString()
                + "; /maxtech_lib_demo pipes clear removes it"), false);
        return 1;
    }

    /**
     * Removes any earlier showcase (loaded chunks only), then builds from {@code origin}, rows running away along
     * {@code forward}, shapes to its right; returns the area it took. Only showcase blocks are ever removed: when
     * anything else stands in the area, nothing is built and an {@link IllegalStateException} names the block.
     */
    static BoundingBox build(ServerLevel level, BlockPos origin, Direction forward) {
        clear(level);
        Direction right = forward.getClockWise();
        BoundingBox area = area(origin, forward);
        BlockPos obstacle = firstObstacle(level, area);
        if (obstacle != null) {
            throw new IllegalStateException(
                    "area not clear: " + level.getBlockState(obstacle).getBlock() + " at " + obstacle.toShortString());
        }
        label(level, at(origin, forward, right, COLUMN_WIDTH - 1, -1), 2.2,
                "Pipe looks (shell + frame bars); /maxtech_lib_demo pipes clear", 0.8F).addTag(AREA + pack(area));
        int[] rowAt = new int[2];
        int fluid = 0;
        for (Section section : SECTIONS) {
            int across = section.column() * COLUMN_WIDTH;
            label(level, at(origin, forward, right, across + COLUMN_WIDTH / 2 - 1, rowAt[section.column()]), 1.2,
                    section.title(), 0.6F);
            rowAt[section.column()] += 2;
            for (Row row : section.rows()) {
                int along = rowAt[section.column()];
                Fluid filling = fluid++ % 2 == 0 ? Fluids.WATER : Fluids.LAVA;
                label(level, at(origin, forward, right, across, along + 1), 0.6, row.label(), 0.5F);
                for (int cell = 0; cell < SHAPES.length; cell++) {
                    int[][] shape = row.tiers() ? T : SHAPES[cell];
                    ConduitType type = row.types().get(row.tiers() ? cell : 0).type().get();
                    int left = across + LABEL_CELLS + cell * STEP;
                    List<BlockPos> placed = new ArrayList<>();
                    for (int[] at : shape) {
                        BlockPos pos = at(origin, forward, right, left + at[0], along + at[1]);
                        if (Conduits.place(level, pos, type)) {
                            placed.add(pos);
                        }
                    }
                    ConduitContents contents = ConduitContents.ofFluid(Optional.of(filling), 3, Optional.empty(),
                            false);
                    for (BlockPos pos : placed) {
                        Conduits.host(level, pos).ifPresent(host -> host.setContents(contents));
                    }
                    if (row.tiers()) {
                        label(level, at(origin, forward, right, left + 1, along + 1), 0.9, "T" + (cell + 1), 0.5F);
                    }
                }
                rowAt[section.column()] += STEP;
            }
            rowAt[section.column()] += 1;
        }
        return area;
    }

    /** The box a showcase built from {@code origin} along {@code forward} takes. */
    static BoundingBox area(BlockPos origin, Direction forward) {
        Direction right = forward.getClockWise();
        int depth = 0;
        for (int column = 0; column < 2; column++) {
            depth = Math.max(depth, columnDepth(column));
        }
        return BoundingBox.fromCorners(origin.relative(right, -1).relative(forward, -1),
                origin.relative(right, 2 * COLUMN_WIDTH).relative(forward, depth).above(HEIGHT - 1));
    }

    /** Removes every showcase whose title is in a loaded chunk: its blocks and labels; returns how many. */
    static int clear(ServerLevel level) {
        List<? extends Display.TextDisplay> labels = level.getEntities(EntityType.TEXT_DISPLAY,
                display -> display.entityTags().contains(TAG));
        int removed = 0;
        for (Display.TextDisplay display : labels) {
            for (String tag : display.entityTags()) {
                if (tag.startsWith(AREA)) {
                    removeDemo(level, unpack(tag.substring(AREA.length())));
                    removed++;
                }
            }
        }
        for (Display.TextDisplay display : labels) {
            display.discard();
        }
        return removed;
    }

    /** How far the sections of {@code column} reach forward. */
    private static int columnDepth(int column) {
        int depth = 0;
        for (Section section : SECTIONS) {
            if (section.column() == column) {
                depth += 2 + STEP * section.rows().size() + 1;
            }
        }
        return depth;
    }

    /** Whether {@code state} is one of the showcase's own demo conduit blocks. */
    private static boolean isDemo(BlockState state) {
        for (Section section : SECTIONS) {
            for (Row row : section.rows()) {
                for (ConduitEntry<ConduitType> entry : row.types()) {
                    if (Conduits.blockOf(entry.type().get()).filter(state::is).isPresent()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Removes the demo conduit blocks in {@code area}; every other block is left alone. */
    private static void removeDemo(ServerLevel level, BoundingBox area) {
        for (BlockPos pos : BlockPos.betweenClosed(area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(),
                area.maxZ())) {
            if (isDemo(level.getBlockState(pos))) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS);
            }
        }
    }

    /** The first position in {@code area} holding something that is not air, or null when it is clear. */
    private static @Nullable BlockPos firstObstacle(ServerLevel level, BoundingBox area) {
        for (BlockPos pos : BlockPos.betweenClosed(area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(),
                area.maxZ())) {
            if (!level.getBlockState(pos).isAir()) {
                return pos.immutable();
            }
        }
        return null;
    }

    private static Display.TextDisplay label(ServerLevel level, BlockPos pos, double lift, String text, float scale) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:text_display");
        tag.putString("text", text);
        tag.putString("billboard", "center");
        tag.putInt("line_width", 400);
        CompoundTag transformation = new CompoundTag();
        transformation.put("scale", floats(scale, scale, scale));
        transformation.put("translation", floats(0, 0, 0));
        transformation.put("left_rotation", floats(0, 0, 0, 1));
        transformation.put("right_rotation", floats(0, 0, 0, 1));
        tag.put("transformation", transformation);
        Vec3 at = Vec3.atBottomCenterOf(pos).add(0, lift, 0);
        Entity entity = EntityType.loadEntityRecursive(tag, level, EntitySpawnReason.COMMAND, e -> {
            e.snapTo(at.x, at.y, at.z, 0, 0);
            return e;
        });
        if (!(entity instanceof Display.TextDisplay display)) {
            throw new IllegalStateException("no text display for " + text);
        }
        display.addTag(TAG);
        if (!level.addFreshEntity(display)) {
            throw new IllegalStateException("label not added: " + text + " at " + pos);
        }
        return display;
    }

    private static ListTag floats(float... values) {
        ListTag list = new ListTag();
        for (float value : values) {
            list.add(FloatTag.valueOf(value));
        }
        return list;
    }

    private static BlockPos at(BlockPos origin, Direction forward, Direction right, int across, int along) {
        return origin.relative(right, across).relative(forward, along);
    }

    private static String pack(BoundingBox box) {
        return box.minX() + "," + box.minY() + "," + box.minZ() + "," + box.maxX() + "," + box.maxY() + ","
                + box.maxZ();
    }

    private static BoundingBox unpack(String text) {
        String[] parts = text.split(",", -1);
        int[] values = new int[6];
        for (int i = 0; i < values.length; i++) {
            values[i] = Integer.parseInt(parts[i]);
        }
        return new BoundingBox(values[0], values[1], values[2], values[3], values[4], values[5]);
    }

    private static Row row(String label, String name, ConduitLook.Shell look) {
        return new Row(label, List.of(type(name, look)), false);
    }

    private static Row tiers(String label, String name, ConduitLook.Shell... looks) {
        List<ConduitEntry<ConduitType>> types = new ArrayList<>();
        for (int i = 0; i < looks.length; i++) {
            types.add(type(name + "_" + (i + 1), looks[i]));
        }
        return new Row(label, List.copyOf(types), true);
    }

    private static ConduitEntry<ConduitType> type(String name, ConduitLook.Shell look) {
        // test_ keeps them out of the lang check; GameTest fixtures have no English names.
        return Conduits.builder(MOD_ID, "test_pipe_demo_" + name,
                item -> new ConduitType(NETWORK_ID, name, 0xE4F4F4, THICKNESS, item)).look(look).register();
    }

    private static ConduitLook.Shell glass(String sprite) {
        return ConduitLook.shell(demo(sprite)).withBorderColour(GUNMETAL).withBorder(1).withFrameBars();
    }

    private static ConduitLook.Shell textured(String bar) {
        return LAB.withBorderColour(OWN_COLOURS).withBarTexture(demo(bar));
    }

    private static Identifier demo(String sprite) {
        return Identifier.fromNamespaceAndPath(MOD_ID, "block/pipe_demo/" + sprite);
    }
}
