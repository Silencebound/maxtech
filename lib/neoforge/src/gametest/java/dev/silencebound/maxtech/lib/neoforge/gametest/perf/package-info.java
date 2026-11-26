/**
 * Performance GameTests: large scenarios that assert the tick budgets of PRD 8.1 after warm-up and log what they
 * measured. Each runs alone in its own batch. Budgets go through Lib's
 * {@link dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget}, which logs instead of failing when the machine is
 * busy.
 */
@NullMarked
package dev.silencebound.maxtech.lib.neoforge.gametest.perf;

import org.jspecify.annotations.NullMarked;
