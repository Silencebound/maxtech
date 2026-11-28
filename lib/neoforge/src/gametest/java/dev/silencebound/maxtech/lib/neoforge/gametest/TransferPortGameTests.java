package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import dev.silencebound.maxtech.lib.api.transfer.PortFilter;
import dev.silencebound.maxtech.lib.api.transfer.PortSettings;
import dev.silencebound.maxtech.lib.neoforge.api.transfer.KindBridges;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Port filters and settings over the built-in kinds, which need registries and tags (JUnit covers the rest). */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class TransferPortGameTests {
    private static final String TAGS = "transfer_filter_tags";
    private static final String SAVED = "transfer_settings_saved";
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        FUNCTIONS.register(TAGS, () -> TransferPortGameTests::tags);
        FUNCTIONS.register(SAVED, () -> TransferPortGameTests::saved);
    }

    private TransferPortGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("transfer"));
        for (String name : List.of(TAGS, SAVED)) {
            Identifier id = MaxtechLib.id(name);
            event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                    new TestData<>(environment, LibGameTests.EMPTY_STRUCTURE, 20, 0, true)));
        }
    }

    /** Item and fluid tags match their members; Lib bridges items and fluids, not energy. */
    private static void tags(GameTestHelper helper) {
        PortFilter<ItemKey> logs = PortFilter.of(ResourceKinds.ITEM, PortFilter.Mode.ALLOW,
                List.of(ItemKey.of(Items.STONE)), List.of(Identifier.withDefaultNamespace("logs")));
        helper.assertTrue(logs.test(ItemKey.of(Items.OAK_LOG)), "oak log in #logs");
        helper.assertTrue(logs.test(ItemKey.of(Items.STONE)), "stone listed");
        helper.assertFalse(logs.test(ItemKey.of(Items.DIRT)), "dirt allowed");
        PortFilter<FluidKey> water = PortFilter.of(ResourceKinds.FLUID, PortFilter.Mode.DENY, List.of(),
                List.of(Identifier.withDefaultNamespace("water")));
        helper.assertFalse(water.test(FluidKey.of(Fluids.WATER)), "water denied");
        helper.assertTrue(water.test(FluidKey.of(Fluids.LAVA)), "lava allowed");
        helper.assertTrue(KindBridges.get(ResourceKinds.ITEM).isPresent(), "item bridge");
        helper.assertTrue(KindBridges.get(ResourceKinds.FLUID).isPresent(), "fluid bridge");
        helper.assertFalse(KindBridges.get(ResourceKinds.ENERGY).isPresent(), "energy bridge");
        helper.succeed();
    }

    /** Settings with item and fluid filters and energy faces save by kind id and load back equal. */
    private static void saved(GameTestHelper helper) {
        PortSettings settings = PortSettings.EMPTY.withMode(ResourceKinds.ITEM, Direction.UP, PortFaceMode.PUSH)
                .withFilter(ResourceKinds.ITEM,
                        Optional.of(PortFilter.of(ResourceKinds.ITEM, PortFilter.Mode.DENY,
                                List.of(ItemKey.of(Items.DIRT)), List.of(Identifier.withDefaultNamespace("logs")))))
                .withMode(ResourceKinds.FLUID, Direction.DOWN, PortFaceMode.PULL).withRate(ResourceKinds.FLUID, 500)
                .withMode(ResourceKinds.ENERGY, Direction.NORTH, PortFaceMode.PUSH);
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Tag saved = PortSettings.CODEC.encodeStart(ops, settings).getOrThrow();
        helper.assertValueEqual(settings, PortSettings.CODEC.parse(ops, saved).getOrThrow(), "loaded settings");
        helper.assertTrue(saved.toString().contains("maxtech_lib:item"), "saved by kind id: " + saved);
        helper.succeed();
    }
}
