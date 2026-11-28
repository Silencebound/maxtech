---
name: perf
description: Writing fast code in MaxTech and proving it - hot-path rules with examples from this repo, writing a perf GameTest with PerfBudget (measureUnder, measureTicks, measureTicksOnce), running perf tests locally and reading CI warnings, benches and the in-game profiler. Use for any change to per-tick code, the network, the scheduler, terminals or saves, and for any perf_* test.
---

# Performance in MaxTech

The requirement is PRD §8.1 (and `storage/PRD.md` §7.2 for Storage): big multiplayer bases must not cost tick time.
`CLAUDE.md` has the short rules; this skill has the how.

## Hot paths

A hot path is code that runs per tick, per transfer or per network node: scheduler tasks, port and bus moves, network
patches and recounts, terminal deltas, display updates, saves of changed cells.

- **Nothing ticks without work.** Cables and idle devices have no ticker. Work runs as a task on the network's
  scheduler and sleeps until an event wakes it (container change, neighbour change, redstone, a network change).
  Recurring work is spread over ticks by position hash, never all on the same tick.
- **No lookups per tick.** No `getCapability` or `getBlockEntity` in a loop: keep a Lib `CachedBlockLookup` (a
  `BlockCapabilityCache`) in a field and react to its invalidation. See the capability cache entry in
  `neoforge-26-1/SKILL.md` for the two ways it breaks.
- **No allocation.** No streams, lambdas, boxing, iterators over boxed maps or new collections. Use fastutil primitive
  maps keyed by interned key ids, reused mutable `BlockPos`, plain loops. Storage never keeps `ItemStack`s in its
  index.
- **Incremental, not full.** Change one node, touch its neighbours; split work that can grow (rebuilds, scans) across
  ticks with a budget. Totals update from the change that caused them, never by recounting.
- **Watch the hidden costs.** Every `Transaction.open`/`openRoot` walks the stack (about a sixth of a busy port's
  operation time in a profile), and `insertStacking` opens a nested one: a hot loop opens only the transactions it
  needs (`PortTransfers.insertStacking`). Listeners subscribe by key id so a change is dispatched once.
