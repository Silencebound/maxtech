package dev.silencebound.maxtech.lib.shim.gametest;

import net.minecraft.network.chat.Component;

/**
 * 26.1's {@code net.minecraft.gametest.framework.GameTestException}, the base of its GameTest failures. 1.21.1's
 * failures ({@code GameTestAssertException}, {@code GameTestTimeoutException}) don't extend it, so Lib code that catches
 * it is pointed at 1.21.1's types by port/rules/saving.py; this class only keeps the contract's shape.
 */
public abstract class GameTestException extends RuntimeException {
    public GameTestException(String message) {
        super(message);
    }

    public abstract Component getDescription();
}
