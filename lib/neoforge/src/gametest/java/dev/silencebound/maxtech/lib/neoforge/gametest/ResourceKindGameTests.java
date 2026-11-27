package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.resource.AmountUnit;
import dev.silencebound.maxtech.lib.api.resource.EnergyKey;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.KindKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** The built-in resource kinds with registry access, which the JUnit tests of {@code KindKey} cannot build. */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class ResourceKindGameTests {
    private static final String ROUND_TRIP = "resource_kinds_round_trip";
    private static final String REGISTRY = "resource_kinds_registry";
    private static final String SAVED_SHAPE = "resource_kinds_saved_shape";
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        FUNCTIONS.register(ROUND_TRIP, () -> ResourceKindGameTests::roundTrip);
        FUNCTIONS.register(REGISTRY, () -> ResourceKindGameTests::registry);
        FUNCTIONS.register(SAVED_SHAPE, () -> ResourceKindGameTests::savedShape);
    }

    private ResourceKindGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("resource_kinds"));
        for (String name : List.of(ROUND_TRIP, REGISTRY, SAVED_SHAPE)) {
            Identifier id = MaxtechLib.id(name);
            event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                    new TestData<>(environment, LibGameTests.EMPTY_STRUCTURE, 20, 0, true)));
        }
    }

    /**
     * An enchanted, named sword, water with a component and energy save as {@code kind} plus the key's own fields and
     * stream unchanged; draw bases drop the components; units format grouped.
     */
    private static void roundTrip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS),
                3);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Edge"));
        KindKey<ItemKey> item = new KindKey<>(ResourceKinds.ITEM, ItemKey.of(sword));
        KindKey<FluidKey> fluid = new KindKey<>(ResourceKinds.FLUID, FluidKey.of(Fluids.WATER,
                DataComponentPatch.builder().set(DataComponents.CUSTOM_NAME, Component.literal("Holy")).build()));
        KindKey<EnergyKey> energy = new KindKey<>(ResourceKinds.ENERGY, EnergyKey.INSTANCE);

        RegistryOps<Tag> ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        for (KindKey<?> key : List.of(item, fluid, energy)) {
            Tag saved = KindKey.CODEC.encodeStart(ops, key).getOrThrow();
            helper.assertTrue(
                    saved instanceof CompoundTag compound
                            && compound.getStringOr(KindKey.KIND_FIELD, "").equals(key.kind().id().toString()),
                    "saved kind of " + key);
            helper.assertValueEqual(KindKey.CODEC.parse(ops, saved).getOrThrow(), key, "loaded " + key.kind());
        }
        CompoundTag savedItem = (CompoundTag) KindKey.CODEC.encodeStart(ops, item).getOrThrow();
        helper.assertValueEqual(savedItem.getStringOr("id", ""), "minecraft:diamond_sword", "item id field");
        helper.assertTrue(savedItem.contains("components"), "item components field");
        CompoundTag savedEnergy = (CompoundTag) KindKey.CODEC.encodeStart(ops, energy).getOrThrow();
        helper.assertValueEqual(savedEnergy.size(), 1, "energy saves only its kind");

        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        KindKey.STREAM_CODEC.encode(buffer, item);
        KindKey.STREAM_CODEC.encode(buffer, fluid);
        KindKey.STREAM_CODEC.encode(buffer, energy);
        helper.assertValueEqual(KindKey.STREAM_CODEC.decode(buffer), item, "streamed item");
        helper.assertValueEqual(KindKey.STREAM_CODEC.decode(buffer), fluid, "streamed fluid");
        helper.assertValueEqual(KindKey.STREAM_CODEC.decode(buffer), energy, "streamed energy");
        helper.assertValueEqual(buffer.readableBytes(), 0, "bytes left");

        helper.assertValueEqual(item.drawBase(), new KindKey<>(ResourceKinds.ITEM, ItemKey.of(Items.DIAMOND_SWORD)),
                "item draw base");
        helper.assertValueEqual(fluid.drawBase(), new KindKey<>(ResourceKinds.FLUID, FluidKey.of(Fluids.WATER)),
                "fluid draw base");
        helper.assertValueEqual(energy.drawBase(), energy, "energy draw base");
        helper.assertValueEqual(ResourceKinds.FLUID.unit().format(1250).getString(), "1,250 mB", "fluid amount");
        helper.assertValueEqual(ResourceKinds.ENERGY.unit().format(12).getString(), "12 MU", "energy amount");
        helper.assertValueEqual(ResourceKinds.FLUID.unit().perBucket(), 1000L, "mB per bucket");
        helper.succeed();
    }

    /**
     * The built-in kinds save exactly as these literal tags, which worlds already hold: {@code kind} plus the key's own
     * fields, no {@code value}. Needs registries, so not a JUnit test.
     */
    private static void savedShape(GameTestHelper helper) {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        DataComponentPatch small = DataComponentPatch.builder().set(DataComponents.MAX_STACK_SIZE, 16).build();
        CompoundTag components = new CompoundTag();
        components.putInt("minecraft:max_stack_size", 16);

        CompoundTag item = new CompoundTag();
        item.putString("kind", "maxtech_lib:item");
        item.putString("id", "minecraft:diamond_sword");
        assertSaved(helper, ops, new KindKey<>(ResourceKinds.ITEM, ItemKey.of(Items.DIAMOND_SWORD)), item);
        CompoundTag itemWithComponents = item.copy();
        itemWithComponents.put("components", components.copy());
        assertSaved(helper, ops, new KindKey<>(ResourceKinds.ITEM, ItemKey.of(Items.DIAMOND_SWORD, small)),
                itemWithComponents);

        CompoundTag fluid = new CompoundTag();
        fluid.putString("kind", "maxtech_lib:fluid");
        fluid.putString("id", "minecraft:water");
        assertSaved(helper, ops, new KindKey<>(ResourceKinds.FLUID, FluidKey.of(Fluids.WATER)), fluid);
        CompoundTag fluidWithComponents = fluid.copy();
        fluidWithComponents.put("components", components.copy());
        assertSaved(helper, ops, new KindKey<>(ResourceKinds.FLUID, FluidKey.of(Fluids.WATER, small)),
                fluidWithComponents);

        CompoundTag energy = new CompoundTag();
        energy.putString("kind", "maxtech_lib:energy");
        assertSaved(helper, ops, new KindKey<>(ResourceKinds.ENERGY, EnergyKey.INSTANCE), energy);
        helper.succeed();
    }

    private static void assertSaved(GameTestHelper helper, RegistryOps<Tag> ops, KindKey<?> key, CompoundTag expected) {
        helper.assertValueEqual(KindKey.CODEC.encodeStart(ops, key).getOrThrow(), expected, "saved " + key);
        helper.assertValueEqual(KindKey.CODEC.parse(ops, expected).getOrThrow(), key, "loaded " + expected);
    }

    /** The registry is frozen once the game runs; the built-ins come first and map back by id and index. */
    private static void registry(GameTestHelper helper) {
        helper.assertTrue(ResourceKinds.isFrozen(), "the registry is not frozen after common setup");
        boolean refused = false;
        try {
            ResourceKinds.register(MaxtechLib.id("too_late"), ItemKey.MAP_CODEC, ItemKey.STREAM_CODEC, key -> key,
                    AmountUnit.ITEMS);
        } catch (IllegalStateException expected) {
            refused = true;
        }
        helper.assertTrue(refused, "a kind registered after common setup");
        List<ResourceKind<?>> builtIn = List.of(ResourceKinds.ITEM, ResourceKinds.FLUID, ResourceKinds.ENERGY);
        for (int i = 0; i < builtIn.size(); i++) {
            ResourceKind<?> kind = builtIn.get(i);
            helper.assertValueEqual(kind.index(), i, "index of " + kind);
            helper.assertTrue(ResourceKinds.byIndex(i) == kind, "byIndex(" + i + ")");
            helper.assertValueEqual(ResourceKinds.byId(kind.id()), Optional.<ResourceKind<?>>of(kind), "byId");
        }
        helper.assertValueEqual(ResourceKinds.ITEM.id(), MaxtechLib.id("item"), "item kind id");
        helper.assertValueEqual(ResourceKinds.FLUID.id(), MaxtechLib.id("fluid"), "fluid kind id");
        helper.assertValueEqual(ResourceKinds.ENERGY.id(), MaxtechLib.id("energy"), "energy kind id");
        helper.assertTrue(ResourceKinds.count() >= builtIn.size(), "kind count");
        helper.succeed();
    }
}
