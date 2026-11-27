/**
 * Lib's GUI toolkit for MaxTech screens, client side only: never load these classes on a dedicated server, except
 * {@code TextFit}, which is pure Java. The Accent Frame look
 * ({@link dev.silencebound.maxtech.lib.api.client.gui.Accent}, palette and drawing) and the widgets built on it:
 * {@link dev.silencebound.maxtech.lib.api.client.gui.AccentButton},
 * {@link dev.silencebound.maxtech.lib.api.client.gui.IconButton} with its
 * {@link dev.silencebound.maxtech.lib.api.client.gui.Icon},
 * {@link dev.silencebound.maxtech.lib.api.client.gui.ScrollBar} and
 * {@link dev.silencebound.maxtech.lib.api.client.gui.AccentToast}; for screens that lay out lists and tables
 * themselves, {@link dev.silencebound.maxtech.lib.api.client.gui.AccentPanel},
 * {@link dev.silencebound.maxtech.lib.api.client.gui.AccentInset},
 * {@link dev.silencebound.maxtech.lib.api.client.gui.AccentRow},
 * {@link dev.silencebound.maxtech.lib.api.client.gui.AccentTabRow},
 * {@link dev.silencebound.maxtech.lib.api.client.gui.AccentReadout},
 * {@link dev.silencebound.maxtech.lib.api.client.gui.AccentTag} and
 * {@link dev.silencebound.maxtech.lib.api.client.gui.AccentStatus}. Text goes through
 * {@link dev.silencebound.maxtech.lib.api.client.gui.TextScale} (the client's text size option) and
 * {@link dev.silencebound.maxtech.lib.api.client.gui.TextFit} (fitting with an ellipsis);
 * {@link dev.silencebound.maxtech.lib.api.client.gui.LayoutCheck} checks that a layout stays on the smallest GUI.
 * <p>
 * Moved from {@code lib.client.gui} in Lib 0.15.0 (ADR-0016) so addons on the api jar draw the same look; experimental
 * until Farms (FM1) settles it. Positions are absolute screen coordinates unless a type says otherwise; call from the
 * client thread.
 */
@ApiStatus.Experimental
@NullMarked
package dev.silencebound.maxtech.lib.api.client.gui;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
