package dev.silencebound.maxtech.lib.api.menu;

/**
 * Handle of a tank: its resource, amount and capacity, declared in a {@link MenuValues} as a fluid tank
 * ({@link MenuValues.Builder#tank(java.util.function.Function, java.util.function.ToLongFunction, java.util.function.ToLongFunction)
 * tank}) or a tank of another or any resource kind ({@code kindTank}, the {@code tank} overloads taking a kind). Reads
 * with {@link MenuValueView#resource(TankValue)}, for fluid tanks also {@link MenuValueView#fluid(TankValue)}, and
 * {@link MenuValueView#amount(TankValue)} and {@link MenuValueView#capacity(TankValue)}; the resource travels with its
 * declaration's codec, the numbers as var-longs, all three together. Immutable; any thread.
 */
public final class TankValue extends MenuValue {
    TankValue(int index) {
        super(index);
    }
}
