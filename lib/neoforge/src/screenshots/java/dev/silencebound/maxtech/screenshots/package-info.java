/**
 * The client screenshot harness ({@code ./gradlew :<mod>:neoforge:runScreenshots -Pscene=<name>}, the client-check
 * skill). Its sources live in Lib and are compiled into every mod's GameTest source set, so they run in dev clients
 * only and never reach a jar.
 */
@NullMarked
package dev.silencebound.maxtech.screenshots;

import org.jspecify.annotations.NullMarked;
