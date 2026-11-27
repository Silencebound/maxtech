package dev.silencebound.maxtech.lib.conduit;

import dev.silencebound.maxtech.lib.api.conduit.ConduitContents;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * How a conduit's {@link ConduitContents} is packed for the client model. Only the client knows the look indices, so
 * the client glue installs its packer at startup; everywhere else (the server, a dedicated server) the default packs
 * every contents as 0, and this server-side code never names a client class.
 */
public final class ContentsPacking {
    private static volatile ToIntFunction<ConduitContents> packer = ContentsPacking::none;

    private ContentsPacking() {
    }

    private static int none(ConduitContents contents) {
        return 0;
    }

    /** Installs the client's packer; called once from client setup. */
    public static void install(ToIntFunction<ConduitContents> clientPacker) {
        packer = Objects.requireNonNull(clientPacker, "clientPacker");
    }

    /** {@code contents} packed by the installed packer; 0 when none is (always on a server). */
    public static int pack(ConduitContents contents) {
        return packer.applyAsInt(contents);
    }

    /** Back to the default packer; tests only. */
    static void reset() {
        packer = ContentsPacking::none;
    }
}
