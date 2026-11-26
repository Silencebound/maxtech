/**
 * The sensor (M7L area D): reads one block next to it without changing it (its item, fluid and energy handlers, its
 * block state, its comparator output and the redstone it gives) and offers what it read as a list of named values for
 * the logic layer. A full block and a cable part with the same readers and settings. Never ticks.
 */
@NullMarked
package dev.silencebound.maxtech.storage.sensor;

import org.jspecify.annotations.NullMarked;
