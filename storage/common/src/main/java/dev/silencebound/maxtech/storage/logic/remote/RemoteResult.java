package dev.silencebound.maxtech.storage.logic.remote;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.Locale;

/** What came of a pause or resume through a logic remote ({@link LogicRemoteMenu#setRule}). */
public enum RemoteResult {
    /** The rule is in the state asked for; the remote paid when it changed. */
    DONE,
    /** The remote is out of range, unbound, its network gone, or it left the hand. */
    OUT_OF_REACH,
    /** The remote cannot pay one operation. */
    NO_POWER,
    /** The player lacks logic access to that controller. */
    DENIED,
    /** No logic controller of the network at that position, or no rule with that index. */
    NO_SUCH_RULE;

    /** The message key shown on the remote's screen. */
    public String key() {
        return "gui." + MaxtechStorage.MOD_ID + ".logic_remote.result." + name().toLowerCase(Locale.ROOT);
    }

    /** The result named {@code name}; {@link #NO_SUCH_RULE} for an unknown name. */
    public static RemoteResult byName(String name) {
        for (RemoteResult result : values()) {
            if (result.name().equals(name)) {
                return result;
            }
        }
        return NO_SUCH_RULE;
    }
}
