package dev.silencebound.maxtech.lib.shim.client.gui.input;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
@OnlyIn(Dist.CLIENT)
public record CharacterEvent(int codepoint) {
    public String codepointAsString() { throw new UnsupportedOperationException("port stub"); }
    public boolean isAllowedChatCharacter() { throw new UnsupportedOperationException("port stub"); }
}
