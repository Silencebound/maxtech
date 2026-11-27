package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.conduit.ConduitEntry;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.Conduits;
import dev.silencebound.maxtech.lib.api.network.Network;
import dev.silencebound.maxtech.lib.api.network.NetworkLogic;
import dev.silencebound.maxtech.lib.api.network.NetworkType;
import dev.silencebound.maxtech.lib.api.registry.Registrar;
import dev.silencebound.maxtech.lib.api.registry.Registrars;
import dev.silencebound.maxtech.lib.api.registry.RegistryEntry;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jspecify.annotations.Nullable;

/**
 * PROTOTYPE (proto/tube-fx branch only, never merged): lab tubes with fluid contents and ten candidate effects, so the
 * owner can judge them in a client. Two tube blocks on Lib's shell look (integrated junctions, and moulded fittings 1
 * px wider at junctions), two stand-in third-party fluids and NeoForge's milk. What a tube shows is packed into its
 * render value ({@link Value}); {@link TubeFxShowcase} builds the stations and {@code TubeFxClient} draws them.
 */
@EventBusSubscriber(modid = ConduitApiGameTests.MOD_ID)
public final class TubeFx {
    static final String MOD_ID = ConduitApiGameTests.MOD_ID;
    static final Identifier NETWORK_ID = Identifier.fromNamespaceAndPath(MOD_ID, "test_fx_network");
    /** The prototype's tech glass, hand-placed by the owner. */
    static final Identifier GLASS = Identifier.fromNamespaceAndPath(MOD_ID, "block/proto_fx/glass");
    static final float THICKNESS = 0.375F;

    static {
        // NeoForge registers its milk fluid only when a mod asks during construction.
        NeoForgeMod.enableMilkFluid();
    }

    static final NetworkType NETWORK = NetworkType.register(NETWORK_ID,
            level -> Conduits.connectivity(level, NETWORK_ID), level -> new NetworkLogic() {
                @Override
                public void tick(Network network) {
                }
            });

    private static final ConduitLook.Shell LOOK = ConduitLook.shell(GLASS).withBorder(1.0F);

    /** Lib's shell geometry as it is: the junction is the tube's own glass. */
    static final ConduitEntry<ConduitType> TUBE = Conduits
            .builder(MOD_ID, "test_fx_tube", item -> new ConduitType(NETWORK_ID, "fx", 0x6E8A94, THICKNESS, item))
            .look(LOOK).customModel(true).creativeTab(CreativeModeTabs.REDSTONE_BLOCKS).register();

    /** The same tube with a moulded fitting 1 px wider around each junction and bend. */
    static final ConduitEntry<ConduitType> FITTED_TUBE = Conduits
            .builder(MOD_ID, "test_fx_tube_fitted",
                    item -> new ConduitType(NETWORK_ID, "fx_fitted", 0x6E8A94, THICKNESS, item))
            .look(LOOK).customModel(true).creativeTab(CreativeModeTabs.REDSTONE_BLOCKS).register();

    private static final Registrar<FluidType> FLUID_TYPES = Registrars.create(MOD_ID,
            NeoForgeRegistries.Keys.FLUID_TYPES);
    private static final Registrar<Fluid> FLUIDS = Registrars.create(MOD_ID, Registries.FLUID);

    static final TestFluid GEL = new TestFluid("test_nutrient_gel", 1300, 3000);
    static final TestFluid CRYO = new TestFluid("test_cryo_fluid", 900, 800);

    private TubeFx() {
    }

    /** A dev-only fluid standing in for a third-party one: a type, a source and a flowing fluid, no block or bucket. */
    static final class TestFluid {
        final RegistryEntry<FluidType, FluidType> type;
        final RegistryEntry<Fluid, BaseFlowingFluid.Source> source;
        final RegistryEntry<Fluid, BaseFlowingFluid.Flowing> flowing;

        TestFluid(String name, int density, int viscosity) {
            type = FLUID_TYPES.register(name,
                    () -> new FluidType(FluidType.Properties.create().density(density).viscosity(viscosity)));
            source = FLUIDS.register(name, () -> new BaseFlowingFluid.Source(properties()));
            flowing = FLUIDS.register("flowing_" + name, () -> new BaseFlowingFluid.Flowing(properties()));
        }

        private BaseFlowingFluid.Properties properties() {
            return new BaseFlowingFluid.Properties(type, source, flowing);
        }
    }

    /** The candidate effects; {@link #id} is what a tube's render value holds. */
    enum Effect {
        BUBBLES(1, "bubbles"), CAUSTICS(2, "caustics"), MENISCUS(3, "meniscus"), MOTES(4, "motes"), PULSE(5,
                "pressure pulse"), GAUGE(6, "fill gauge"), GLOW(7, "activity glow"), FLUID_OVERLAY(8,
                        "per-fluid overlay"), CONDENSATION(9, "condense / haze"), NONE(10, "none");

        final int id;
        final String label;

        Effect(int id, String label) {
            this.id = id;
            this.label = label;
        }

        static @Nullable Effect byId(int id) {
            for (Effect effect : values()) {
                if (effect.id == id) {
                    return effect;
                }
            }
            return null;
        }
    }

    /**
     * What one tube shows, packed into its render value: bits 0-7 the fluid's numeric registry id (0, the empty fluid,
     * draws no contents), 8-10 the fill 0 to 4, 11-13 the upstream side (0 none, else the direction's 3D value + 1), 14
     * flowing (else idle), 15-18 and 19-22 up to two effect ids. Numeric ids are not stable across sessions; the
     * showcase sets them again whenever it builds.
     */
    record Value(int fluidId, int fill, @Nullable Direction upstream, boolean flowing, int effectA, int effectB) {
        int encode() {
            int up = upstream == null ? 0 : upstream.get3DDataValue() + 1;
            return (fluidId & 0xFF) | (fill & 7) << 8 | up << 11 | (flowing ? 1 << 14 : 0) | (effectA & 0xF) << 15
                    | (effectB & 0xF) << 19;
        }

        static Value decode(int value) {
            int up = value >>> 11 & 7;
            return new Value(value & 0xFF, Math.min(4, value >>> 8 & 7),
                    up == 0 || up > 6 ? null : Direction.from3DDataValue(up - 1), (value & 1 << 14) != 0,
                    value >>> 15 & 0xF, value >>> 19 & 0xF);
        }

        boolean has(Effect effect) {
            return effectA == effect.id || effectB == effect.id;
        }
    }
}
