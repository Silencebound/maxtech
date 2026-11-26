/**
 * Owner and team security (M6): an {@link dev.silencebound.maxtech.lib.api.security.OwnerRecord} saved with an owned
 * block or item, the {@link dev.silencebound.maxtech.lib.api.security.Owned} interface, and the one access check in
 * {@link dev.silencebound.maxtech.lib.api.security.OwnerSecurity}, which resolves teams through vanilla scoreboard
 * teams and pluggable {@link dev.silencebound.maxtech.lib.api.security.TeamResolver}s (FTB Teams when installed).
 */
@NullMarked
package dev.silencebound.maxtech.lib.api.security;

import org.jspecify.annotations.NullMarked;
