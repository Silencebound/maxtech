package dev.silencebound.maxtech.lib.neoforge.gametest;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.resource.KeyCounts;
import dev.silencebound.maxtech.lib.resource.KeyPalette;
import dev.silencebound.maxtech.lib.resource.KeyRegistry;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import io.netty.buffer.Unpooled;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.LongArrayTag;
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
import net.neoforged.neoforge.transfer.item.ItemResource;

/** Stack keys with real components and registry access, which the JUnit tests of {@code lib.resource} cannot build. */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class ResourceKeyGameTests {
    private static final String NAME = "resource_keys_round_trip";
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        FUNCTIONS.register(NAME, () -> ResourceKeyGameTests::resourceKeysRoundTrip);
    }

    private ResourceKeyGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("resource_keys"));
        Identifier id = MaxtechLib.id(NAME);
        event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                new TestData<>(environment, LibGameTests.EMPTY_STRUCTURE, 20, 0, true)));
    }

    /**
     * Stack keys with an enchantment, a name and damage go through the palette codec with registry access and through
     * the network codec unchanged; interning keeps variants of one item together, and keys convert to and from
     * NeoForge's resources.
     */
    private static void resourceKeysRoundTrip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS),
                3);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Edge"));
        sword.setDamageValue(17);
        ItemKey enchanted = ItemKey.of(sword);
        ItemKey plain = ItemKey.of(Items.DIAMOND_SWORD);
        FluidKey water = FluidKey.of(Fluids.WATER);
        helper.assertTrue(enchanted.hasComponents() && !plain.hasComponents(), "component flags");
        helper.assertTrue(!enchanted.equals(plain), "a variant equals the plain key");
        helper.assertValueEqual(ItemKey.of(enchanted.toStack(5)), enchanted, "key of its own stack");

        KeyRegistry<StackKey> registry = KeyRegistry.stackKeys();
        KeyCounts<StackKey> cell = new KeyCounts<>(registry);
        cell.add(registry.intern(enchanted), 1);
        cell.add(registry.intern(plain), 40);
        cell.add(registry.intern(water), 144_000);
        helper.assertValueEqual(registry.variants(Items.DIAMOND_SWORD).size(), 2, "sword variants");
        helper.assertValueEqual(registry.variants(Fluids.WATER).size(), 1, "water variants");

        RegistryOps<Tag> ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Codec<KeyPalette<StackKey>> codec = KeyPalette.codec(StackKey.CODEC);
        Tag saved = codec.encodeStart(ops, cell.toPalette()).getOrThrow();
        helper.assertTrue(
                saved instanceof CompoundTag compound && compound.get("indices") instanceof IntArrayTag
                        && compound.get("counts") instanceof LongArrayTag,
                "the palette arrays are not NBT int and long arrays");
        KeyRegistry<StackKey> loadedRegistry = KeyRegistry.stackKeys();
        KeyCounts<StackKey> loaded = new KeyCounts<>(loadedRegistry);
        loaded.load(codec.parse(ops, saved).getOrThrow());
        helper.assertValueEqual(loaded.count(loadedRegistry.id(enchanted)), 1L, "enchanted swords after loading");
        helper.assertValueEqual(loaded.count(loadedRegistry.id(plain)), 40L, "plain swords after loading");
        helper.assertValueEqual(loaded.count(loadedRegistry.id(water)), 144_000L, "water after loading");
        helper.assertTrue(StackKey.CODEC.parse(ops, StackKey.CODEC.encodeStart(ops, plain).getOrThrow()).isSuccess(),
                "a plain key does not parse");

        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        StackKey.STREAM_CODEC.encode(buffer, enchanted);
        StackKey.STREAM_CODEC.encode(buffer, water);
        helper.assertValueEqual(StackKey.STREAM_CODEC.decode(buffer), (StackKey) enchanted, "streamed sword");
        helper.assertValueEqual(StackKey.STREAM_CODEC.decode(buffer), (StackKey) water, "streamed water");

        ItemResource resource = StackKeys.toResource(enchanted);
        helper.assertTrue(ItemResource.of(sword).equals(resource), "the key's resource differs from the stack's");
        helper.assertValueEqual(StackKeys.of(resource), enchanted, "key of the resource");
        helper.succeed();
    }
}
