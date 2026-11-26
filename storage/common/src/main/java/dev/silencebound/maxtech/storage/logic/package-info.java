/**
 * The logic rule core (M7L area A): graphs of typed nodes and wires, their codec, the compiler that checks and orders
 * them, and the incremental evaluator. Pure logic without level access; the Logic Controller, labels, sensors and
 * actions implement {@link dev.silencebound.maxtech.storage.logic.LogicContext} on top of it.
 */
@NullMarked
package dev.silencebound.maxtech.storage.logic;

import org.jspecify.annotations.NullMarked;
