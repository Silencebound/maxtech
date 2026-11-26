package dev.silencebound.maxtech.storage.controller.module;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.registry.ModContent;

/** The security screen's lang keys and English names; loaded with {@link ExpansionContent}. */
public final class SecurityTexts {
    private static final String PREFIX = "gui." + MaxtechStorage.MOD_ID + ".security.";
    public static final String EVERYONE = PREFIX + "everyone";
    public static final String OWNER_TEAM = PREFIX + "owner_team";
    public static final String TEAM = PREFIX + "team";
    public static final String NO_RULE = PREFIX + "no_rule";
    public static final String HINT = PREFIX + "hint";
    public static final String ADD_HINT = PREFIX + "add_hint";
    public static final String ADD = PREFIX + "add";
    public static final String TRUSTED = PREFIX + "trusted";
    public static final String NOT_TRUSTED = PREFIX + "not_trusted";
    public static final String UNKNOWN_PLAYER = "message." + MaxtechStorage.MOD_ID + ".security.unknown_player";

    private SecurityTexts() {
    }

    /** Declares the texts and registers the screen's payload ({@link SecurityPayloads}). */
    static void register() {
        SecurityPayloads.register();
        ModContent.CONTENT.translation(ADD_HINT, "Player name");
        ModContent.CONTENT.translation(ADD, "Add");
        ModContent.CONTENT.translation(TRUSTED, "Trusted: may change these rules");
        ModContent.CONTENT.translation(NOT_TRUSTED, "Click the mark to trust this player (owner only)");
        ModContent.CONTENT.translation(UNKNOWN_PLAYER, "No player called %s");
        ModContent.CONTENT.translation(EVERYONE, "Everyone else");
        ModContent.CONTENT.translation(OWNER_TEAM, "Owner's team");
        ModContent.CONTENT.translation(TEAM, "Team %s");
        ModContent.CONTENT.translation(NO_RULE, "-");
        ModContent.CONTENT.translation(HINT, "Click a row to change its access");
        ModContent.CONTENT.translation(AccessLevel.NONE.translationKey(), "None");
        ModContent.CONTENT.translation(AccessLevel.VIEW.translationKey(), "View");
        ModContent.CONTENT.translation(AccessLevel.DEPOSIT.translationKey(), "Deposit");
        ModContent.CONTENT.translation(AccessLevel.CRAFT.translationKey(), "Craft");
        ModContent.CONTENT.translation(AccessLevel.LOGIC.translationKey(), "Logic");
        ModContent.CONTENT.translation(AccessLevel.FULL.translationKey(), "Full");
    }
}
