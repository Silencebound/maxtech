/**
 * Off-thread work for the server: copy-on-write snapshots of main-thread data, a bounded worker pool, and commits back
 * on the main thread with revalidation (PRD 7.2, Performance). Internal; Storage and the M7 planner use it.
 */
@NullMarked
package dev.silencebound.maxtech.lib.concurrent;

import org.jspecify.annotations.NullMarked;
