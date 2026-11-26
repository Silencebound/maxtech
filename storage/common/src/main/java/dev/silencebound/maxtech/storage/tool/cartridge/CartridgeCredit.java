package dev.silencebound.maxtech.storage.tool.cartridge;

/** The credit a solved cartridge gives, pure so JUnit can test it. */
public final class CartridgeCredit {
    private CartridgeCredit() {
    }

    /**
     * Research points for one solved cartridge: {@code percent} of the {@code pointsPerToken} a skip token costs,
     * rounded down, never below 1 when {@code percent} is above 0 (a cartridge always gives something), 0 at 0 percent.
     */
    public static long points(long pointsPerToken, int percent) {
        if (percent <= 0 || pointsPerToken <= 0) {
            return 0;
        }
        return Math.max(1, pointsPerToken * percent / 100);
    }
}
