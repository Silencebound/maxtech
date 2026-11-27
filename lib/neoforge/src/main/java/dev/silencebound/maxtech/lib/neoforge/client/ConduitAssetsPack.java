package dev.silencebound.maxtech.lib.neoforge.client;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.content.ConduitAssets;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.flag.FeatureFlagSet;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import org.jspecify.annotations.Nullable;

/**
 * A required, hidden client pack holding {@link ConduitAssets}: the block state files and item model definitions of
 * addons' conduits that set a look. It sits at the bottom, below the mods' own resources (NeoForge adds those at the
 * top), so a file an addon ships itself wins.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID, value = Dist.CLIENT)
final class ConduitAssetsPack {
    private static final String ID = "maxtech_lib:conduit_assets";

    private ConduitAssetsPack() {
    }

    @SubscribeEvent
    static void addPack(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }
        Map<Identifier, byte[]> files = ConduitAssets.files();
        if (files.isEmpty()) {
            return;
        }
        Component title = Component.literal("MaxTech Lib: conduit resources");
        PackLocationInfo location = new PackLocationInfo(ID, title, PackSource.BUILT_IN, Optional.empty());
        Pack.ResourcesSupplier resources = new Pack.ResourcesSupplier() {
            @Override
            public PackResources openPrimary(PackLocationInfo info) {
                return new Resources(info, files);
            }

            @Override
            public PackResources openFull(PackLocationInfo info, Pack.Metadata metadata) {
                return new Resources(info, files);
            }
        };
        Pack.Metadata metadata = new Pack.Metadata(title, PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of(),
                true);
        Pack pack = new Pack(location, resources, metadata, new PackSelectionConfig(true, Pack.Position.BOTTOM, false));
        event.addRepositorySource(consumer -> consumer.accept(pack));
    }

    /** Serves {@code files} as client resources; there is nothing at the pack root. */
    static final class Resources extends AbstractPackResources {
        private final Map<Identifier, byte[]> files;
        private final Set<String> namespaces = new HashSet<>();

        Resources(PackLocationInfo location, Map<Identifier, byte[]> files) {
            super(location);
            this.files = files;
            for (Identifier id : files.keySet()) {
                namespaces.add(id.getNamespace());
            }
        }

        @Override
        public @Nullable IoSupplier<InputStream> getRootResource(String... path) {
            return null;
        }

        @Override
        public @Nullable IoSupplier<InputStream> getResource(PackType type, Identifier location) {
            byte[] bytes = type == PackType.CLIENT_RESOURCES ? files.get(location) : null;
            return bytes == null ? null : () -> new ByteArrayInputStream(bytes);
        }

        @Override
        public void listResources(PackType type, String namespace, String directory, ResourceOutput output) {
            if (type != PackType.CLIENT_RESOURCES) {
                return;
            }
            String prefix = directory + "/";
            for (Map.Entry<Identifier, byte[]> file : files.entrySet()) {
                Identifier id = file.getKey();
                byte[] bytes = file.getValue();
                if (id.getNamespace().equals(namespace) && id.getPath().startsWith(prefix)) {
                    output.accept(id, () -> new ByteArrayInputStream(bytes));
                }
            }
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            return type == PackType.CLIENT_RESOURCES ? namespaces : Set.of();
        }

        @Override
        public void close() {
        }
    }
}
