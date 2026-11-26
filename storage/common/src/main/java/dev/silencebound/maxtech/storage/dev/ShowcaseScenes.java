package dev.silencebound.maxtech.storage.dev;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.board.BoardContent;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cable.CableLook;
import dev.silencebound.maxtech.storage.cell.CellCasing;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.CellData;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.crafting.assembler.AssemblerContent;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuCoreBlockEntity;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.creative.CreativeEnergyContent;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTerminalContent;
import dev.silencebound.maxtech.storage.display.DisplayContent;
import dev.silencebound.maxtech.storage.display.DisplayMode;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlockEntity;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.display.DisplaySourceKind;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.energy.EnergyContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.StorageMachineBlockEntity;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
// PLATFORM-LEAK: transfer types to feed the monitoring scene's network; allowed in common (ADR-0002).
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Builds the showcase scenes ({@link ShowcaseScene}) for the mod-page screenshots: a scene comes from its structure
 * file in {@code docs/release/scenes} when there is one (the owner's in-game edit, saved with {@code scene save}),
 * otherwise from the code draft here. Either way a setup pass then adds what a structure file cannot hold: owners,
 * fresh cell ids, the network's stock, full machine buffers, the crafting job and the monitoring scene's moving
 * cobblestone count. Dev tooling only; the NeoForge glue registers the commands outside production. Server thread only.
 */
public final class ShowcaseScenes {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Layers cleared above the room, for trees standing on the site. */
    private static final int CLEAR_ABOVE = 24;
    /** Bytes a scene file may unpack to; vanilla's structure files are far smaller. */
    private static final long FILE_QUOTA = 64L * 1024 * 1024;
    /** Ticks the monitoring scene's cobblestone count moves, inside the task patience of {@link Showcase}. */
    private static final int FEED_TICKS = 580;
    private static final int FEED_EVERY = 10;
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    /** The last origin of each scene per level, so {@code scene save} knows the region. */
    private static final Map<ServerLevel, Map<ShowcaseScene, BlockPos>> PLACED = Collections
            .synchronizedMap(new WeakHashMap<>());

    private ShowcaseScenes() {
    }

    /** What a placement did: where the scene's origin is, whether it came from its file, and what did not set up. */
    public record Placed(ShowcaseScene scene, BlockPos origin, boolean fromFile, List<String> warnings) {
    }

    /** Forgets the placements; called when the server stops. */
    static void forget() {
        PLACED.clear();
    }

    /** Where {@code scene} was placed last in {@code level} since the server started. */
    public static Optional<BlockPos> placed(ServerLevel level, ShowcaseScene scene) {
        Map<ShowcaseScene, BlockPos> scenes = PLACED.get(level);
        return Optional.ofNullable(scenes == null ? null : scenes.get(scene));
    }

    /** The bottom north-west corner of the scene's box: the origin, {@link ShowcaseScene#BELOW} layers down. */
    public static BlockPos corner(BlockPos origin) {
        return origin.below(ShowcaseScene.BELOW);
    }

    /** The scene's box size. */
    public static Vec3i size() {
        return new Vec3i(ShowcaseScene.WIDTH, ShowcaseScene.BELOW + ShowcaseScene.HEIGHT, ShowcaseScene.DEPTH);
    }

    /** The scene's box for an origin. */
    public static AABB bounds(BlockPos origin) {
        BlockPos corner = corner(origin);
        Vec3i size = size();
        return new AABB(corner.getX(), corner.getY(), corner.getZ(), corner.getX() + size.getX(),
                corner.getY() + size.getY(), corner.getZ() + size.getZ());
    }

    /**
     * The origin of {@code scene} for the start spot {@code start} ({@link SceneSites}): its x and z from the spot, its
     * height the most common surface over its footprint. Loads the site's chunks.
     */
    public static BlockPos site(ServerLevel level, ShowcaseScene scene, BlockPos start) {
        int x0 = SceneSites.originX(scene, start.getX());
        int z0 = SceneSites.originZ(start.getZ());
        loadChunks(level, x0 - SceneSites.APRON, z0 - SceneSites.APRON, x0 + ShowcaseScene.WIDTH - 1 + SceneSites.APRON,
                z0 + ShowcaseScene.DEPTH - 1 + SceneSites.APRON);
        int[] surfaces = new int[ShowcaseScene.WIDTH * ShowcaseScene.DEPTH];
        int i = 0;
        for (int x = x0; x < x0 + ShowcaseScene.WIDTH; x++) {
            for (int z = z0; z < z0 + ShowcaseScene.DEPTH; z++) {
                surfaces[i++] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            }
        }
        return new BlockPos(x0, SceneSites.floorY(surfaces), z0);
    }

    private static void loadChunks(ServerLevel level, int x0, int z0, int x1, int z1) {
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
    }

    /**
     * Places {@code scene} at its site for {@code start}: levels the ground around it, then builds it from its file in
     * the repository at {@code root} if there is one, else from the code draft, and sets it up for {@code player}.
     */
    public static Placed place(ServerLevel level, ShowcaseScene scene, BlockPos start, @Nullable Path root,
            @Nullable ServerPlayer player) {
        BlockPos origin = site(level, scene, start);
        levelSite(level, origin);
        List<String> warnings = new ArrayList<>();
        StructureTemplate template = null;
        if (root != null) {
            Path file = SceneSites.file(root, scene);
            try {
                template = read(level, file).orElse(null);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Scene file {} could not be read", file, e);
                warnings.add("could not read " + file + ": " + e.getMessage() + "; built the code draft instead");
            }
        }
        Placed placed = build(level, scene, origin, player, template);
        warnings.addAll(placed.warnings());
        return new Placed(scene, origin, placed.fromFile(), warnings);
    }

    /**
     * Builds {@code scene} with its origin at {@code origin} from {@code template}, or from the code draft when it is
     * null, without touching the ground around it, and runs the setup pass. Completes all research for {@code player}'s
     * team and makes the controllers theirs.
     */
    public static Placed build(ServerLevel level, ShowcaseScene scene, BlockPos origin, @Nullable ServerPlayer player,
            @Nullable StructureTemplate template) {
        OwnerRecord owner = player == null ? OwnerRecord.UNOWNED : OwnerRecord.of(player);
        if (player != null) {
            Showcase.completeResearch(player);
        }
        List<String> warnings = new ArrayList<>();
        clear(level, origin);
        if (template != null) {
            BlockPos corner = corner(origin);
            if (!template.placeInWorld(level, corner, corner, new StructurePlaceSettings().setIgnoreEntities(true),
                    level.getRandom(), Block.UPDATE_ALL)) {
                warnings.add("the structure file placed nothing");
            }
            renew(level, origin);
        } else {
            Showcase.Pad pad = new Showcase.Pad(level, origin, owner, warnings);
            switch (scene) {
                case HALL -> hall(pad);
                case CRAFTING -> crafting(pad);
                case PROCESSING -> processing(pad);
                case MONITORING -> monitoring(pad);
                case CABLES -> cables(pad);
            }
        }
        // A scene from a file keeps what its machines held, so they may take less than the draft fills in.
        setup(level, scene, origin, owner, player, warnings, template == null ? warnings : new ArrayList<>());
        PLACED.computeIfAbsent(level, key -> new EnumMap<>(ShowcaseScene.class)).put(scene, origin.immutable());
        return new Placed(scene, origin.immutable(), template != null, warnings);
    }

    /**
     * Removes everything in the scene's box at {@code origin}, without drops, and the queued work inside it. Devices
     * run their removal (a CPU cancels its job); what they spill is discarded.
     */
    public static void clear(ServerLevel level, BlockPos origin) {
        AABB bounds = bounds(origin);
        Showcase.dropTasks(level, bounds);
        BlockPos corner = corner(origin);
        Vec3i size = size();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // Twice: parts and cables first notice their neighbours leaving, then whatever they left behind goes.
        for (int pass = 0; pass < 2; pass++) {
            for (int x = 0; x < size.getX(); x++) {
                for (int z = 0; z < size.getZ(); z++) {
                    for (int y = size.getY() - 1; y >= 0; y--) {
                        pos.setWithOffset(corner, x, y, z);
                        if (level.getBlockState(pos).isAir()) {
                            continue;
                        }
                        if (level.getBlockEntity(pos) instanceof Container container) {
                            container.clearContent();
                        }
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(),
                                Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                    }
                }
            }
        }
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, bounds.inflate(1))) {
            item.discard();
        }
    }

    /**
     * Levels the ground around a scene at {@code origin}: over the footprint and an {@link SceneSites#APRON} around it,
     * clears everything from the floor's top up (trees included) and fills from the ground up to the floor with the
     * ground blocks found at the site's centre, so the scene sits in the terrain.
     */
    static void levelSite(ServerLevel level, BlockPos origin) {
        int floor = origin.getY();
        BlockPos centre = origin.offset(ShowcaseScene.WIDTH / 2, -1, ShowcaseScene.DEPTH / 2);
        BlockState top = level.getBlockState(centre);
        if (!top.isCollisionShapeFullBlock(level, centre) || top.hasBlockEntity()) {
            top = Blocks.GRASS_BLOCK.defaultBlockState();
        }
        BlockState sub = top.is(Blocks.GRASS_BLOCK) || top.is(Blocks.PODZOL) || top.is(Blocks.MYCELIUM)
                ? Blocks.DIRT.defaultBlockState()
                : top;
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = origin.getX() - SceneSites.APRON; x < origin.getX() + ShowcaseScene.WIDTH
                + SceneSites.APRON; x++) {
            for (int z = origin.getZ() - SceneSites.APRON; z < origin.getZ() + ShowcaseScene.DEPTH
                    + SceneSites.APRON; z++) {
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int sky = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z),
                        floor + ShowcaseScene.HEIGHT + CLEAR_ABOVE);
                for (int y = floor; y < Math.max(sky, floor + ShowcaseScene.HEIGHT); y++) {
                    pos.set(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, air, Block.UPDATE_CLIENTS);
                    }
                }
                for (int y = ground; y < floor - 1; y++) {
                    level.setBlock(pos.set(x, y, z), sub, Block.UPDATE_CLIENTS);
                }
                level.setBlock(pos.set(x, floor - 1, z), top, Block.UPDATE_CLIENTS);
            }
        }
        AABB site = new AABB(origin.getX() - SceneSites.APRON, floor - 1, origin.getZ() - SceneSites.APRON,
                origin.getX() + ShowcaseScene.WIDTH + SceneSites.APRON, floor + ShowcaseScene.HEIGHT + CLEAR_ABOVE,
                origin.getZ() + ShowcaseScene.DEPTH + SceneSites.APRON);
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, site)) {
            item.discard();
        }
    }

    /** The scene's box at {@code origin} as a vanilla structure, without entities. */
    public static StructureTemplate capture(ServerLevel level, BlockPos origin) {
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, corner(origin), size(), false, List.of(Blocks.STRUCTURE_VOID));
        return template;
    }

    /**
     * Saves the scene's box at {@code origin} to its file under the repository root {@code root} (gzip NBT in vanilla's
     * structure format) and returns the file.
     */
    public static Path save(ServerLevel level, ShowcaseScene scene, BlockPos origin, Path root) throws IOException {
        Path file = SceneSites.file(root, scene);
        Path folder = file.getParent();
        if (folder != null) {
            Files.createDirectories(folder);
        }
        NbtIo.writeCompressed(capture(level, origin).save(new CompoundTag()), file);
        return file;
    }

    /** Reads a scene file, brought up to the current data version; empty when there is no file. */
    public static Optional<StructureTemplate> read(ServerLevel level, Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.create(FILE_QUOTA));
        CompoundTag current = DataFixTypes.STRUCTURE.updateToCurrentVersion(level.getServer().getFixerUpper(), tag,
                NbtUtils.getDataVersion(tag, 500));
        StructureTemplate template = new StructureTemplate();
        template.load(level.holderLookup(Registries.BLOCK), current);
        return Optional.of(template);
    }

    /**
     * Makes a scene from a file independent of the world it was saved in: its cells get new ids (their contents lived
     * in that world's save data; the setup pass stocks the network again) and its CPUs drop the jobs they were running.
     */
    private static void renew(ServerLevel level, BlockPos origin) {
        BlockPos corner = corner(origin);
        Vec3i size = size();
        for (BlockPos pos : BlockPos.betweenClosed(corner, corner.offset(size).offset(-1, -1, -1))) {
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof DriveBlockEntity drive) {
                List<ItemStack> cells = new ArrayList<>();
                for (ItemStack cell = drive.removeCell(); !cell.isEmpty(); cell = drive.removeCell()) {
                    cells.add(cell);
                }
                for (int i = cells.size() - 1; i >= 0; i--) {
                    ItemStack cell = cells.get(i);
                    CellData data = cell.get(ModDataComponents.CELL.get());
                    if (data != null) {
                        cell.set(ModDataComponents.CELL.get(), data.withId(CellData.NO_ID).withStats(0, 0, 0, 0));
                    }
                    drive.insertCell(cell);
                }
            } else if (entity instanceof CpuCoreBlockEntity cpu) {
                UUID job = cpu.job();
                if (job != null) {
                    cpu.cancel(job);
                }
            }
        }
    }

    /**
     * The live state of a scene, found by block rather than by place so it works on the owner's edits too: owners of
     * the controllers, the first controller's network stock, full machines and fuelled furnaces, and per scene the
     * crafting job or the moving cobblestone count.
     */
    private static void setup(ServerLevel level, ShowcaseScene scene, BlockPos origin, OwnerRecord owner,
            @Nullable ServerPlayer player, List<String> warnings, List<String> fillWarnings) {
        BlockPos controller = null;
        BlockPos corner = corner(origin);
        Vec3i size = size();
        for (BlockPos pos : BlockPos.betweenClosed(corner, corner.offset(size).offset(-1, -1, -1))) {
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof ControllerBlockEntity block) {
                block.setOwner(owner);
                if (controller == null) {
                    controller = pos.immutable();
                }
            } else if (entity instanceof LogicControllerBlockEntity logic) {
                logic.setOwner(owner);
            } else if (entity instanceof StorageMachineBlockEntity machine) {
                Showcase.fillMachine(machine, fillWarnings, machineInputs(machine.getBlockState()));
            } else if (entity instanceof AbstractFurnaceBlockEntity furnace && furnace.getItem(1).isEmpty()) {
                furnace.setItem(1, new ItemStack(Items.COAL, 64));
            }
        }
        if (controller == null) {
            warnings.add("no controller in scene " + scene.id());
            return;
        }
        for (Stock stock : stock(scene)) {
            Showcase.stockLater(level, controller, stock.key(), stock.amount());
        }
        UUID requester = player == null ? new UUID(0, 0) : player.getUUID();
        switch (scene) {
            case CRAFTING -> Showcase.later(level, controller, new Jobs(level, controller, requester));
            case MONITORING -> Showcase.later(level, controller, new Feeder(level, controller));
            default -> {
            }
        }
    }

    private static ItemStack[] machineInputs(BlockState state) {
        if (state.is(MachineContent.FUEL_GENERATOR.get())) {
            return new ItemStack[]{new ItemStack(Items.COAL, 64)};
        }
        if (state.is(MachineContent.LITHOGRAPH.get())) {
            return new ItemStack[]{new ItemStack(MaterialContent.SILICON.get(), 16)};
        }
        if (state.is(MachineContent.FABRICATOR.get())) {
            return new ItemStack[]{new ItemStack(Items.COPPER_INGOT, 16)};
        }
        if (state.is(MachineContent.ASSEMBLER.get())) {
            return new ItemStack[]{new ItemStack(MaterialContent.LOGIC_DIE.get(), 4),
                    new ItemStack(MaterialContent.INSULATED_COPPER_WIRE.get(), 8),
                    new ItemStack(MaterialContent.OSCILLATOR.get(), 4),
                    new ItemStack(MaterialContent.CIRCUIT_BOARD.get(), 4)};
        }
        return new ItemStack[0];
    }

    private record Stock(StackKey key, long amount) {
    }

    private static Stock items(Item item, long amount) {
        return new Stock(ItemKey.of(item), amount);
    }

    /** What each scene's network holds: enough variety for a terminal shot, and what its machines and jobs use. */
    private static List<Stock> stock(ShowcaseScene scene) {
        return switch (scene) {
            case HALL ->
                List.of(items(Items.COBBLESTONE, 20_000), items(Items.STONE, 4_000), items(Items.OAK_LOG, 1_500),
                        items(Items.OAK_PLANKS, 3_000), items(Items.IRON_INGOT, 900), items(Items.GOLD_INGOT, 300),
                        items(Items.COPPER_INGOT, 1_200), items(Items.REDSTONE, 2_000), items(Items.DIAMOND, 180),
                        items(Items.EMERALD, 60), items(Items.LAPIS_LAZULI, 640), items(Items.COAL, 2_500),
                        items(Items.GLASS, 800), items(Items.SAND, 1_500), items(Items.QUARTZ, 400),
                        new Stock(FluidKey.of(Fluids.WATER), 64_000), new Stock(FluidKey.of(Fluids.LAVA), 16_000));
            case CRAFTING -> List.of(items(Items.RAW_IRON, 512), items(Items.OAK_PLANKS, 512), items(Items.COAL, 256),
                    items(Items.COBBLESTONE, 2_000), items(Items.IRON_INGOT, 32));
            case PROCESSING -> List.of(items(Items.COAL, 512), items(MaterialContent.SILICON.get(), 256),
                    items(Items.COPPER_INGOT, 256), items(MaterialContent.CIRCUIT_BOARD.get(), 64),
                    items(MaterialContent.INSULATED_COPPER_WIRE.get(), 128), items(Items.COBBLESTONE, 1_000));
            case MONITORING -> List.of(items(Items.COBBLESTONE, 100), items(Items.IRON_INGOT, 400),
                    items(Items.REDSTONE, 600), items(Items.OAK_PLANKS, 800));
            case CABLES -> List.of(items(Items.COBBLESTONE, 4_096), items(Items.IRON_INGOT, 256));
        };
    }

    /** Starts the crafting scene's jobs once its stock is in: ingots for the furnaces, sticks for the assembler. */
    private static final class Jobs implements ShowcaseBridges.Task {
        private final ServerLevel level;
        private final BlockPos controller;
        private final UUID requester;

        Jobs(ServerLevel level, BlockPos controller, UUID requester) {
            this.level = level;
            this.controller = controller;
            this.requester = requester;
        }

        @Override
        public boolean tick() {
            NetworkState network = StorageNetworks.network(level, StorageNetworks.ID, controller);
            if (network == null || !network.isPowered() || Showcase.stockPending(level, network)) {
                return false;
            }
            submit(network, ItemKey.of(Items.IRON_INGOT), 64);
            submit(network, ItemKey.of(Items.STICK), 64);
            return true;
        }

        private void submit(NetworkState network, StackKey key, long amount) {
            CraftingPlanner.Result plan = CraftingPlanner.planNow(network, key, amount);
            JobQueue.Submission submission = JobQueue.submit(network, controller.asLong(), plan,
                    JobQueue.Request.player(requester));
            if (submission.job().isEmpty()) {
                LOGGER.warn("Crafting scene: the job for {} was refused: {}", key, submission.refusal());
            }
        }
    }

    /**
     * Moves the monitoring scene's cobblestone between about 40 and 160 for {@link #FEED_TICKS} ticks, so its graph has
     * a line and the "lamp" switch (on over 100) goes on and off.
     */
    private static final class Feeder implements ShowcaseBridges.Task {
        private final ServerLevel level;
        private final BlockPos controller;
        private int ticks;

        Feeder(ServerLevel level, BlockPos controller) {
            this.level = level;
            this.controller = controller;
        }

        @Override
        public boolean tick() {
            NetworkState network = StorageNetworks.network(level, StorageNetworks.ID, controller);
            if (network == null || !network.isPowered()) {
                return false;
            }
            ticks++;
            if (ticks % FEED_EVERY == 0) {
                long target = 100 + Math.round(60 * Math.sin(ticks / (double) FEED_EVERY * 0.3));
                long have = network.contents().amount(COBBLE);
                try (Transaction transaction = Transaction.openRoot()) {
                    if (target > have) {
                        network.insert(COBBLE, target - have, transaction);
                    } else if (target < have) {
                        network.extract(COBBLE, have - target, transaction);
                    }
                    transaction.commit();
                }
            }
            return ticks >= FEED_TICKS;
        }
    }

    // The code drafts. Local coordinates as in Showcase.Pad: y 0 is the first layer above the floor, the walls stand
    // on z 0 (north) and x 0 (west), device fronts face south towards the camera.

    /** Floor, the layer under it, the north and west walls up to {@code wallTop} (excluded) and a trim row on top. */
    private static void room(Showcase.Pad pad, Block floor, Block wall, Block trim, int wallTop) {
        int w = ShowcaseScene.WIDTH - 1;
        int d = ShowcaseScene.DEPTH - 1;
        pad.fill(0, -2, 0, w, -2, d, Blocks.DIRT.defaultBlockState());
        pad.fill(0, -1, 0, w, -1, d, floor.defaultBlockState());
        pad.fill(0, 0, 0, w, wallTop - 1, 0, wall.defaultBlockState());
        pad.fill(0, 0, 0, 0, wallTop - 1, d, wall.defaultBlockState());
        pad.fill(0, wallTop, 0, w, wallTop, 0, trim.defaultBlockState());
        pad.fill(0, wallTop, 0, 0, wallTop, d, trim.defaultBlockState());
    }

    /** A two-block controller at ({@code x}, 0..1, {@code z}) with the creative source hidden in the floor under it. */
    private static void controller(Showcase.Pad pad, int x, int z) {
        pad.place(x, -1, z, CreativeEnergyContent.CREATIVE_ENERGY_SOURCE.get(), null);
        pad.place(x, 0, z, ControllerContent.CONTROLLER.get(), null);
        pad.place(x, 1, z, ControllerContent.CONTROLLER.get(), null);
    }

    private static void cable(Showcase.Pad pad, int x, int y, int z) {
        pad.cable(x, y, z, CableContent.STORAGE_CABLE_TIER_4.type().get(), null);
    }

    /** Item cells for the {@code i}th drive of a wall: three to six of mixed sizes. */
    private static ItemStack[] cells(int i) {
        CellCasing[] casings = {CellCasing.SMALL, CellCasing.MEDIUM, CellCasing.LARGE, CellCasing.BULK};
        ItemStack[] cells = new ItemStack[3 + i % 4];
        for (int c = 0; c < cells.length; c++) {
            cells[c] = Showcase.cell(casings[(i + c) % casings.length], CellContent.ITEM_TYPE.id());
        }
        return cells;
    }

    /**
     * The main hall: a two-block controller with energy cells beside it, a wall of 21 drives with filled cells, a
     * tier-4 cable along the wall under the trim, and two terminals on a counter along the west wall, fed by a cable
     * over them.
     */
    private static void hall(Showcase.Pad pad) {
        room(pad, Blocks.DEEPSLATE_TILES, Blocks.DEEPSLATE_BRICKS, Blocks.POLISHED_DEEPSLATE, 5);
        controller(pad, 2, 1);
        pad.place(3, 0, 1, EnergyContent.TIER_3.get(), null);
        pad.place(3, 1, 1, EnergyContent.TIER_2.get(), null);
        pad.place(3, 2, 1, EnergyContent.TIER_1.get(), null);
        int i = 0;
        for (int x = 4; x <= 10; x++) {
            for (int y = 0; y <= 2; y++) {
                ItemStack[] cells = x == 10 && y == 0
                        ? new ItemStack[]{Showcase.cell(CellCasing.LARGE, CellContent.FLUID_TYPE.id()),
                                Showcase.cell(CellCasing.MEDIUM, CellContent.FLUID_TYPE.id())}
                        : cells(i);
                pad.drive(x, y, 1, Direction.SOUTH, cells);
                i++;
            }
        }
        cable(pad, 2, 2, 1);
        for (int x = 2; x <= 11; x++) {
            cable(pad, x, 3, 1);
        }
        for (int z = 1; z <= 7; z++) {
            cable(pad, 1, 2, z);
        }
        pad.fill(1, 0, 3, 1, 0, 7, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
        pad.place(1, 1, 4, TerminalContent.TERMINAL.get(), Direction.EAST);
        pad.place(1, 1, 6, TerminalContent.TERMINAL.get(), Direction.EAST);
        pad.set(1, 1, 3, Blocks.LANTERN.defaultBlockState());
        pad.set(1, 1, 7, Blocks.LANTERN.defaultBlockState());
    }

    /**
     * The crafting room: controller, two drives and an energy cell, a 3 x 3 tier-3 CPU cluster, and pattern providers
     * behind two furnaces (raw iron to ingots) and a crafting assembler (sticks), each fed by a cable dropping from the
     * run along the wall; a pattern terminal and the request board on the west counter.
     */
    private static void crafting(Showcase.Pad pad) {
        room(pad, Blocks.POLISHED_ANDESITE, Blocks.STONE_BRICKS, Blocks.SMOOTH_STONE, 5);
        controller(pad, 2, 1);
        pad.drive(3, 0, 1, Direction.SOUTH, Showcase.cell(CellCasing.LARGE, CellContent.ITEM_TYPE.id()),
                Showcase.cell(CellCasing.LARGE, CellContent.ITEM_TYPE.id()));
        pad.drive(3, 1, 1, Direction.SOUTH, Showcase.cell(CellCasing.MEDIUM, CellContent.ITEM_TYPE.id()));
        pad.place(3, 2, 1, EnergyContent.TIER_2.get(), null);
        // Bottom row first; the core sits in the top row, where it touches the cable (the parts link to nothing).
        Block[][] cpu = {{CpuContent.STORAGE.get(), CpuContent.STORAGE.get(), CpuContent.STORAGE.get()},
                {CpuContent.LANE.get(), CpuContent.CACHE.get(), CpuContent.LANE.get()},
                {CpuContent.STORAGE.get(), CpuContent.CORE_3.get(), CpuContent.STORAGE.get()}};
        for (int y = 0; y < cpu.length; y++) {
            for (int x = 0; x < cpu[y].length; x++) {
                pad.place(4 + x, y, 1, cpu[y][x], null);
            }
        }
        cable(pad, 2, 2, 1);
        for (int x = 2; x <= 11; x++) {
            cable(pad, x, 3, 1);
        }
        ItemStack ingots = PatternItem.encode(
                Showcase.pattern(CraftingPattern.Kind.PROCESSING, Items.RAW_IRON, 1, Items.IRON_INGOT, 1, null));
        for (int x : new int[]{7, 9}) {
            pad.provider(x, 0, 1, Direction.SOUTH, ingots.copy());
            pad.set(x, 0, 2, Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,
                    Direction.SOUTH));
        }
        pad.provider(11, 0, 1, Direction.SOUTH, PatternItem
                .encode(Showcase.pattern(CraftingPattern.Kind.CRAFTING, Items.OAK_PLANKS, 2, Items.STICK, 4, "stick")));
        pad.place(11, 0, 2, AssemblerContent.ASSEMBLER.get(), null);
        for (int x : new int[]{7, 9, 11}) {
            cable(pad, x, 1, 1);
            cable(pad, x, 2, 1);
        }
        for (int z = 1; z <= 6; z++) {
            cable(pad, 1, 2, z);
        }
        pad.fill(1, 0, 2, 1, 0, 6, Blocks.SMOOTH_STONE.defaultBlockState());
        pad.place(1, 1, 3, PatternTerminalContent.TERMINAL.get(), Direction.EAST);
        pad.place(1, 1, 5, BoardContent.REQUEST_BOARD.get(), Direction.EAST);
    }

    /**
     * The processing line: lithograph, fuel generator, fabricator, assembler and a second generator along the wall,
     * export ports above the lithograph and fabricator feeding their inputs, two more export ports filling chests, and
     * the controller with a drive and a terminal at the east end.
     */
    private static void processing(Showcase.Pad pad) {
        room(pad, Blocks.SMOOTH_STONE, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.POLISHED_BLACKSTONE, 4);
        Block[] machines = {MachineContent.LITHOGRAPH.get(), MachineContent.FUEL_GENERATOR.get(),
                MachineContent.FABRICATOR.get(), MachineContent.ASSEMBLER.get(), MachineContent.FUEL_GENERATOR.get()};
        for (int i = 0; i < machines.length; i++) {
            pad.place(3 + i, 0, 1, machines[i], Direction.SOUTH);
        }
        // No port on the generators: a generator touching a network device joins that network's energy network, whose
        // pass then draws the same buffer the machines are pushed from, and which of the two comes first in a tick
        // decides whether the lithograph runs dry. Off the network, the generators feed only the machines.
        export(pad, 3, MaterialContent.SILICON.get());
        export(pad, 5, Items.COPPER_INGOT);
        export(pad, 9, MaterialContent.CIRCUIT_BOARD.get());
        export(pad, 10, MaterialContent.INSULATED_COPPER_WIRE.get());
        pad.chest(9, 0, 1);
        pad.chest(10, 0, 1);
        for (int x = 3; x <= 11; x++) {
            cable(pad, x, 2, 1);
        }
        controller(pad, 11, 1);
        pad.drive(12, 0, 1, Direction.SOUTH, Showcase.cell(CellCasing.LARGE, CellContent.ITEM_TYPE.id()),
                Showcase.cell(CellCasing.LARGE, CellContent.ITEM_TYPE.id()));
        pad.place(12, 1, 1, TerminalContent.TERMINAL.get(), Direction.SOUTH);
    }

    /** An export port at ({@code x}, 1, 1) facing down into the block under it, moving only {@code item}. */
    private static void export(Showcase.Pad pad, int x, Item item) {
        PortBlockEntity port = pad.entity(pad.place(x, 1, 1, PortContent.PORT.get(), Direction.DOWN),
                PortBlockEntity.class);
        if (port != null) {
            port.setMode(PortMode.EXPORT);
            port.filter().setItem(0, new ItemStack(item));
        }
    }

    /**
     * The monitoring wall: a 4 x 2 display graphing cobblestone over four drives, a 2 x 2 display graphing throughput
     * over the Logic Controller with a lever on its front, two logic-driven condition switches with lamps ("lamp" over
     * 100 cobblestone, "lever lamp" following the lever), fed by a cable along the wall.
     */
    private static void monitoring(Showcase.Pad pad) {
        room(pad, Blocks.POLISHED_TUFF, Blocks.TUFF_BRICKS, Blocks.CHISELED_TUFF_BRICKS, 5);
        controller(pad, 2, 1);
        for (int x = 3; x <= 6; x++) {
            pad.drive(x, 0, 1, Direction.SOUTH, cells(x));
        }
        for (int x = 3; x <= 9; x++) {
            if (x == 7) {
                continue;
            }
            for (int y = 1; y <= 2; y++) {
                pad.place(x, y, 1, DisplayContent.PANEL.get(), Direction.SOUTH);
            }
        }
        DisplayPanelBlockEntity count = pad.entity(pad.pos(3, 1, 1), DisplayPanelBlockEntity.class);
        if (count != null) {
            count.applyToWall(DisplaySettings.DEFAULT.withMode(DisplayMode.LINE)
                    .withSource(DisplaySourceKind.ITEM_COUNT).withItem(COBBLE).withWindow(60));
        }
        DisplayPanelBlockEntity throughput = pad.entity(pad.pos(8, 1, 1), DisplayPanelBlockEntity.class);
        if (throughput != null) {
            throughput.applyToWall(DisplaySettings.DEFAULT.withMode(DisplayMode.LINE)
                    .withSource(DisplaySourceKind.THROUGHPUT).withWindow(60));
        }
        BlockPos logic = pad.place(8, 0, 1, LogicContent.CONTROLLER.get(), Direction.SOUTH);
        pad.set(8, 0, 2, Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH).setValue(LeverBlock.POWERED, true));
        pad.drive(9, 0, 1, Direction.SOUTH, cells(9));
        pad.fill(10, 0, 1, 11, 0, 1, Blocks.TUFF_BRICKS.defaultBlockState());
        pad.logicSwitch(10, 1, 1, "lamp", DyeColor.YELLOW);
        pad.logicSwitch(11, 1, 1, "lever lamp", DyeColor.RED);
        cable(pad, 2, 2, 1);
        for (int x = 2; x <= 9; x++) {
            cable(pad, x, 3, 1);
        }
        Showcase.graphsLater(pad.level, logic, LogicExamples.MONITORING_NAMES, LogicExamples.monitoring());
    }

    /**
     * The cable wall: a controller in the corner with a smart tier-4 trunk rising over it, and one run per listed cable
     * look leaving it across the walls, stepping down from tier 4 to tier 1, into a drive, a terminal, a display and a
     * second drive.
     */
    private static void cables(Showcase.Pad pad) {
        room(pad, Blocks.POLISHED_DIORITE, Blocks.SMOOTH_QUARTZ, Blocks.QUARTZ_BRICKS, 6);
        controller(pad, 1, 1);
        for (int y = 2; y <= 5; y++) {
            pad.cable(1, y, 1, CableContent.cable(CableLook.SMART, 4).type().get(), null);
        }
        for (int i = 0; i < 9; i++) {
            int tier = SceneSites.runTier(i);
            pad.cable(2 + i, 0, 1, CableContent.cable(CableLook.PLAIN, tier).type().get(), null);
            pad.cable(2 + i, 2, 1, CableContent.cable(CableLook.COVERED, tier).type().get(), null);
            pad.cable(2 + i, 4, 1, CableContent.cable(CableLook.SMART, tier).type().get(), null);
            pad.cable(1, 3, 2 + i, CableContent.cable(CableLook.FIBER, tier).type().get(), null);
        }
        pad.drive(11, 0, 1, Direction.SOUTH, cells(1));
        pad.place(11, 2, 1, TerminalContent.TERMINAL.get(), Direction.SOUTH);
        pad.place(11, 4, 1, DisplayContent.PANEL.get(), Direction.SOUTH);
        DisplayPanelBlockEntity panel = pad.entity(pad.pos(11, 4, 1), DisplayPanelBlockEntity.class);
        if (panel != null) {
            panel.applyToWall(DisplaySettings.DEFAULT.withSource(DisplaySourceKind.ITEM_COUNT).withItem(COBBLE));
        }
        pad.drive(1, 3, 11, Direction.EAST, cells(2));
    }
}
