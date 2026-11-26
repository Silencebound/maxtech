package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.energy.FeLookups;
import dev.silencebound.maxtech.lib.platform.CachedBlockLookup;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Lib loads as a mod of its own, and the platform seam works. Test functions go through {@link Platform#registry()} on
 * purpose, so a broken registry seam fails here before any content depends on it.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class LibGameTests {
    /** A 1x1x1 air template; tests that need no scene use it. */
    public static final Identifier EMPTY_STRUCTURE = MaxtechLib.id("empty_1x1x1");
    private static final Identifier STRUCTURE = MaxtechLib.id("empty_7x2x3");
    private static final String[] NAMES = {"lib_loaded", "platform_available", "energy_lookups_resolve",
            "cached_lookup_invalidates"};

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        FUNCTIONS.register(NAMES[0], () -> LibGameTests::loaded);
        FUNCTIONS.register(NAMES[1], () -> LibGameTests::platformAvailable);
        FUNCTIONS.register(NAMES[2], () -> LibGameTests::energyLookupsResolve);
        FUNCTIONS.register(NAMES[3], () -> LibGameTests::cachedLookupInvalidates);
    }

    private LibGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("default"));
        event.registerTest(MaxtechLib.id(NAMES[0]),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechLib.id(NAMES[0])),
                        new TestData<>(environment, STRUCTURE, 20, 0, true)));
        for (int i = 1; i < NAMES.length; i++) {
            event.registerTest(MaxtechLib.id(NAMES[i]),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechLib.id(NAMES[i])),
                            new TestData<>(environment, EMPTY_STRUCTURE, 20, 0, true)));
        }
    }

    private static void loaded(GameTestHelper helper) {
        helper.assertTrue(ModList.get().isLoaded(MaxtechLib.MOD_ID), "maxtech_lib is not loaded");
        helper.succeed();
    }

    private static void platformAvailable(GameTestHelper helper) {
        helper.assertTrue(Platform.get().registry() != null, "Platform has no registry helper");
        helper.succeed();
    }

    /** Air exposes no energy; the point is that both lookup paths reach NeoForge without throwing. */
    private static void energyLookupsResolve(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(BlockPos.ZERO);
        helper.assertTrue(FeLookups.BLOCK.find(helper.getLevel(), pos, Direction.NORTH) == null,
                "Air should expose no energy handler");
        helper.assertTrue(FeLookups.BLOCK.cache(helper.getLevel(), pos, Direction.NORTH).find() == null,
                "Cached lookup on air should be empty");
        helper.succeed();
    }

    /**
     * The invalidation listener must fire when a capability appears, and the cache must see it afterwards. Goes through
     * {@code Platform.transfer().items()}, so it also checks that the transfer lookup reaches a vanilla chest.
     */
    private static void cachedLookupInvalidates(GameTestHelper helper) {
        AtomicBoolean invalidated = new AtomicBoolean();
        CachedBlockLookup<?> cache = Platform.get().transfer().items().cache(helper.getLevel(),
                helper.absolutePos(BlockPos.ZERO), Direction.UP, () -> true, () -> invalidated.set(true));
        helper.assertTrue(cache.find() == null, "Empty position should have no item handler");
        helper.setBlock(BlockPos.ZERO, Blocks.CHEST);
        helper.succeedWhen(() -> {
            helper.assertTrue(invalidated.get(), "Invalidation listener did not fire");
            helper.assertTrue(cache.find() != null, "Cache did not pick up the chest");
        });
    }
}
