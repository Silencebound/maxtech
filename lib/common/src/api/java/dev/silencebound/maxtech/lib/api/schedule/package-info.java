/**
 * Budgeted per-level work queues (PRD 7.2, Performance): task groups with a time and operation budget per owner,
 * priorities, wake-on-event and backoff, run from the level tick after the networks. An owner gets a group with
 * {@code TaskSchedulers.newGroup(level)}, adds its {@link dev.silencebound.maxtech.lib.api.schedule.ScheduledTask}s and
 * wakes them on events instead of ticking. Experimental (Lib 0.14.0) until Farms, its first addon user, settles it.
 */
@ApiStatus.Experimental
@NullMarked
package dev.silencebound.maxtech.lib.api.schedule;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
