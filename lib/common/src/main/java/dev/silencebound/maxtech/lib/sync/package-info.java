/**
 * Large lists synced from server to client for open screens (PRD 7.2, terminals): one full dump when a screen opens,
 * then {@code (id, count)} changes merged per entry and sent at most a few times a second, only while the screen is
 * open. Entries are named by an {@code int} id the owner chooses (storage's interned keys); an entry's definition
 * travels once, with its first count. The client keeps its own copy ({@link ClientSyncedList}) and searches and sorts
 * it locally ({@link SyncedListIndex}), so queries never reach the server. Everything here is server-safe; the grid
 * widget that shows such a list is {@code client.gui.VirtualGrid}.
 */
@NullMarked
package dev.silencebound.maxtech.lib.sync;

import org.jspecify.annotations.NullMarked;
