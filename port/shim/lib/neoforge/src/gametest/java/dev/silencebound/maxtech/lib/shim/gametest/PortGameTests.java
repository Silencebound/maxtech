package dev.silencebound.maxtech.lib.shim.gametest;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * Port only (1.21.1): runs Lib's 26.1-style GameTests on 1.21.1's framework. On NeoForge 21.1's
 * {@code RegisterGameTestsEvent} it posts 26.1's event ({@link RegisterGameTestsEvent}) and registers
 * {@link #testFunctions()}, which turns every registered test into a {@link TestFunction} whose batch is its environment's
 * id, as in 26.1, where each environment is its own batch. The environment is set up before and torn down after each
 * batch, through 1.21.1's per-batch hooks; those are only filled from {@code @BeforeBatch}/{@code @AfterBatch}
 * annotations, so this class adds to them by reflection.
 */
@EventBusSubscriber(modid = "maxtech_lib")
public final class PortGameTests {
    private static final Map<Holder<TestEnvironmentDefinition<?>>, ResourceLocation> ENVIRONMENTS = new IdentityHashMap<>();
    private static final Map<ResourceLocation, Holder<TestEnvironmentDefinition<?>>> ENVIRONMENT_IDS = new HashMap<>();
    private static final Map<ResourceLocation, FunctionGameTestInstance> TESTS = new LinkedHashMap<>();
    private static final Map<String, TestEnvironmentDefinition.Activation<?>> ACTIVE = new HashMap<>();
    private static boolean posted;

    private PortGameTests() {
    }

    static Holder<TestEnvironmentDefinition<?>> registerEnvironment(ResourceLocation name,
            TestEnvironmentDefinition<?> definition) {
        if (ENVIRONMENT_IDS.containsKey(name)) {
            throw new IllegalStateException("Duplicate test environment " + name);
        }
        Holder<TestEnvironmentDefinition<?>> holder = Holder.direct(definition);
        ENVIRONMENTS.put(holder, name);
        ENVIRONMENT_IDS.put(name, holder);
        return holder;
    }

    static void registerTest(ResourceLocation name, FunctionGameTestInstance test) {
        if (TESTS.putIfAbsent(name, test) != null) {
            throw new IllegalStateException("Duplicate test " + name);
        }
    }

    @SubscribeEvent
    static void register(net.neoforged.neoforge.event.RegisterGameTestsEvent event) {
        if (!posted) {
            posted = true;
            ModLoader.postEvent(new RegisterGameTestsEvent());
        }
        try {
            event.register(PortGameTests.class.getDeclaredMethod("testFunctions"));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Called once by 1.21.1's GameTestRegistry. */
    @GameTestGenerator
    public static Collection<TestFunction> testFunctions() {
        List<TestFunction> functions = new ArrayList<>(TESTS.size());
        Map<String, TestEnvironmentDefinition<?>> batches = new LinkedHashMap<>();
        TESTS.forEach((id, test) -> {
            TestData<Holder<TestEnvironmentDefinition<?>>> data = test.info();
            ResourceLocation environment = ENVIRONMENTS.get(data.environment());
            String batch = environment != null ? environment.toString() : id.toString();
            batches.putIfAbsent(batch, data.environment().value());
            functions.add(new TestFunction(batch, id.toString(), data.structure().toString(), data.rotation(),
                    data.maxTicks(), data.setupTicks(), data.required(), data.manualOnly(), data.maxAttempts(),
                    data.requiredSuccesses(), data.skyAccess(), test::run));
        });
        Map<String, Consumer<ServerLevel>> before = batchFunctions("BEFORE_BATCH_FUNCTIONS");
        Map<String, Consumer<ServerLevel>> after = batchFunctions("AFTER_BATCH_FUNCTIONS");
        batches.forEach((batch, environment) -> {
            if (before.putIfAbsent(batch, level -> setUp(batch, environment, level)) != null
                    | after.putIfAbsent(batch, level -> tearDown(batch)) != null) {
                throw new IllegalStateException("Batch " + batch + " already has before or after functions");
            }
        });
        return functions;
    }

    private static void setUp(String batch, TestEnvironmentDefinition<?> environment, ServerLevel level) {
        TestEnvironmentDefinition.Activation<?> previous = ACTIVE.put(batch,
                TestEnvironmentDefinition.activate(environment, level));
        if (previous != null) {
            previous.teardown();
        }
    }

    private static void tearDown(String batch) {
        TestEnvironmentDefinition.Activation<?> activation = ACTIVE.remove(batch);
        if (activation != null) {
            activation.teardown();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Consumer<ServerLevel>> batchFunctions(String field) {
        try {
            Field f = GameTestRegistry.class.getDeclaredField(field);
            f.setAccessible(true);
            return (Map<String, Consumer<ServerLevel>>) f.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Can't reach GameTestRegistry." + field + " for test environments", e);
        }
    }
}
