/**
 * Client-only GUI building blocks shared by MaxTech screens: {@link ScreenComponents} for drawing and
 * {@link VirtualGrid} for long scrolling grids, and the editor toolkit: {@link PixelScale} (a screen's own whole-pixel
 * scale), {@link PanView} (a pannable canvas), {@link RightAngleWire} (wires drawn with fills), {@link SidePanel} (a
 * scrolling side panel) and {@link UndoStack} (snapshot undo). The Accent Frame look and its widget set are api
 * ({@code lib.api.client.gui}); the {@code accent} subpackage keeps the device side tabs and the demo screen. Never
 * load these classes on a dedicated server.
 */
@NullMarked
package dev.silencebound.maxtech.lib.client.gui;

import org.jspecify.annotations.NullMarked;
