package dev.silencebound.maxtech.storage.cell;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import java.util.function.LongSupplier;

/**
 * What a cell's controller chip lets it store, and how much of it one byte holds (PRD 7.2, the cell builder). Each type
 * is an entry of the {@link CellTypes} registry, so other mods add chips by registering their own type; a cell names
 * its type by registry id. Item and fluid types exist now; universal and creative come with the cell workbench.
 *
 * <p>
 * Types are immutable and shared by every cell of the type. Methods are called on the server thread when a cell enters
 * a drive and for every insert, so they must be cheap.
 */
public class CellType {
    private final StackKey.Kind kind;
    private final LongSupplier unitsPerByte;

    /**
     * A type that stores keys of {@code kind} only, {@code unitsPerByte} items or mB to a byte (read from config each
     * time a cell of the type enters a drive).
     */
    public CellType(StackKey.Kind kind, LongSupplier unitsPerByte) {
        this.kind = kind;
        this.unitsPerByte = unitsPerByte;
    }

    /** Whether a cell of this type may hold {@code key}. */
    public boolean accepts(StackKey key) {
        return key.kind() == kind;
    }

    /** Items, or mB of fluid, one byte holds for keys of {@code kind}; at least 1. Only asked for accepted kinds. */
    public long unitsPerByte(StackKey.Kind kind) {
        return Math.max(1, unitsPerByte.getAsLong());
    }

    /** The kind of key this type stores. */
    public StackKey.Kind kind() {
        return kind;
    }
}
