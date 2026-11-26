/**
 * World highlights: timed outlines of blocks and boxes and coloured line segments, optionally drawn through walls. The
 * server sends a {@link dev.silencebound.maxtech.lib.highlight.HighlightRequest} with
 * {@link dev.silencebound.maxtech.lib.highlight.Highlights}; the client keeps what it shows in
 * {@link dev.silencebound.maxtech.lib.highlight.HighlightStore}, and Lib's client glue draws it in one batched pass.
 * Server-safe.
 */
@NullMarked
package dev.silencebound.maxtech.lib.highlight;

import org.jspecify.annotations.NullMarked;
