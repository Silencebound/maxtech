/**
 * The content builder: one chain per block or item registers it and declares its datagen (model, lang, loot, tags,
 * recipes) and creative-tab entry. Declarations are plain data built at class init; outside datagen only registration
 * and the creative tab read them. Internal until it settles (M3 task 12); lib/neoforge's datagen providers turn the
 * declarations into files.
 */
@NullMarked
package dev.silencebound.maxtech.lib.content;

import org.jspecify.annotations.NullMarked;
