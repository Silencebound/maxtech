package dev.silencebound.maxtech.lib.neoforge.api.client.conduit;

import dev.silencebound.maxtech.lib.api.conduit.ConduitEntry;
import java.util.function.BiConsumer;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Registers the {@link ConduitModelHook} of each conduit marked {@code customModel} on its builder. Fired once on the
 * client's mod bus while the client starts, after registration and before the first model bake (also in client
 * datagen). Every marked conduit needs exactly one hook and only marked conduits may have one; otherwise the client
 * fails to start, naming the block.
 * <p>
 * Experimental (Lib 0.14.0, for Farms, FM1).
 */
@ApiStatus.Experimental
public final class RegisterConduitModelsEvent extends Event implements IModBusEvent {
    private final BiConsumer<Identifier, ConduitModelHook> sink;

    /** Lib fires this event; {@code sink} takes each hook by block id. */
    @ApiStatus.Internal
    public RegisterConduitModelsEvent(BiConsumer<Identifier, ConduitModelHook> sink) {
        this.sink = sink;
    }

    /**
     * Draws {@code conduit}'s block with {@code hook}. Throws {@link IllegalStateException} if the conduit is not
     * marked {@code customModel} or already has a hook.
     */
    public void register(ConduitEntry<?> conduit, ConduitModelHook hook) {
        sink.accept(conduit.block().id(), hook);
    }
}