- **fastutil shrinks on removal.** `ObjectArrayFIFOQueue` halves its array once it drains to a quarter, so a queue
  that fills and empties each tick allocates on most ticks (Lib's `TaskQueue` never shrinks); the open hash maps and
  sets rehash down the same way while a batch removes many entries (`KeyRegistry.releaseUnused` holds that back).
- **Off the main thread only on snapshots.** Search, sort and craft planning read copy-on-write snapshots on worker
  threads; the server thread commits results and checks them again.

## Writing a perf GameTest

Every budget goes through `lib.neoforge.perf.PerfBudget` (Lib), named `perf_<area>_<what>` and kept in a
`*PerfGameTests` class. Pick the method by how the measurement runs:

| Method | Use when | Must allow |
|---|---|---|
| `measureUnder(helper, test, budgetNanos, measurement, failure)` | the work runs within one call | running it twice: reset what it filled |
| `measureTicks(helper, test, ticks, budgetNanos, measurement, failure)` | the work spreads over ticks | `start()` resets every sum; `maxTicks` covers `2 * (ticks + 1)` plus setup |
| `measureTicksOnce(...)` | a second attempt can't repeat the situation (cuts on a network the first attempt already patched) | no retry, so one slow run fails locally |

`measureTicks` returns a step for `thenWaitUntil`; build the measurement with `TickMeasurement.of(start, tick,
finish)` and keep `tick()` free of allocation, since it runs inside the measured window.

- The budget is a named constant with a Javadoc line saying what it bounds (`CellPerfGameTests.BUDGET_NANOS`: median
  time to encode a save with 100 changed cells among 1,000). Measure a median over rounds where single runs are noisy.
- Assert the work too, not only the time: "only the changed cells were encoded", "the change rerouted a device". A
  fast test that skipped the work proves nothing. Break the code once and watch the test fail.
- Build the scene in code and give the test its own environment batch (`StorageTestEnvironment.plain()` or a
  `clearing(...)` wrapper), so other tests don't share its level or config.
- An idle test waits until its devices have settled (nothing queued, no runs for some ticks, with a limit) before it
  measures, not a fixed number of ticks: the scheduler's time budget per tick lets a slow machine settle hundreds of
  devices much later (`WorldDeviceGameTests.QUIET_TICKS`, issue #3). Simulate one with the
  `scheduler.ownerBudgetMicros` test option at `1`.
- Never loosen a budget or disable a test to get green. If a budget is wrong, say so in the PR with the numbers from a
  quiet machine and let the owner decide.

## Running and reading results

- Locally a budget over twice fails the test. Run perf tests alone, with no other build, game or IDE indexing busy:
  `./gradlew :storage:neoforge:runGameTestServer -Pgametest='maxtech_storage:perf_*'` (Lib:
  `:lib:neoforge:runGameTestServer -Pgametest='maxtech_lib:perf_*'`). `scripts/perf-run.sh -n 3` runs both suites three
  times, keeps every result in a per-machine CSV and compares each test's median with the median of its three results
  before. A retry on a busy machine logs "skipped budget: machine busy"; that is not a pass, run it again when the
  machine is quiet.
- In CI (`MAXTECH_PERF_BUDGETS_WARN=true`) a budget over twice only logs and annotates "Perf budget over"; hosted
  runners vary too much to block on. A CI warning on a PR that touches the area means: run that test on a quiet machine
  (the owner's Mac or Windows PC) before merging, and put the numbers in the PR.
- A change to a hot path runs the perf tests of its area on a quiet machine before merge, warning or not.
- Compare old and new code warm, never from one test alone in a fresh server (`-Pgametest='maxtech_storage:one_test'`):
  that run measures how far the JIT got before the window, not the code. `perf_storage_burst_insert` took about 90 ms
  alone and 27 ms warm; alone, new code read 11% slower in 9 of 10 alternated rounds while warm copies, CPU samples and
  GC were the same (2026-11-26). Compare with the full suite (`perf-run.sh -n 3`) or with `-Pgametest.repeat=30` and the
  median of the copies, and alternate old and new runs so machine drift falls on both.

## Perf trends in CI (2026-11-28)

- Every CI run that runs GameTests collects PerfBudget's `perf-result <test> <measured_ns> <budget_ns> <outcome>` lines
  into a JSON artifact (`.github/scripts/perf-trends.py extract`). Nightly and manual runs on main store it as
  `perf-results-main` (30 days), the baseline; pushes to main run no GameTests and store none.
- On a same-repo PR the `perf trends` job compares with the newest `perf-results-main` and keeps one PR comment up to
  date: tests over 20% slower, over 20% faster, and tests without a baseline. It never fails the build. A slowdown there
  is a hint, like a "Perf budget over" warning: confirm it on a quiet machine before acting. Fork PRs get no comment.
- Changing the script: `bash .github/scripts/test/test-perf-trends.sh` runs it against the sample log there.

## Finding where time goes

- **Benches:** `*BenchGameTests` (`LogicCostBenchGameTests`, `PortOpBenchGameTests`) time single operations and only
  run with `MAXTECH_BENCH=1`. Use them to compare before and after a change; they have no budget.
- **In game:** `/maxtech network profile [seconds]` (game masters) reports which networks, devices, device kinds and
  logic rules cost the most, in microseconds per tick and as a share; `/maxtech network` lists the heaviest networks.
  Each network also shows its own timing in the controller.
- **In game, Lib:** `/maxtech_lib profile [seconds]` (default 10, `stop` ends it early; game masters) reports Lib's
  scheduler and network time per tick, scheduler time by mod, the ten most expensive task classes and network types,
  and the largest networks with their node counts. A task kind is its class (`LibTransferPort.Task`), its mod comes
  from the package. While no session runs the cost is one static read per task run (`TaskScheduler.setRunListener`
  is null); `perf_profiler_off_task_runs` guards that. During a session it turns on `LevelTasks`/`NetworkGraph`
  timing and puts every task run in a per-class tally.
- **Spark** in a dev client or server for a full profile; the PRD's targets are measured with it. Lib tasks show in
  its call tree under `TaskGroup.runOne` by their class name; spark's api (statistics, GC, placeholders) has no hook
  to name sections, and Lib's tasks all run on the server thread, so Lib registers nothing for Spark.
- **JFR** on a GameTest run: `-Pgametest.jfr=<file.jfr>`. The `profile` settings sample every 10 to 20 ms, which
  misses windows of a few microseconds per tick; add a settings file with `jdk.ExecutionSample` at `1 ms`
  (`-Pgametest.jfr=<file.jfr>,settings=<fast.jfc>`) and `-Pgametest.repeat=100` to get a few hundred samples in the
  measured code. Repeated copies run side by side in one level, so level-wide budgets fail then; only the profile
  counts.

Record what a profile or bench taught you about an API's cost in `neoforge-26-1/SKILL.md` or `storage-facts.md`, like
the transaction stack walk above.
