/*
 * SPDX-License-Identifier: LGPL-2.1-only
 */
/*
 * Added for MaxTech Lib's 1.21.1 build (port/NOTICE-transfer.md): Minecraft 26.1 and NeoForge 26.1 types the
 * vendored transfer api needs and 1.21.1 lacks. Part of the vendored library, under its licence.
 */

/**
 * Stand-ins for the 26.1 types the vendored transfer api is built on ({@code TypedInstance}, {@code StackCopySlot}),
 * shaped like the originals so the vendored files keep their code.
 */
@NullMarked
package dev.silencebound.maxtech.lib.shim.transfer.port;

import org.jspecify.annotations.NullMarked;
