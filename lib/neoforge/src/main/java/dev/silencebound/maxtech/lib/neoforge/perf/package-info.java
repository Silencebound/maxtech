/**
 * Perf GameTest support shared by every mod: {@link PerfBudget} checks a measurement against its budget. It lives in
 * Lib's main source set rather than a {@code gametest} one because an addon's GameTests see only Lib's main classes, as
 * with {@code ProgressionGraph}. Nothing outside GameTests uses it.
 */
@NullMarked
package dev.silencebound.maxtech.lib.neoforge.perf;

import org.jspecify.annotations.NullMarked;
