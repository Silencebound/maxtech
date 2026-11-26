package dev.silencebound.maxtech.lib.neoforge.platform;

import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

final class NeoForgeRegistryHelper implements RegistryHelper {
    @Override
    public <R> Registrar<R> registrar(String modId, ResourceKey<? extends Registry<R>> registry) {
        DeferredRegister<R> register = DeferredRegister.create(registry, modId);
        register.register(ModContainers.eventBus(modId));
        return new Registrar<>() {
            @Override
            public <T extends R> RegistryEntry<R, T> register(String name, Supplier<? extends T> factory) {
                return new Entry<>(register.register(name, factory));
            }

            @Override
            public <T extends R> RegistryEntry<R, T> register(String name, Function<Identifier, ? extends T> factory) {
                return new Entry<>(register.register(name, factory));
            }
        };
    }

    private record Entry<R, T extends R>(DeferredHolder<R, T> holder) implements RegistryEntry<R, T> {
        @Override
        public T get() {
            return holder.get();
        }

        @Override
        public Identifier id() {
            return holder.getId();
        }

        @Override
        public ResourceKey<R> key() {
            return holder.getKey();
        }
    }
}
