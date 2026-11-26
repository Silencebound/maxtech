package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.authlib.GameProfile;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.machine.StorageMachineBlockEntity;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import dev.silencebound.maxtech.storage.neoforge.datagen.ResearchSourceDatagen;
import dev.silencebound.maxtech.storage.network.NetworkContent;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.research.source.ItemAnalysis;
import dev.silencebound.maxtech.storage.research.source.MachineObserverBlock;
import dev.silencebound.maxtech.storage.research.source.MachineObserverBlockEntity;
import dev.silencebound.maxtech.storage.research.source.ResearchScannerItem;
import dev.silencebound.maxtech.storage.research.source.SourceContent;
import dev.silencebound.maxtech.storage.research.source.SourceData;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.research.source.MachineObserverItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.TradeSets;
import net.minecraft.world.item.trading.VillagerTrade;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Research sources (M6b area E): the machine observer (points per watched runs, the daily cap, no ticker), the scanner
 * (first scan per team, cooldown), research notes (read and used up, in chest loot, sold by librarians) and the
 * analyzer (first analysis per team, diagnostics unchanged), and the preset point scaling. Every test makes its own
 * players, so each has a fresh team.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ResearchSourceGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final BlockPos MACHINE = new BlockPos(2, 1, 1);
    private static final List<String> PLAIN = new ArrayList<>();

    static {
        plain("source_observer_points_per_runs", ResearchSourceGameTests::observerRuns);
        plain("source_observer_daily_cap", ResearchSourceGameTests::observerCap);
        plain("source_scanner_first_scan_per_team", ResearchSourceGameTests::scanner);
        plain("source_notes_read_and_used_up", ResearchSourceGameTests::notes);
        plain("source_librarian_trade", ResearchSourceGameTests::trade);
        plain("source_analyzer_first_analysis", ResearchSourceGameTests::analyzer);
        plain("source_analyzer_diagnostics", ResearchSourceGameTests::diagnostics);
        plain("source_data_saves_and_loads", ResearchSourceGameTests::saves);
        function("source_notes_in_chest_loot", helper -> chestLoot(helper, true));
        function("source_no_notes_without_chance", helper -> chestLoot(helper, false));
        function("source_preset_relaxed", ResearchSourceGameTests::relaxed);
        function("source_preset_expert", ResearchSourceGameTests::expert);
        function("source_preset_off", ResearchSourceGameTests::off);
    }

    private ResearchSourceGameTests() {
    }

    private static void plain(String name, Consumer<GameTestHelper> test) {
        PLAIN.add(name);
        function(name, test);
    }

    private static void function(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("research_sources"),
                StorageTestEnvironment.plain());
        for (String name : PLAIN) {
            StorageTests.test(event, plain, name, STRUCTURE, 400);
        }
        env(event, "source_notes_in_chest_loot", "research.sources.notes.lootChance", "1.0");
        env(event, "source_no_notes_without_chance", "research.sources.notes.lootChance", "0.0");
        env(event, "source_preset_relaxed", "research.preset", "relaxed");
        env(event, "source_preset_expert", "research.preset", "expert");
        env(event, "source_preset_off", "research.preset", "off");
    }

    private static void env(RegisterGameTestsEvent event, String name, String option, String value) {
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id(name), new StorageTestEnvironment(option, value, false)),
                name, STRUCTURE, 100);
    }

    /**
     * An observer facing an owned lithograph gives its owner's team a point per 20 finished runs: 19 counted runs give
     * nothing, the machine's next real run gives the point. An observer beside the machine facing away counts nothing,
     * and the observer block has no ticker.
     */
    private static void observerRuns(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = NetworkGameTests.player(level, "source_observer");
        ResearchTeam team = Research.team(owner, true);
        helper.setBlock(MACHINE, MachineContent.block(MachineKind.LITHOGRAPH));
        MachineObserverBlockEntity watching = observer(helper, MACHINE.east(), Direction.WEST, owner);
        MachineObserverBlockEntity away = observer(helper, MACHINE.above(), Direction.UP, owner);
        BlockState state = helper.getBlockState(MACHINE.east());
        helper.assertTrue(state.getTicker(level, SourceContent.OBSERVER_BLOCK_ENTITY.get()) == null,
                "the observer has a ticker");
        for (int i = 0; i < 19; i++) {
            watching.machineRan(level);
        }
        helper.assertValueEqual(team.points(), 0L, "no point before 20 runs");
        StorageMachineBlockEntity machine = helper.getBlockEntity(MACHINE, StorageMachineBlockEntity.class);
        MachineGameTests.insert(machine, new ItemStack(MaterialContent.SILICON.get()));
        MachineGameTests.charge(machine, 5_000);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertValueEqual(team.points(), 1L, "a point at the 20th run");
            helper.assertValueEqual(watching.runs(), 0, "runs start again");
            helper.assertValueEqual(away.runs(), 0, "an observer facing away counts nothing");
        }).thenSucceed();
    }

    /**
     * One observer gives at most 200 points a day however many runs it sees; an unowned observer gives nothing. The day
     * change itself is {@code ObserverTallyTest}'s.
     */
    private static void observerCap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = NetworkGameTests.player(level, "source_capped");
        ResearchTeam team = Research.team(owner, true);
        MachineObserverBlockEntity observer = observer(helper, MACHINE.east(), Direction.WEST, owner);
        long given = 0;
        for (int i = 0; i < 20 * 210; i++) {
            given += observer.machineRan(level);
        }
        helper.assertValueEqual(given, 200L, "points given");
        helper.assertValueEqual(team.points(), 200L, "team points");
        helper.assertValueEqual(observer.pointsToday(), 200, "points today");
        // Broken and placed again, the observer keeps its count: the cap still holds.
        BlockPos at = helper.absolutePos(MACHINE.east());
        List<ItemStack> drops = Block.getDrops(helper.getBlockState(MACHINE.east()), level, at, observer, null,
                ItemStack.EMPTY);
        helper.assertValueEqual(drops.size(), 1, "one drop");
        ItemStack dropped = drops.getFirst();
        helper.assertTrue(dropped.has(DataComponents.BLOCK_ENTITY_DATA), "the drop keeps the count");
        List<Component> tooltip = new ArrayList<>();
        dropped.getItem().appendHoverText(dropped, Item.TooltipContext.of(level), TooltipDisplay.DEFAULT, tooltip::add,
                TooltipFlag.NORMAL);
        helper.assertTrue(
                tooltip.stream()
                        .anyMatch(line -> line.getContents() instanceof TranslatableContents t
                                && t.getKey().equals(MachineObserverItem.POINTS_KEY)),
                "tooltip shows the points: " + tooltip);
        helper.setBlock(MACHINE.east(), Blocks.AIR);
        ServerPlayer placer = connected(level, "source_capped_placer");
        helper.setBlock(MACHINE.east().below(), Blocks.STONE);
        BlockPos below = helper.absolutePos(MACHINE.east().below());
        dropped.useOn(new UseOnContext(level, placer, InteractionHand.MAIN_HAND, dropped,
                new BlockHitResult(Vec3.atCenterOf(below).add(0, 0.5, 0), Direction.UP, below, false)));
        MachineObserverBlockEntity placed = helper.getBlockEntity(MACHINE.east(), MachineObserverBlockEntity.class);
        helper.assertValueEqual(placed.pointsToday(), 200, "points today after placing again");
        placed.setOwner(OwnerRecord.of(owner));
        for (int i = 0; i < 40; i++) {
            helper.assertValueEqual(placed.machineRan(level), 0L, "the cap holds after placing again");
        }
        helper.assertValueEqual(team.points(), 200L, "team points after placing again");
        ItemStack fresh = Block.getDrops(SourceContent.MACHINE_OBSERVER.get().defaultBlockState(), level, at,
                new MachineObserverBlockEntity(at, SourceContent.MACHINE_OBSERVER.get().defaultBlockState()), null,
                ItemStack.EMPTY).getFirst();
        helper.assertFalse(fresh.has(DataComponents.BLOCK_ENTITY_DATA), "a fresh observer drops a plain item");
        helper.setBlock(MACHINE.west(), SourceContent.MACHINE_OBSERVER.get());
        MachineObserverBlockEntity unowned = helper.getBlockEntity(MACHINE.west(), MachineObserverBlockEntity.class);
        for (int i = 0; i < 40; i++) {
            helper.assertValueEqual(unowned.machineRan(level), 0L, "an unowned observer gives nothing");
        }
        helper.succeed();
    }

    /**
     * The first scan of a block type gives the team 5 points; another scan of it gives nothing, nor does the cooldown
     * allow one at once. Another block type gives 5 more, and another team still earns its own first scan.
     */
    private static void scanner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = connected(level, "source_scanner");
        ResearchTeam team = Research.team(player, true);
        helper.setBlock(MACHINE, Blocks.STONE);
        helper.setBlock(MACHINE.east(), Blocks.STONE);
        helper.setBlock(MACHINE.west(), Blocks.DIRT);
        ItemStack scanner = new ItemStack(SourceContent.RESEARCH_SCANNER.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, scanner);
        helper.assertValueEqual(scan(helper, player, MACHINE), InteractionResult.SUCCESS, "first scan");
        helper.assertValueEqual(team.points(), 5L, "points for stone");
        helper.assertTrue(player.getCooldowns().isOnCooldown(scanner), "cooldown after a scan");
        helper.assertValueEqual(scan(helper, player, MACHINE.east()), InteractionResult.FAIL, "refused on cooldown");
        clearCooldown(player, scanner);
        helper.assertValueEqual(scan(helper, player, MACHINE.east()), InteractionResult.SUCCESS, "second stone");
        helper.assertValueEqual(team.points(), 5L, "stone counts once per team");
        clearCooldown(player, scanner);
        scan(helper, player, MACHINE.west());
        helper.assertValueEqual(team.points(), 10L, "dirt is a new type");
        ServerPlayer other = NetworkGameTests.player(level, "source_scanner_other");
        ResearchTeam otherTeam = Research.team(other, true);
        helper.assertValueEqual(ResearchScannerItem.scan(other, Blocks.STONE), ResearchScannerItem.Result.NEW,
                "another team's first stone");
        helper.assertValueEqual(otherTeam.points(), 5L, "another team's points");
        helper.assertValueEqual(ResearchScannerItem.scan(player, Blocks.STONE), ResearchScannerItem.Result.KNOWN,
                "still known for the first team");
        helper.succeed();
    }

    /** Reading a note gives 25 to 50 points and uses one note of the stack up. */
    private static void notes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = NetworkGameTests.player(level, "source_notes");
        // GameTest players start with creative abilities, which keep used items (ItemStack.consume).
        player.getAbilities().instabuild = false;
        ResearchTeam team = Research.team(player, true);
        ItemStack notes = new ItemStack(SourceContent.RESEARCH_NOTES.get(), 3);
        player.setItemInHand(InteractionHand.MAIN_HAND, notes);
        helper.assertTrue(notes.getMaxStackSize() > 1, "notes stack");
        notes.use(level, player, InteractionHand.MAIN_HAND);
        helper.assertTrue(team.points() >= 25 && team.points() <= 50, "points " + team.points());
        helper.assertValueEqual(player.getMainHandItem().getCount(), 2, "one note used up");
        long first = team.points();
        player.getMainHandItem().use(level, player, InteractionHand.MAIN_HAND);
        helper.assertTrue(team.points() - first >= 25 && team.points() - first <= 50, "second note");
        helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "another note used up");
        helper.succeed();
    }

    /** A dungeon chest holds research notes at chance 1 and none at chance 0. */
    private static void chestLoot(GameTestHelper helper, boolean expected) {
        ServerLevel level = helper.getLevel();
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(helper.absolutePos(BlockPos.ZERO)))
                .create(LootContextParamSets.CHEST);
        List<ItemStack> loot = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.SIMPLE_DUNGEON)
                .getRandomItems(params);
        boolean found = false;
        for (ItemStack stack : loot) {
            found |= stack.is(SourceContent.RESEARCH_NOTES.get());
        }
        helper.assertValueEqual(found, expected, "research notes in the dungeon chest");
        helper.succeed();
    }

    /**
     * The notes trade is in the villager trade registry and in the librarian's level-3 trade set next to vanilla's
     * trades, and offers one note for 5 emeralds.
     */
    private static void trade(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Holder<VillagerTrade> trade = level.registryAccess().lookupOrThrow(Registries.VILLAGER_TRADE)
                .getOrThrow(ResearchSourceDatagen.NOTES_TRADE);
        var trades = level.registryAccess().lookupOrThrow(Registries.TRADE_SET).getOrThrow(TradeSets.LIBRARIAN_LEVEL_3)
                .value().getTrades();
        helper.assertTrue(trades.contains(trade), "notes trade in the librarian's level-3 set");
        helper.assertTrue(trades.size() > 1, "vanilla's level-3 trades kept");
        Villager villager = helper.spawn(EntityType.VILLAGER, MACHINE);
        try {
            LootContext context = new LootContext.Builder(
                    new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, villager.position())
                            .withParameter(LootContextParams.THIS_ENTITY, villager)
                            .withParameter(LootContextParams.ADDITIONAL_COST_COMPONENT_ALLOWED, Unit.INSTANCE)
                            .create(LootContextParamSets.VILLAGER_TRADE))
                    .create(Optional.empty());
            MerchantOffer offer = trade.value().getOffer(context);
            if (offer == null) {
                throw helper.assertionException(Component.literal("the notes trade made no offer"));
            }
            helper.assertTrue(offer.getResult().is(SourceContent.RESEARCH_NOTES.get()), "sells notes");
            helper.assertTrue(offer.getCostA().is(Items.EMERALD), "for emeralds");
            helper.assertValueEqual(offer.getCostA().getCount(), 5, "5 emeralds");
        } finally {
            villager.discard();
        }
        helper.succeed();
    }

    /**
     * Using the analyzer in the air with a processor or a cell in the off hand gives 15 points the first time the team
     * analyses that item type; a second analysis of the type gives none, the cooldown refuses one at once, and an item
     * that is neither is not analysed.
     */
    private static void analyzer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = connected(level, "source_analyzer");
        ResearchTeam team = Research.team(player, true);
        ItemStack analyzer = new ItemStack(NetworkContent.NETWORK_ANALYZER.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, analyzer);
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(MaterialContent.BASIC_LOGIC_PROCESSOR.get()));
        helper.assertTrue(analyzer.use(level, player, InteractionHand.MAIN_HAND).consumesAction(), "analysed");
        helper.assertValueEqual(team.points(), 15L, "points for the processor");
        helper.assertValueEqual(analyzer.use(level, player, InteractionHand.MAIN_HAND), InteractionResult.FAIL,
                "refused on cooldown");
        clearCooldown(player, analyzer);
        analyzer.use(level, player, InteractionHand.MAIN_HAND);
        helper.assertValueEqual(team.points(), 15L, "a processor type counts once");
        clearCooldown(player, analyzer);
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(CellContent.SMALL_STORAGE_CELL.get()));
        analyzer.use(level, player, InteractionHand.MAIN_HAND);
        helper.assertValueEqual(team.points(), 30L, "points for the cell");
        clearCooldown(player, analyzer);
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.DIRT));
        helper.assertValueEqual(analyzer.use(level, player, InteractionHand.MAIN_HAND), InteractionResult.PASS,
                "dirt is not analysed");
        helper.assertValueEqual(team.points(), 30L, "nothing for dirt");
        helper.assertFalse(ItemAnalysis.analysable(new ItemStack(Items.DIRT)), "dirt analysable");
        helper.succeed();
    }

    /** The analyzer used on a storage block still prints its full report, with an analysable item in the off hand. */
    private static void diagnostics(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        List<Component> lines = new ArrayList<>();
        Player player = new Player(helper.getLevel(), new GameProfile(UUID.randomUUID(), "source_diagnostics")) {
            @Override
            public GameType gameMode() {
                return GameType.SURVIVAL;
            }

            @Override
            public boolean isClientAuthoritative() {
                return false;
            }

            @Override
            public void sendSystemMessage(Component message) {
                lines.add(message);
            }
        };
        ItemStack analyzer = new ItemStack(NetworkContent.NETWORK_ANALYZER.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, analyzer);
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(MaterialContent.BASIC_LOGIC_PROCESSOR.get()));
        BlockPos at = helper.absolutePos(controller);
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, controller)).thenExecute(() -> {
            InteractionResult result = analyzer.onItemUseFirst(new UseOnContext(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false)));
            helper.assertValueEqual(result, InteractionResult.SUCCESS, "used on the controller");
            helper.assertFalse(lines.isEmpty(), "the full report");
        }).thenSucceed();
    }

    /** The scanned and analysed sets survive the save codec. */
    private static void saves(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "source_saved");
        ResearchTeam team = Research.team(player, true);
        Identifier stone = Identifier.withDefaultNamespace("stone");
        Identifier processor = MaxtechStorage.id("basic_logic_processor");
        SourceData data = SourceData.get(server);
        data.scan(team, stone);
        data.analyse(team, processor);
        var ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Tag saved = SourceData.CODEC.encodeStart(ops, data).getOrThrow();
        SourceData loaded = SourceData.CODEC.parse(ops, saved).getOrThrow();
        helper.assertTrue(loaded.scanned(team, stone), "scanned block");
        helper.assertTrue(loaded.analysed(team, processor), "analysed item");
        helper.assertFalse(loaded.scanned(team, processor), "nothing else");
        helper.succeed();
    }

    /** Relaxed doubles the source points: 10 per scan, and an observer point every 10 runs. */
    private static void relaxed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = NetworkGameTests.player(level, "source_relaxed");
        ResearchTeam team = Research.team(player, true);
        ResearchScannerItem.scan(player, Blocks.STONE);
        helper.assertValueEqual(team.points(), 10L, "scan points");
        MachineObserverBlockEntity observer = observer(helper, MACHINE.east(), Direction.WEST, player);
        long given = 0;
        for (int i = 0; i < 10; i++) {
            given += observer.machineRan(level);
        }
        helper.assertValueEqual(given, 1L, "a point every 10 runs");
        helper.succeed();
    }

    /** Expert halves the source points, rounded half up: 3 per scan, 8 per analysis. */
    private static void expert(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = NetworkGameTests.player(level, "source_expert");
        ResearchTeam team = Research.team(player, true);
        ResearchScannerItem.scan(player, Blocks.STONE);
        helper.assertValueEqual(team.points(), 3L, "scan points");
        ItemAnalysis.analyse(player, new ItemStack(MaterialContent.BASIC_LOGIC_PROCESSOR.get()));
        helper.assertValueEqual(team.points(), 11L, "analysis points");
        helper.succeed();
    }

    /** With research off nothing is recorded or given, and a note is kept. */
    private static void off(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = NetworkGameTests.player(level, "source_off");
        player.getAbilities().instabuild = false;
        ResearchTeam team = Research.team(player, true);
        helper.assertValueEqual(ResearchScannerItem.scan(player, Blocks.STONE), ResearchScannerItem.Result.OFF, "scan");
        helper.assertFalse(SourceData.get(level.getServer()).scanned(team, Identifier.withDefaultNamespace("stone")),
                "nothing recorded");
        ItemStack notes = new ItemStack(SourceContent.RESEARCH_NOTES.get(), 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, notes);
        notes.use(level, player, InteractionHand.MAIN_HAND);
        helper.assertValueEqual(player.getMainHandItem().getCount(), 2, "note kept");
        helper.assertValueEqual(team.points(), 0L, "no points");
        helper.succeed();
    }

    /**
     * A server player whose packets go nowhere: a server-side item cooldown sends a packet, which a plain GameTest
     * player (no connection) cannot.
     */
    private static ServerPlayer connected(ServerLevel level, String name) {
        return new FakePlayer(level, new GameProfile(UUID.randomUUID(), name));
    }

    private static MachineObserverBlockEntity observer(GameTestHelper helper, BlockPos pos, Direction facing,
            Player owner) {
        BlockState state = SourceContent.MACHINE_OBSERVER.get().defaultBlockState()
                .setValue(MachineObserverBlock.FACING, facing);
        helper.setBlock(pos, state);
        state.getBlock().setPlacedBy(helper.getLevel(), helper.absolutePos(pos), state, owner, ItemStack.EMPTY);
        return helper.getBlockEntity(pos, MachineObserverBlockEntity.class);
    }

    private static InteractionResult scan(GameTestHelper helper, Player player, BlockPos pos) {
        BlockPos at = helper.absolutePos(pos);
        return player.getMainHandItem().onItemUseFirst(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false)));
    }

    private static void clearCooldown(Player player, ItemStack stack) {
        player.getCooldowns().removeCooldown(player.getCooldowns().getCooldownGroup(stack));
    }
}
