package dev.silencebound.maxtech.lib.client.gui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Undo and redo for an editor screen by snapshots: before each change the screen {@link #record}s the state it is about
 * to change; {@link #undo} hands back the state before the last change and keeps the current one for {@link #redo}. A
 * new change clears what could be redone. Holds at most {@code capacity} snapshots and drops the oldest beyond that.
 * Snapshots should be immutable values (a record of the edited data), so nothing the editor does later changes them.
 *
 * <p>
 * Client thread only; not thread-safe.
 *
 * @param <T>
 *            the snapshot type
 */
public final class UndoStack<T> {
    private final int capacity;
    private final Deque<T> undo = new ArrayDeque<>();
    private final Deque<T> redo = new ArrayDeque<>();

    /** A stack keeping at most {@code capacity} (at least 1) undo steps. */
    public UndoStack(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** Remembers {@code before}, the state as it is just before a change; clears the redo steps. */
    public void record(T before) {
        undo.push(before);
        while (undo.size() > capacity) {
            undo.removeLast();
        }
        redo.clear();
    }

    /** The state before the last change, if any; {@code current} becomes redoable. */
    public Optional<T> undo(T current) {
        T previous = undo.poll();
        if (previous == null) {
            return Optional.empty();
        }
        redo.push(current);
        return Optional.of(previous);
    }

    /** The state the last undo left, if any; {@code current} becomes undoable again. */
    public Optional<T> redo(T current) {
        T next = redo.poll();
        if (next == null) {
            return Optional.empty();
        }
        undo.push(current);
        return Optional.of(next);
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /** Forgets every step, as after loading a new state. */
    public void clear() {
        undo.clear();
        redo.clear();
    }
}
