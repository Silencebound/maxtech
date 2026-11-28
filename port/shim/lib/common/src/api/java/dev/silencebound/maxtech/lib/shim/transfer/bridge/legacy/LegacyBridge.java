package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import dev.silencebound.maxtech.lib.shim.transfer.ResourceHandler;
import dev.silencebound.maxtech.lib.shim.transfer.access.ItemAccess;
import dev.silencebound.maxtech.lib.shim.transfer.bridge.Capabilities;
import dev.silencebound.maxtech.lib.shim.transfer.energy.EnergyHandler;
import dev.silencebound.maxtech.lib.shim.transfer.fluid.FluidResource;
import dev.silencebound.maxtech.lib.shim.transfer.item.ItemResource;
import dev.silencebound.maxtech.lib.shim.transfer.port.EnergyHandlerAdapter;
import dev.silencebound.maxtech.lib.shim.transfer.port.FluidResourceHandlerAdapter;
import dev.silencebound.maxtech.lib.shim.transfer.port.FluidResourceHandlerItemAdapter;
import dev.silencebound.maxtech.lib.shim.transfer.port.ItemResourceHandlerAdapter;
import java.util.function.Function;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import net.neoforged.neoforge.items.IItemHandler;
import org.jspecify.annotations.Nullable;

/**
 * Connects the 26.1-style capabilities in {@link Capabilities} with NeoForge 21.1's {@code IItemHandler},
 * {@code IFluidHandler} and {@code IEnergyStorage} capabilities, after every other mod has registered its providers:
 *
 * <ul>
 * <li>a block or item with a 26.1-style provider (Lib's and its content mods') also gets the 21.1 capability, through
 * NeoForge 26.1's own adapters, so other 1.21.1 mods see it;
 * <li>a block or item with only a 21.1 provider (vanilla and other mods) gets the 26.1-style capability through the
 * {@code Legacy*} wrappers, so Lib reaches it.
 * </ul>
 *
 * <p>A block never gets both directions for the same pair, so lookups cannot loop. Wrappers are unwrapped when they
 * meet their own adapter. Entity capabilities and {@code Item.ITEM}/{@code Energy.ITEM} are not bridged; nothing in
 * Lib uses them yet.
 */
@EventBusSubscriber(modid = "maxtech_lib", bus = EventBusSubscriber.Bus.MOD)
public final class LegacyBridge {
    private LegacyBridge() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void register(RegisterCapabilitiesEvent event) {
        BlockCapability<IItemHandler, @Nullable Direction> legacyItems = net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK;
        BlockCapability<IFluidHandler, @Nullable Direction> legacyFluids = net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK;
        BlockCapability<IEnergyStorage, @Nullable Direction> legacyEnergy = net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK;
        for (Block block : BuiltInRegistries.BLOCK) {
            bridge(event, block, Capabilities.Item.BLOCK, legacyItems, LegacyBridge::toLegacy, LegacyBridge::fromLegacy);
            bridge(event, block, Capabilities.Fluid.BLOCK, legacyFluids, LegacyBridge::toLegacyFluids,
                    LegacyBridge::fromLegacyFluids);
            bridge(event, block, Capabilities.Energy.BLOCK, legacyEnergy, LegacyBridge::toLegacyEnergy,
                    LegacyBridge::fromLegacyEnergy);
        }
        for (Item item : BuiltInRegistries.ITEM) {
            bridgeFluidItem(event, item);
        }
    }

    private static <N, L> void bridge(RegisterCapabilitiesEvent event, Block block,
            BlockCapability<N, @Nullable Direction> ours, BlockCapability<L, @Nullable Direction> legacy,
            Function<N, @Nullable L> toLegacy, Function<L, @Nullable N> fromLegacy) {
        boolean hasOurs = event.isBlockRegistered(ours, block);
        boolean hasLegacy = event.isBlockRegistered(legacy, block);
        if (hasOurs) {
            event.registerBlock(legacy, (level, pos, state, blockEntity, side) -> {
                N handler = level.getCapability(ours, pos, state, blockEntity, side);
                return handler == null ? null : toLegacy.apply(handler);
            }, block);
        } else if (hasLegacy) {
            event.registerBlock(ours, (level, pos, state, blockEntity, side) -> {
                L handler = level.getCapability(legacy, pos, state, blockEntity, side);
                return handler == null ? null : fromLegacy.apply(handler);
            }, block);
        }
    }

    private static void bridgeFluidItem(RegisterCapabilitiesEvent event, Item item) {
        var legacy = net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.ITEM;
        if (event.isItemRegistered(Capabilities.Fluid.ITEM, item)) {
            event.registerItem(legacy, (stack, unused) -> {
                ItemAccess access = ItemAccess.forStack(stack);
                ResourceHandler<FluidResource> handler = stack.getCapability(Capabilities.Fluid.ITEM, access);
                return handler == null ? null : new FluidResourceHandlerItemAdapter(handler, access);
            }, item);
        } else if (event.isItemRegistered(legacy, item)) {
            event.registerItem(Capabilities.Fluid.ITEM, (stack, access) -> {
                IFluidHandlerItem handler = stack.getCapability(legacy);
                return handler == null ? null : new LegacyFluidItemHandler(access);
            }, item);
        }
    }

    private static IItemHandler toLegacy(ResourceHandler<ItemResource> handler) {
        return handler instanceof LegacyItemHandler wrapper ? wrapper.handler()
                : new ItemResourceHandlerAdapter(handler);
    }

    private static ResourceHandler<ItemResource> fromLegacy(IItemHandler handler) {
        return handler instanceof ItemResourceHandlerAdapter adapter ? adapter.handler()
                : new LegacyItemHandler(handler);
    }

    private static IFluidHandler toLegacyFluids(ResourceHandler<FluidResource> handler) {
        return handler instanceof LegacyFluidHandler wrapper ? wrapper.handler()
                : new FluidResourceHandlerAdapter(handler);
    }

    private static ResourceHandler<FluidResource> fromLegacyFluids(IFluidHandler handler) {
        return handler instanceof FluidResourceHandlerAdapter adapter ? adapter.handler()
                : new LegacyFluidHandler(handler);
    }

    private static IEnergyStorage toLegacyEnergy(EnergyHandler handler) {
        return handler instanceof LegacyEnergyHandler wrapper ? wrapper.storage() : new EnergyHandlerAdapter(handler);
    }

    private static EnergyHandler fromLegacyEnergy(IEnergyStorage storage) {
        return storage instanceof EnergyHandlerAdapter adapter ? adapter.handler() : new LegacyEnergyHandler(storage);
    }
}
