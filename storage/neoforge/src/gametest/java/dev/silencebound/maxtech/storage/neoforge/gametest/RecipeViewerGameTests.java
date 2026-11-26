package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.sync.ServerSyncedList;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import dev.silencebound.maxtech.storage.neoforge.compat.viewer.StorageRecipeViews;
import dev.silencebound.maxtech.storage.neoforge.compat.viewer.TerminalShortfall;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Every recipe type Storage registers has a recipe viewer category, read from the registry so a new type without one
 * fails here, and every category has recipes and a title.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class RecipeViewerGameTests {
    static {
        StorageTests.function("recipe_types_have_viewer_categories", RecipeViewerGameTests::covered);
        StorageTests.function("recipe_terminal_shortfall", RecipeViewerGameTests::shortfall);
    }

    private RecipeViewerGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        var environment = event.registerEnvironment(MaxtechStorage.id("recipe_viewer"));
        StorageTests.test(event, environment, "recipe_types_have_viewer_categories", StorageTests.SMALL, 20);
        StorageTests.test(event, environment, "recipe_terminal_shortfall", StorageTests.SMALL, 20);
    }

    private static void covered(GameTestHelper helper) {
        Set<RecipeType<?>> shown = new HashSet<>();
        for (StorageRecipeViews.Category category : StorageRecipeViews.categories()) {
            shown.add(category.recipeType());
            String title = Component.translatable(category.titleKey()).getString();
            helper.assertFalse(title.equals(category.titleKey()), "no title for " + category.id());
            boolean any = helper.getLevel().recipeAccess().getRecipes().stream()
                    .anyMatch(holder -> holder.value().getType().equals(category.recipeType()));
            helper.assertTrue(any, "no recipes of " + category.id());
        }
        for (RecipeType<?> type : BuiltInRegistries.RECIPE_TYPE) {
            Identifier id = BuiltInRegistries.RECIPE_TYPE.getKey(type);
            if (id != null && id.getNamespace().equals(MaxtechStorage.MOD_ID)) {
                helper.assertTrue(shown.contains(type), "recipe type " + id + " has no recipe viewer category");
            }
        }
        Set<Identifier> shownIds = new HashSet<>();
        for (RecipeType<?> type : shown) {
            Identifier id = BuiltInRegistries.RECIPE_TYPE.getKey(type);
            if (id != null) {
                shownIds.add(id);
            }
        }
        helper.assertTrue(StorageTests.uncoveredSerializers(shownIds).isEmpty(),
                "recipe serializers without a viewer category or an entry in StorageTests.SERIALIZERS_WITHOUT_OWN_TYPE: "
                        + StorageTests.uncoveredSerializers(shownIds));
        helper.succeed();
    }

    /** The "+" button's missing-input logic: stored, carried, missing but craftable, missing, and no candidates. */
    private static void shortfall(GameTestHelper helper) {
        StackKey cobble = ItemKey.of(Items.COBBLESTONE);
        StackKey stick = ItemKey.of(Items.STICK);
        StackKey diamond = ItemKey.of(Items.DIAMOND);
        StackKey gold = ItemKey.of(Items.GOLD_INGOT);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getInventory().add(new ItemStack(Items.STICK, 2));
        RegistryFriendlyByteBuf open = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess());
        open.writeBlockPos(BlockPos.ZERO);
        // No view filter slots, as for a wireless terminal.
        open.writeBoolean(false);
        TerminalMenu menu = TerminalMenu.fromNetwork(1, player.getInventory(), open);
        StackKey[] keys = {cobble, diamond};
        Int2LongOpenHashMap stored = new Int2LongOpenHashMap();
        stored.put(0, 64);
        new ServerSyncedList<>(TerminalContent.LIST, 1, id -> keys[id], menu.clientList()::apply).open(stored, 0);
        Int2LongOpenHashMap craftable = new Int2LongOpenHashMap();
        craftable.put(1, 1);
        new ServerSyncedList<>(RequestContent.CRAFTABLES, 1, id -> keys[id], menu.craftingData().craftables()::apply)
                .open(craftable, 0);

        TerminalShortfall.Result result = new TerminalShortfall().compute(menu, player,
                List.of(new TerminalShortfall.Input(List.of(cobble), 4), new TerminalShortfall.Input(List.of(stick), 2),
                        new TerminalShortfall.Input(List.of(diamond), 1), new TerminalShortfall.Input(List.of(gold), 1),
                        new TerminalShortfall.Input(List.of(), 1)));
        helper.assertValueEqual(result.missing(), List.of(2, 3), "only the diamond and the gold are missing");
        helper.assertTrue(diamond.equals(result.craftable()), "the craftable missing input");
        helper.assertValueEqual(result.amount(), 1L, "its amount");
        helper.assertValueEqual(result.craftables().size(), 1, "the gold is not craftable");
        helper.succeed();
    }
}
