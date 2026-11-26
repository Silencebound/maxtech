package dev.silencebound.maxtech.storage.priority;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.function.UnaryOperator;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.DataSlot;
import org.jspecify.annotations.Nullable;

/**
 * The shared priority setting (owner decisions 2026-10-07 and 2026-10-15): one range for every block with a priority,
 * the step buttons of the priority page, and how the priority travels. The range is the whole {@code int} range, as in
 * AE2. The page sends the value it wants in a {@link PriorityPayloads.Set} payload; menus check access and apply it in
 * {@link PriorityMenu#applyPriority}. The priority travels back in two 16-bit data slots ({@link Sync}), as data slots
 * sync shorts.
 */
public final class PriorityControl {
    /** Lowest priority: {@link Integer#MIN_VALUE}. */
    public static final int MIN = Integer.MIN_VALUE;
    /** Highest priority: {@link Integer#MAX_VALUE}. */
    public static final int MAX = Integer.MAX_VALUE;
    private static final int[] STEPS = {-100, -10, -1, 1, 10, 100};
    public static final int STEP_COUNT = STEPS.length;

    private static final String PREFIX = "gui." + MaxtechStorage.MOD_ID + ".priority.";
    /** "Priority": the page's title. */
    public static final String TITLE_KEY = PREFIX + "title";
    /** "Priority: %s": the tab's tooltip and item lines. */
    public static final String VALUE_KEY = PREFIX + "value";
    public static final String HELP_KEY = PREFIX + "help";
    public static final String BACK_KEY = PREFIX + "back";

    private PriorityControl() {
    }

    /** The priority within {@link #MIN}..{@link #MAX}; every int already is. */
    public static int clamp(int priority) {
        return priority;
    }

    /**
     * The two data slots a menu syncs the priority with: live from {@code target} on the server, plain on the client.
     * {@code add} is the menu's {@code addDataSlot}.
     */
    public static Sync sync(@Nullable Prioritized target, UnaryOperator<DataSlot> add) {
        return new Sync(add.apply(target == null ? DataSlot.standalone() : new Live(target, false)),
                add.apply(target == null ? DataSlot.standalone() : new Live(target, true)));
    }

    /** The priority in the data slots from {@link #sync}. */
    public static int read(Sync sync) {
        return join(sync.low.get(), sync.high.get());
    }

    /** The low 16 bits of {@code priority}, as a data slot carries them. */
    static int low(int priority) {
        return priority & 0xFFFF;
    }

    /** The high 16 bits of {@code priority}. */
    static int high(int priority) {
        return priority >>> 16;
    }

    /** The priority from the halves {@link #low} and {@link #high} made; sign extension of a half is ignored. */
    static int join(int low, int high) {
        return (low & 0xFFFF) | (high << 16);
    }

    /** {@code priority + step}, stopping at {@link #MIN} and {@link #MAX} instead of wrapping around. */
    public static int add(int priority, int step) {
        return (int) Math.clamp((long) priority + step, MIN, MAX);
    }

    /** Step {@code i} of the page's buttons: -100, -10, -1, +1, +10, +100. */
    public static int step(int i) {
        return STEPS[i];
    }

    /** The label of step button {@code i}: "-100", "+1" and so on. */
    public static String stepLabel(int i) {
        int step = STEPS[i];
        return step > 0 ? "+" + step : Integer.toString(step);
    }

    /** "Priority: 5". */
    public static Component line(int priority) {
        return Component.translatable(VALUE_KEY, Integer.toString(priority));
    }

    /** The two data slots of a menu's priority. */
    public static final class Sync {
        private final DataSlot low;
        private final DataSlot high;

        Sync(DataSlot low, DataSlot high) {
            this.low = low;
            this.high = high;
        }
    }

    /** One half of the priority read live from its block; server side. */
    private static final class Live extends DataSlot {
        private final Prioritized target;
        private final boolean upper;

        Live(Prioritized target, boolean upper) {
            this.target = target;
            this.upper = upper;
        }

        @Override
        public int get() {
            return upper ? high(target.priority()) : low(target.priority());
        }

        @Override
        public void set(int value) {
            // Server side the block is the truth; the payload changes it.
        }
    }
}
