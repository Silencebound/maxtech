package dev.silencebound.maxtech.lib.shim.gametest;
import net.minecraft.network.chat.Component;
public abstract class GameTestException extends RuntimeException {
    public GameTestException(String message) { throw new UnsupportedOperationException("port stub"); }
    public abstract Component getDescription();
}
