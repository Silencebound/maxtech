/**
 * Text helpers for MaxTech screens that are pure Java, so code on either side may use them:
 * {@link dev.silencebound.maxtech.lib.api.text.TextFit} fits text into a width with an ellipsis. Moved out of
 * {@code lib.api.client.gui} in Lib 0.15.0 so the client package holds client classes only. Experimental until Farms
 * (FM1) settles the GUI toolkit (ADR-0016).
 */
@ApiStatus.Experimental
@NullMarked
package dev.silencebound.maxtech.lib.api.text;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
