// port-override: base=1684c270d4a8b994
package dev.silencebound.maxtech.lib.neoforge.datagen;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.facade.FacadeContent;
import dev.silencebound.maxtech.lib.neoforge.client.FacadeItemModel;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.client.model.generators.CustomLoaderBuilder;
import net.neoforged.neoforge.client.model.generators.ItemModelBuilder;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

/**
 * The facade's custom item model, Lib's only item: {@code maxtech_lib:item/facade} naming the
 * {@link FacadeItemModel} loader (26.1 names the item model type in an item model definition instead). GameTest
 * entries in Lib's namespace never ship and have none.
 */
final class LibModelProvider extends ItemModelProvider {
    LibModelProvider(PackOutput output) {
        // Validation off, as 26.1's model provider has none.
        super(output, MaxtechLib.MOD_ID, new ExistingFileHelper(List.of(), Set.of(), false, null, null));
    }

    @Override
    protected void registerModels() {
        getBuilder(BuiltInRegistries.ITEM.getKey(FacadeContent.FACADE.get()).withPrefix("item/").toString())
                .customLoader(FacadeLoader::new);
    }

    private static final class FacadeLoader extends CustomLoaderBuilder<ItemModelBuilder> {
        FacadeLoader(ItemModelBuilder parent, ExistingFileHelper files) {
            super(FacadeItemModel.Unbaked.ID, parent, files, false);
        }
    }
}
