/**
 * The Accent Frame look every MaxTech screen uses (owner decision 2026-10-18, GUI pass): {@link Accent} (palette and
 * drawing) and the one widget set built on it: {@link AccentButton} (text, normal and compact; a toggle is a button lit
 * while on), {@link IconButton} (plain, toggle, cycle) with its {@link Icon}, {@link SideTabs} (device side tabs with
 * flyouts, changed dots and badges), {@link ScrollBar} and {@link AccentToast}; for screens that lay out lists and
 * tables themselves (B09 item 21e), {@link AccentPanel} (a panel as a layout), {@link AccentInset} (a dark well),
 * {@link AccentRow} (list rows), {@link AccentTabRow} (page tabs with badges), {@link AccentReadout}, {@link AccentTag}
 * and {@link AccentStatus} (state colours and dots); plus the dev-only {@link AccentDemoScreen}. Tooltips go through
 * vanilla widget tooltips. Never load these classes on a dedicated server.
 */
@NullMarked
package dev.silencebound.maxtech.lib.client.gui.accent;

import org.jspecify.annotations.NullMarked;
