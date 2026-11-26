/**
 * Conduit model geometry, client half (ADR-0012): {@link ConduitGeometry} lays out the faces of a conduit's body and
 * face decorations from its {@link dev.silencebound.maxtech.lib.conduit.ConduitLook}, and {@link PartCache} keeps one
 * baked part per key. Plain Java on vanilla maths types, so it is unit-tested; the loader's conduit model bakes the
 * faces into quads.
 */
@NullMarked
package dev.silencebound.maxtech.lib.client.conduit;

import org.jspecify.annotations.NullMarked;
