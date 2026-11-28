package dev.silencebound.maxtech.farms.neoforge.gametest;

import dev.silencebound.maxtech.farms.MaxtechFarms;
import dev.silencebound.maxtech.farms.lab.LabTubes;
import dev.silencebound.maxtech.farms.registry.FarmsContent;
import dev.silencebound.maxtech.farms.registry.FarmsCreativeTab;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

/** Farms' own creative tab lists its items in order, and the vanilla tabs no longer list any of them. */
@EventBusSubscriber(modid = MaxtechFarms.MOD_ID)
public final class CreativeTabGameTests {
    private static final Identifier STRUCTURE = MaxtechFarms.id("empty_7x2x3");
    private static final String NAME = "creative_tab_lists_farms";

    private CreativeTabGameTests() {
    }

    /** The test function, registered on the mod bus; the GameTest source set only exists in dev runs. */
    @SubscribeEvent
    static void registerFunctions(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, MaxtechFarms.id(NAME), () -> CreativeTabGameTests::listsFarms);
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechFarms.id("creative_tab"));
        event.registerTest(MaxtechFarms.id(NAME),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechFarms.id(NAME)),
                        new TestData<>(environment, STRUCTURE, 20, 0, true)));
    }

    private static void listsFarms(GameTestHelper helper) {
        List<Item> farms = items(helper, FarmsCreativeTab.FARMS.get());
        List<Item> expected = List.of(FarmsContent.LAB_HUB_ITEM.get(), FarmsContent.FLUID_PORT_ITEM.get(),
                LabTubes.TUBE.item().get(), FarmsContent.SAMPLER.get());
        helper.assertValueEqual(farms, expected, "Farms tab");
        for (ResourceKey<CreativeModeTab> key : List.of(CreativeModeTabs.FUNCTIONAL_BLOCKS,
                CreativeModeTabs.TOOLS_AND_UTILITIES)) {
            List<Item> vanilla = items(helper, BuiltInRegistries.CREATIVE_MODE_TAB.getValueOrThrow(key));
            for (Item item : expected) {
                helper.assertFalse(vanilla.contains(item), item + " in " + key.identifier());
            }
        }
        helper.succeed();
    }

    private static List<Item> items(GameTestHelper helper, CreativeModeTab tab) {
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(helper.getLevel().enabledFeatures(), false,
                helper.getLevel().registryAccess()));
        List<Item> items = new ArrayList<>();
        for (ItemStack stack : tab.getDisplayItems()) {
            items.add(stack.getItem());
        }
        return items;
    }
}
