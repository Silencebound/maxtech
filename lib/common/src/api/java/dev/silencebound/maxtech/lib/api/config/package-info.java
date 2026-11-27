/**
 * Config files with presets, as every MaxTech mod declares them: {@code Configs.presetConfig(modId, anchor)} reads the
 * mod's built-in {@code <modid>/presets/standalone.json}, its
 * {@link dev.silencebound.maxtech.lib.api.config.ConfigBuilder} declares the values, and {@code register()} adds the
 * file. Experimental (Lib 0.14.0) until Farms, its first addon user, settles it.
 */
@ApiStatus.Experimental
@NullMarked
package dev.silencebound.maxtech.lib.api.config;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
