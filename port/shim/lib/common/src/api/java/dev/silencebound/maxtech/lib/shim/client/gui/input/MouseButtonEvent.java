package dev.silencebound.maxtech.lib.shim.client.gui.input;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
@OnlyIn(Dist.CLIENT)
public record MouseButtonEvent(double x, double y, MouseButtonInfo buttonInfo) implements InputWithModifiers {
    @Override
    public int input() { throw new UnsupportedOperationException("port stub"); }
    @MouseButtonInfo.MouseButton
    public int button() { throw new UnsupportedOperationException("port stub"); }
    @InputWithModifiers.Modifiers
    @Override
    public int modifiers() { throw new UnsupportedOperationException("port stub"); }
}
