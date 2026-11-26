package dev.silencebound.maxtech.storage.machine;

import dev.silencebound.maxtech.lib.api.recipe.ItemInput;
import dev.silencebound.maxtech.lib.api.recipe.ItemOutput;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.security.OwnerSecurity;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternContent;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternDraft;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.part.CablePart;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * The export button of Storage's processing machines (M7 C20): writes the machine's recipe, the one it has selected by
 * its slots or the last it ran, as a processing pattern onto a blank pattern from the player's inventory. The pattern
 * goes through {@link PatternDraft#toPattern} and {@link PatternItem#encode}, the pattern terminal's own route, so a
 * recipe exported here and the same recipe sent from a recipe viewer give the identical pattern: tag inputs keep their
 * tag, amounts and the main output first, then byproducts, the recipe type and id, and the faces of
 * {@code PatternFaces} (any face for Storage's machines). Two differences make it more exact than a viewer transfer: a
 * catalyst (an input the recipe does not consume, the lithograph's mask) is marked returned, and a chance output keeps
 * its chance.
 *
 * <p>
 * Access: when the machine touches a storage network (an interface or port block, or a part on a cable, on any side),
 * the player needs the craft access level on every network it touches; a machine on no network needs the owner's
 * permission, as for any Storage device. Server thread.
 */
public final class MachineExport {
    /** What an export did. */
    public enum Result {
        EXPORTED, NO_RECIPE, NO_BLANK, DENIED
    }

    private MachineExport() {
    }

    /**
     * Writes {@code machine}'s recipe onto one blank pattern from {@code player}'s inventory and gives the encoded
     * pattern to the player (dropped when the inventory is full). Takes exactly one blank on success, nothing
     * otherwise.
     */
    public static Result export(ServerPlayer player, ProcessingMachineBlockEntity machine) {
        if (!allowed(player, machine)) {
            return Result.DENIED;
        }
        RecipeHolder<StorageMachineRecipe> holder = machine.exportRecipe();
        CraftingPattern pattern = holder == null ? null : pattern(holder);
        if (pattern == null) {
            return Result.NO_RECIPE;
        }
        Inventory inventory = player.getInventory();
        int blank = -1;
        for (int i = 0; i < inventory.getContainerSize() && blank < 0; i++) {
            if (inventory.getItem(i).is(PatternContent.BLANK_PATTERN.get())) {
                blank = i;
            }
        }
        if (blank < 0) {
            return Result.NO_BLANK;
        }
        inventory.removeItem(blank, 1);
        ItemStack encoded = PatternItem.encode(pattern);
        if (!inventory.add(encoded)) {
            player.drop(encoded, false);
        }
        return Result.EXPORTED;
    }

    /** The processing pattern of {@code holder}'s recipe; null when it has no inputs or outputs the draft can hold. */
    public static @Nullable CraftingPattern pattern(RecipeHolder<StorageMachineRecipe> holder) {
        StorageMachineRecipe recipe = holder.value();
        List<Optional<PatternDraft.Input>> inputs = new ArrayList<>();
        for (ItemInput input : recipe.inputs()) {
            PatternDraft.Input converted = input(input);
            if (converted == null) {
                // An ingredient that matches no item (an empty tag) cannot be written as a pattern.
                return null;
            }
            inputs.add(Optional.of(converted));
        }
        List<Optional<PatternDraft.Output>> outputs = new ArrayList<>();
        for (ItemOutput output : recipe.outputs()) {
            ItemStack stack = output.item().create();
            outputs.add(Optional.of(new PatternDraft.Output(ItemKey.of(stack.copyWithCount(1)), stack.getCount(),
                    output.guaranteed() ? 1f : (float) output.chance() / ItemOutput.ALWAYS)));
        }
        if (inputs.size() > PatternDraft.INPUTS || outputs.size() > PatternDraft.OUTPUTS) {
            return null;
        }
        Identifier type = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        return new PatternDraft(PatternDraft.Mode.PROCESSING, inputs, outputs, Optional.ofNullable(type),
                Optional.of(holder.id().identifier())).toPattern().orElse(null);
    }

    /** One recipe input as a draft input: the tag it came from, else its first item; a catalyst comes back. */
    private static PatternDraft.@Nullable Input input(ItemInput input) {
        Optional<Holder<Item>> holder = input.ingredient().items().findFirst();
        if (holder.isEmpty()) {
            return null;
        }
        Item first = holder.get().value();
        Optional<PatternIngredient> tag = Optional.empty();
        if (input.ingredient().display() instanceof SlotDisplay.TagSlotDisplay(TagKey<Item> key)) {
            tag = Optional.of(new PatternIngredient.ItemTag(key));
        }
        return new PatternDraft.Input(ItemKey.of(first), input.count(), tag, false, Optional.empty(),
                !input.consumed());
    }

    /** Whether {@code player} may export from {@code machine}; see the class comment. Server thread. */
    public static boolean allowed(Player player, StorageMachineBlockEntity machine) {
        List<NetworkState> networks = networks(machine);
        if (networks.isEmpty()) {
            return OwnerSecurity.canAccess(player, machine.ownerRecord());
        }
        for (NetworkState network : networks) {
            if (!network.accessLevel(player).atLeast(AccessLevel.CRAFT)) {
                return false;
            }
        }
        return true;
    }

    /** The networks of the devices touching {@code machine}: network devices on its sides and parts facing it. */
    static List<NetworkState> networks(StorageMachineBlockEntity machine) {
        List<NetworkState> found = new ArrayList<>(2);
        Level level = machine.getLevel();
        if (level == null) {
            return found;
        }
        BlockPos pos = machine.getBlockPos();
        for (Direction side : Direction.values()) {
            BlockPos at = pos.relative(side);
            if (!level.isLoaded(at)) {
                continue;
            }
            BlockEntity entity = level.getBlockEntity(at);
            if (entity instanceof NetworkDevice device) {
                add(found, device);
            } else if (entity instanceof ConduitBlockEntity conduit
                    && conduit.conduit().state() instanceof CableParts parts) {
                CablePart part = parts.part(side.getOpposite());
                if (part != null) {
                    add(found, part);
                }
            }
        }
        return found;
    }

    @SuppressWarnings("ReferenceEquality") // networks are compared by identity
    private static void add(List<NetworkState> found, NetworkDevice device) {
        NetworkState network = device.deviceLink().network();
        if (network != null) {
            for (NetworkState known : found) {
                if (known == network) {
                    return;
                }
            }
            found.add(network);
        }
    }
}
