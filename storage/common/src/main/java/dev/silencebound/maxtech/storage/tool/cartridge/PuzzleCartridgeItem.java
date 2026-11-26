package dev.silencebound.maxtech.storage.tool.cartridge;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleSessions;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleTexts;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleType;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleTypes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * A puzzle cartridge: using a blank one writes a random enabled puzzle onto it (one cartridge of a stack) and opens it;
 * using a written one opens the same puzzle again. Solving it uses the cartridge up and gives the player's research
 * team a share of a skip token's points ({@code tools.cartridge.creditPercent}, half by default), so it is worth less
 * than solving the puzzle of a research node, which completes the node. The research rules are untouched: the puzzle
 * runs as a session for the pseudo node {@link #NODE}, which research ignores because the tree has no such node.
 */
public final class PuzzleCartridgeItem extends Item {
    /** The node id cartridge sessions run under; not a research node. */
    public static final Identifier NODE = MaxtechStorage.id("puzzle_cartridge");
    public static final String DESCRIPTION_KEY = "tooltip.maxtech_storage.puzzle_cartridge";
    public static final String BLANK_KEY = "tooltip.maxtech_storage.puzzle_cartridge.blank";
    public static final String WRITTEN_KEY = "tooltip.maxtech_storage.puzzle_cartridge.written";
    public static final String RESEARCH_OFF_KEY = "message.maxtech_storage.puzzle_cartridge.research_off";
    public static final String NO_PUZZLE_KEY = "message.maxtech_storage.puzzle_cartridge.no_puzzle";
    public static final String SOLVED_KEY = "message.maxtech_storage.puzzle_cartridge.solved";

    /**
     * The seed of each player's open cartridge session. The solved listener is not told the seed, and a player may
     * carry several cartridges of one puzzle; server thread only, cleared when the server stops.
     */
    private static final Map<UUID, Long> ACTIVE = new HashMap<>();

    public PuzzleCartridgeItem(Properties properties) {
        super(properties);
    }

    /** A new puzzle for a blank cartridge: an enabled puzzle picked at random, at the configured difficulty. */
    public static @Nullable CartridgeData write(RandomSource random) {
        List<Identifier> enabled = new ArrayList<>();
        for (PuzzleType<?> type : PuzzleTypes.all()) {
            if (StorageServerConfig.puzzleEnabled(type.id())) {
                enabled.add(type.id());
            }
        }
        if (enabled.isEmpty()) {
            return null;
        }
        enabled.sort(Comparator.comparing(Identifier::toString));
        return new CartridgeData(enabled.get(random.nextInt(enabled.size())),
                PuzzleType.clampDifficulty(StorageServerConfig.CARTRIDGE_DIFFICULTY.getAsInt()), random.nextLong());
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        if (!Research.active()) {
            player.sendSystemMessage(Component.translatable(RESEARCH_OFF_KEY));
            return InteractionResult.SUCCESS;
        }
        ItemStack held = player.getItemInHand(hand);
        CartridgeData data = held.get(ModDataComponents.CARTRIDGE.get());
        if (data == null) {
            data = write(player.getRandom());
            if (data == null) {
                player.sendSystemMessage(Component.translatable(NO_PUZZLE_KEY));
                return InteractionResult.SUCCESS;
            }
            ItemStack written = held.split(1);
            written.set(ModDataComponents.CARTRIDGE.get(), data);
            if (held.isEmpty()) {
                player.setItemInHand(hand, written);
            } else if (!player.getInventory().add(written)) {
                player.drop(written, false);
            }
        }
        if (!StorageServerConfig.puzzleEnabled(data.puzzle())
                || PuzzleSessions.start(serverPlayer, NODE, data.puzzle(), data.seed(), data.difficulty()).isEmpty()) {
            player.sendSystemMessage(Component.translatable(NO_PUZZLE_KEY));
        } else {
            ACTIVE.put(player.getUUID(), data.seed());
        }
        return InteractionResult.SUCCESS;
    }

    /** Forgets a player's remembered session when they log out. */
    public static void forget(UUID player) {
        ACTIVE.remove(player);
    }

    /** Drops the remembered sessions; called when the server stops. */
    public static void clear() {
        ACTIVE.clear();
    }

    /**
     * A puzzle of {@link #NODE} was solved: uses up the player's matching cartridge and credits the team. Nothing
     * happens when no matching cartridge is left (the player dropped it meanwhile).
     */
    public static void solved(ServerPlayer player, Identifier node, Identifier puzzle, int difficulty) {
        if (!NODE.equals(node) || !Research.active()) {
            return;
        }
        Long active = ACTIVE.remove(player.getUUID());
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            CartridgeData data = stack.get(ModDataComponents.CARTRIDGE.get());
            if (stack.getItem() instanceof PuzzleCartridgeItem && data != null && data.puzzle().equals(puzzle)
                    && data.difficulty() == difficulty && active != null && data.seed() == active) {
                stack.shrink(1);
                long points = CartridgeCredit.points(Research.pointsPerToken(),
                        StorageServerConfig.CARTRIDGE_CREDIT_PERCENT.getAsInt());
                Research.addPoints(player.level().getServer(), Research.team(player, false), points);
                player.sendSystemMessage(Component.translatable(SOLVED_KEY, points));
                return;
            }
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable(DESCRIPTION_KEY).withStyle(ChatFormatting.GRAY));
        CartridgeData data = stack.get(ModDataComponents.CARTRIDGE.get());
        if (data == null) {
            builder.accept(Component.translatable(BLANK_KEY).withStyle(ChatFormatting.DARK_AQUA));
        } else {
            builder.accept(Component
                    .translatable(WRITTEN_KEY, Component.translatable(PuzzleTexts.title(data.puzzle())),
                            Component.translatable(PuzzleTexts.difficulty(data.difficulty())))
                    .withStyle(ChatFormatting.DARK_AQUA));
        }
    }
}
