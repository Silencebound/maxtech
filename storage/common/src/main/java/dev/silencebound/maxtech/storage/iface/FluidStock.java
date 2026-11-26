package dev.silencebound.maxtech.storage.iface;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.save.KeptValue;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
// PLATFORM-LEAK: the interface stocks NeoForge fluid resources; its item keeps them as such.
import net.neoforged.neoforge.transfer.fluid.FluidResource;

/**
 * One fluid stock tank's contents, as an interface's item keeps them ({@code maxtech_storage:interface_fluids}): the
 * fluid and its amount in mB, or {@link #EMPTY}.
 */
public record FluidStock(FluidResource fluid, int amount) {
    public static final FluidStock EMPTY = new FluidStock(FluidResource.EMPTY, 0);
    public static final Codec<FluidStock> CODEC = RecordCodecBuilder
            .create(i -> i.group(FluidResource.OPTIONAL_CODEC.fieldOf("fluid").forGetter(FluidStock::fluid),
                    Codec.INT.fieldOf("amount").forGetter(FluidStock::amount)).apply(i, FluidStock::new));
    /** The tanks as an item keeps them: a tank whose fluid no longer loads (a removed mod) is kept as saved. */
    public static final Codec<List<KeptValue<FluidStock>>> LIST_CODEC = KeptValue.codec(CODEC).listOf();

    private static final String FLUID = "fluid";
    private static final String AMOUNT = "amount";

    /**
     * A kept tank's data as a placed interface saves it (NeoForge's {@code FluidStack}: id, amount, components) in this
     * record's form (fluid: id and components; amount), so it can go onto the item; data of another shape unchanged.
     */
    static Tag fromStackTag(Tag stack) {
        if (!(stack instanceof CompoundTag compound) || compound.get(AMOUNT) == null) {
            return stack.copy();
        }
        CompoundTag fluid = new CompoundTag();
        Tag amount = null;
        for (String key : compound.keySet()) {
            Tag value = compound.get(key);
            if (value == null) {
                continue;
            }
            if (key.equals(AMOUNT)) {
                amount = value.copy();
            } else {
                fluid.put(key, value.copy());
            }
        }
        CompoundTag tank = new CompoundTag();
        tank.put(FLUID, fluid);
        if (amount != null) {
            tank.put(AMOUNT, amount);
        }
        return tank;
    }

    /** The reverse of {@link #fromStackTag}: a kept tank from the item, in the form a placed interface saves. */
    static Tag toStackTag(Tag tank) {
        if (!(tank instanceof CompoundTag compound) || !(compound.get(FLUID) instanceof CompoundTag fluid)
                || compound.get(AMOUNT) == null) {
            return tank.copy();
        }
        CompoundTag stack = fluid.copy();
        Tag amount = compound.get(AMOUNT);
        if (amount != null) {
            stack.put(AMOUNT, amount.copy());
        }
        return stack;
    }

    /** Whether the tank holds nothing. */
    public boolean isEmpty() {
        return fluid.isEmpty() || amount <= 0;
    }
}
