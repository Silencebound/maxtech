package dev.silencebound.maxtech.lib.shim.client.gui.input;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** 26.1's typed character, a code point; 1.21.1 passes a {@code char} to {@code charTyped}. */
@OnlyIn(Dist.CLIENT)
public record CharacterEvent(int codepoint) {
    public String codepointAsString() {
        return Character.toString(codepoint);
    }

    /** 26.1's {@code StringUtil.isAllowedChatCharacter(int)}. */
    public boolean isAllowedChatCharacter() {
        return codepoint != 167 && codepoint >= 32 && codepoint != 127;
    }
}
