package dev.silencebound.maxtech.storage.neoforge.compat.ae2;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.LinkedHashMap;
import java.util.Map;

/** The AE2 bridge's texts. Plain data without AE2 classes, so datagen writes them without AE2 on the classpath. */
public final class Ae2Lang {
    /** AE2's mod id. */
    public static final String MOD_ID = "ae2";
    /** The AE2 grid as the bridge's info lines name it. */
    public static final String NETWORK = "info." + MaxtechStorage.MOD_ID + ".bridge.ae2_network";
    /** The Storage network as AE2 describes the storage the bridge mounts into its grid. */
    public static final String EXPORT = "info." + MaxtechStorage.MOD_ID + ".bridge.ae2_export";

    private Ae2Lang() {
    }

    /** The English lines by key, for datagen. */
    public static Map<String, String> translations() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(NETWORK, "Applied Energistics 2 network");
        out.put(EXPORT, "MaxTech Storage network");
        return out;
    }
}
