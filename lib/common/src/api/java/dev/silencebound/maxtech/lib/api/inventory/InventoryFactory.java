package dev.silencebound.maxtech.lib.api.inventory;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;
// PLATFORM-LEAK: the machine's item handler is a NeoForge one (ADR-0002).
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * Where {@link ItemSlots.Builder#build} and the tank factories get Lib's implementations. Lib installs its factory once
 * during its construction, before any mod's content exists; a second install throws {@link IllegalStateException}, and
 * so does building an inventory before the first.
 */
@ApiStatus.Internal
public final class InventoryFactory {
    private static volatile @Nullable Factory factory;

    private InventoryFactory() {
    }

    /** Sets Lib's factory; only the first call counts, a second throws {@link IllegalStateException}. */
    @ApiStatus.Internal
    public static synchronized void install(Factory source) {
        if (factory != null) {
            throw new IllegalStateException("An inventory factory is already installed");
        }
        factory = source;
    }

    static Factory get() {
        Factory source = factory;
        if (source == null) {
            throw new IllegalStateException("MaxTech Lib has not set up inventories yet");
        }
        return source;
    }

    /** Lib's side of the inventory api. */
    @ApiStatus.Internal
    public interface Factory {
        /**
         * Slots with one role, filter (null: any item) and size limit per slot; the arrays are the factory's to keep.
         */
        ItemSlots items(SlotRole[] roles, @Nullable Predicate<ItemStack>[] filters, int[] limits, IntConsumer onChange);

        /** A tank of {@code kind}; see {@link ResourceTank#create}. */
        <K> ResourceTank<K> tank(ResourceKind<K> kind, long capacity, Predicate<? super K> filter, Runnable onChange);

        /** A fluid tank; see {@link FluidTank#create}. */
        FluidTank fluidTank(long capacity, Predicate<? super FluidKey> filter, Runnable onChange);

        /** The machine's handler over Lib's {@code slots}; see {@link MachineAccess#items}. */
        ResourceHandler<ItemResource> machineItems(ItemSlots slots);

        /** The machine's view of Lib's {@code tank}; see {@link MachineAccess#tank}. */
        <K> MachineAccess.Tank<K> machineTank(ResourceTank<K> tank);
    }
}
