/**
 * Pack readers (M7L2): data files that map another mod's blocks to named sensor values read from the block entity's
 * saved data ({@code data/<namespace>/maxtech_storage/readers/**.json}), so pack makers add values without code. The
 * file format ({@link dev.silencebound.maxtech.storage.sensor.reader.PackReader}), the path into saved data
 * ({@link dev.silencebound.maxtech.storage.sensor.reader.ReaderPath}), the loader and the loaded set.
 */
@NullMarked
package dev.silencebound.maxtech.storage.sensor.reader;

import org.jspecify.annotations.NullMarked;
