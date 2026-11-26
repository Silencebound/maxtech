package dev.silencebound.maxtech.storage.display.client;

import dev.silencebound.maxtech.storage.config.StorageClientConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.silencebound.maxtech.lib.text.RichText;
import dev.silencebound.maxtech.storage.display.ChartRecorder;
import dev.silencebound.maxtech.storage.display.DisplayContent;
import dev.silencebound.maxtech.storage.history.ValueRing;
import dev.silencebound.maxtech.storage.display.DisplayText;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import dev.silencebound.maxtech.storage.display.DisplayMode;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.display.DisplaySourceKind;
import dev.silencebound.maxtech.storage.display.DisplayUpdate;
import dev.silencebound.maxtech.storage.display.DisplayView;
import dev.silencebound.maxtech.storage.display.DisplayWalls;
import dev.silencebound.maxtech.storage.display.ScreenRows;
import dev.silencebound.maxtech.storage.palette.StatusPalette;
import net.minecraft.ChatFormatting;
import net.minecraft.world.item.ItemStack;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.terminal.AmountFormat;
import java.util.List;
import java.util.function.IntFunction;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.locale.Language;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * Draws one screen's hologram: emissive accent-coloured quads (frame, a slow scanline, the line or bars of the rolling
 * window, a fill bar for values with a maximum) through vanilla's position-colour {@code debugQuads} render type, which
 * blends and has no lightmap, so the picture glows in the dark; and full-bright text through the submit collector's
 * text nodes. Everything is laid out in the screen's own plane in blocks, so it scales with the wall and stays legible
 * on a single panel. Rich text ({@link DisplaySourceKind#TEXT} and markup in logic notices) is a {@link RichLayout}
 * built once per text, size, language and value change; its icons are the items' own GUI models through vanilla's item
 * model resolver and {@link ItemStackRenderState#submit}, flattened onto the screen, so every item and block looks as
 * in an inventory without a texture lookup of our own. Client render thread; vanilla and Blaze3D APIs only.
 */
public final class ScreenPainter {
    private static final Quaternionf[] ROTATIONS = new Quaternionf[6];
    private static final int OFFLINE_COLOUR = 0xFFE0A040;

    static {
        for (Direction facing : Direction.values()) {
            Direction right = DisplayWalls.right(facing);
            Direction up = DisplayWalls.up(facing);
            // Columns: local x to the viewer's right, y up, z out of the screen.
            Matrix3f basis = new Matrix3f(right.getStepX(), right.getStepY(), right.getStepZ(), up.getStepX(),
                    up.getStepY(), up.getStepZ(), facing.getStepX(), facing.getStepY(), facing.getStepZ());
            ROTATIONS[facing.get3DDataValue()] = basis.getNormalizedRotation(new Quaternionf());
        }
    }

    /**
     * Width in blocks of the panel model's frame (2 px strips on a wall's outer edges, {@code DisplayModels}); the
     * picture stays inside it.
     */
    public static final float PANEL_BEZEL = 2F / 16F;
    /** Width in blocks of the frame in the part's front texture: 2 of its 16 texels over the part's 12 px. */
    public static final float PART_BEZEL = 2F / 16F * 0.75F;
    /** Gap in blocks between the model's frame and the picture's own border. */
    private static final float BORDER_GAP = 0.025F;
    /** Further gap in blocks between the picture's border and rich text. */
    private static final float TEXT_GAP = 0.035F;

    /**
     * Where a screen lies, relative to the pose's origin: the bottom-left corner of its glass as the viewer sees it,
     * the way it faces, its size in blocks and the width in blocks of the model's frame around its edge, inside which
     * everything is drawn.
     */
    public record Frame(double x, double y, double z, Direction facing, float width, float height, float bezel) {
    }

    /** Quads handed to the collector this frame; they outlive {@link #submit}, so they are reused next frame. */
    private static final ObjectArrayList<Quads> POOL = new ObjectArrayList<>();
    private static int used;
    private static final FormattedCharSequence[] SOURCE_NAMES = new FormattedCharSequence[DisplaySourceKind
            .values().length];
    private static FormattedCharSequence offline = FormattedCharSequence.EMPTY;
    private static FormattedCharSequence stopped = FormattedCharSequence.EMPTY;
    private static FormattedCharSequence noNotices = FormattedCharSequence.EMPTY;
    private static @Nullable Language language;

    private ScreenPainter() {
    }

    /**
     * Starts a frame: the quads of the last frame are drawn by now and are reused. Call once per frame before any
     * {@link #submit} (Storage's client glue does it in the level extract event).
     */
    public static void beginFrame() {
        used = 0;
    }

    private static Quads obtain() {
        if (used == POOL.size()) {
            POOL.add(new Quads());
        }
        Quads quads = POOL.get(used++);
        quads.clear();
        return quads;
    }

    /** Translated texts are cached until the language changes (a resource reload makes a new {@link Language}). */
    // Identity says whether the language is the one the texts were made for.
    @SuppressWarnings("ReferenceEquality")
    private static void refreshLanguage() {
        Language current = Language.getInstance();
        if (current == language) {
            return;
        }
        language = current;
        for (DisplaySourceKind source : DisplaySourceKind.values()) {
            SOURCE_NAMES[source.id()] = Component.translatable(source.translationKey()).getVisualOrderText();
        }
        offline = Component.translatable(DisplayContent.OFFLINE_KEY).getVisualOrderText();
        stopped = Component.translatable(DisplayContent.STOPPED_KEY).getVisualOrderText();
        noNotices = Component.translatable(DisplayContent.NO_NOTICES_KEY).getVisualOrderText();
        generation++;
    }

    private static int generation;

    private static FormattedCharSequence sourceName(DisplaySourceKind source) {
        return SOURCE_NAMES[source.id()];
    }

    /** The value with its unit, made again only when the value, source or language changed. */
    private static FormattedCharSequence valueText(DisplayView view, DisplaySourceKind source) {
        Object cached = view.cachedText(view.value(), source.id(), generation);
        if (cached instanceof FormattedCharSequence text) {
            return text;
        }
        FormattedCharSequence text = Component.translatable(source.unitKey(), AmountFormat.compact(view.value()))
                .getVisualOrderText();
        view.cacheText(text, view.value(), source.id(), generation);
        return text;
    }

    /**
     * Queues the picture of {@code view} with {@code settings} on {@code frame}. {@code time} is in ticks (game time
     * plus partial tick) and only moves the scanline. Draws nothing before the screen's first update.
     */
    public static void submit(PoseStack pose, SubmitNodeCollector collector, Font font, Frame frame, DisplayView view,
            DisplaySettings settings, float time) {
        if (view.status() == DisplayUpdate.NO_DATA) {
            return;
        }
        pose.pushPose();
        pose.translate(frame.x(), frame.y(), frame.z());
        pose.mulPose(ROTATIONS[frame.facing().get3DDataValue()]);
        float w = frame.width();
        float h = frame.height();
        float small = Math.min(w, h);
        // The picture's border sits just inside the model's frame, at the same inset on every wall size.
        float margin = frame.bezel() + BORDER_GAP;
        // Where bars and graphs start, a little further in.
        float inner = margin + 0.05F * small;
        int accent = settings.accentColour();
        Quads quads = obtain();
        // The frame and a scanline that sweeps up the screen every ten seconds (client config visuals.effects).
        float edge = Math.max(0.01F, 0.012F * small);
        quads.rect(margin, margin, w - margin, margin + edge, alpha(accent, 0x90));
        quads.rect(margin, h - margin - edge, w - margin, h - margin, alpha(accent, 0x90));
        quads.rect(margin, margin, margin + edge, h - margin, alpha(accent, 0x90));
        quads.rect(w - margin - edge, margin, w - margin, h - margin, alpha(accent, 0x90));
        if (StorageClientConfig.effects()) {
            float sweep = margin + (time % 200F) / 200F * (h - 2 * margin - 0.04F * h);
            quads.rect(margin + edge, sweep, w - margin - edge, sweep + 0.04F * h, alpha(accent, 0x14));
        }

        float label = Math.clamp(0.09F * small, 0.07F, 0.3F);
        float top = h - margin - label * 1.6F;
        refreshLanguage();
        if (settings.source() == DisplaySourceKind.TEXT) {
            richText(pose, collector, font, view, settings, frame.bezel(), w, h, accent);
            if (view.status() == DisplayUpdate.OFFLINE) {
                text(pose, collector, font, offline, w / 2, margin + label * 0.2F, label * 0.7F, OFFLINE_COLOUR, true,
                        w);
            }
            collector.submitCustomGeometry(pose, RenderTypes.debugQuads(), quads);
            pose.popPose();
            return;
        }
        if (settings.source().rows()) {
            rows(pose, collector, font, quads, view, frame.bezel(), w, h, accent, (long) time);
            if (view.status() != DisplayUpdate.ONLINE) {
                FormattedCharSequence status = view.status() == DisplayUpdate.OFFLINE ? offline : stopped;
                text(pose, collector, font, status, w / 2, margin + label * 0.2F, label * 0.7F, OFFLINE_COLOUR, true,
                        w);
            }
            collector.submitCustomGeometry(pose, RenderTypes.debugQuads(), quads);
            pose.popPose();
            return;
        }
        if (settings.source() == DisplaySourceKind.HISTORY) {
            historyChart(pose, collector, font, quads, view, settings, margin, inner, w, h, label, accent, small);
            collector.submitCustomGeometry(pose, RenderTypes.debugQuads(), quads);
            pose.popPose();
            return;
        }
        text(pose, collector, font, sourceName(settings.source()), margin + label * 0.6F, top, label,
                alpha(accent, 0xC0), false, w);
        if (view.status() != DisplayUpdate.ONLINE) {
            FormattedCharSequence status = view.status() == DisplayUpdate.OFFLINE ? offline : stopped;
            text(pose, collector, font, status, w / 2, margin + label * 0.6F, label, OFFLINE_COLOUR, true, w);
        }
        float graphTop = top - label * 0.5F;
        float graphBottom = margin + label * 2.2F;
        if (settings.source() == DisplaySourceKind.LOGIC_NOTICES) {
            notices(pose, collector, font, view, margin, graphTop, w, label, accent, (long) time);
            collector.submitCustomGeometry(pose, RenderTypes.debugQuads(), quads);
            pose.popPose();
            return;
        }
        FormattedCharSequence shown = valueText(view, settings.source());
        if (settings.mode() == DisplayMode.VALUE) {
            float big = Math.min(0.32F * h, (w - 2 * margin) * 0.8F / Math.max(1, font.width(shown)) * font.lineHeight);
            text(pose, collector, font, shown, w / 2, (h - big) / 2 + big * 0.1F, big, accent, true, w);
            if (view.max() > 0) {
                float fraction = (float) Math.clamp((double) view.value() / view.max(), 0, 1);
                float barTop = graphBottom;
                float barHeight = Math.max(0.03F, 0.06F * small);
                quads.rect(inner, barTop, w - inner, barTop + barHeight, alpha(accent, 0x30));
                quads.rect(inner, barTop, inner + (w - inner * 2) * fraction, barTop + barHeight, alpha(accent, 0xB0));
            }
        } else {
            graph(quads, view, settings.mode(), inner, graphBottom, w - inner, graphTop, accent, small,
                    settings.source().hasMax() ? view.max() : 0);
            text(pose, collector, font, shown, w - margin - label * 0.6F, top, label, accent, false, -1);
        }
        collector.submitCustomGeometry(pose, RenderTypes.debugQuads(), quads);
        pose.popPose();
    }

    /**
     * The newest logic notices from the last {@link DisplayView#NOTICE_TICKS} ticks, one line each, newest at the
     * bottom; as many as fit below {@code areaTop}, at most {@link DisplayView#NOTICE_LINES}. A line too long for the
     * screen is cut. With none, a dim "No recent notices". Lines are laid out once per notice list, language and screen
     * width.
     */
    private static void notices(PoseStack pose, SubmitNodeCollector collector, Font font, DisplayView view,
            float margin, float areaTop, float w, float label, int accent, long gameTime) {
        List<NetworkNotices.Notice> notices = view.notices();
        float x = margin + label * 0.6F;
        int maxPixels = Math.max(1, (int) ((w - 2 * x) / (label / font.lineHeight)));
        int key = generation * 31 + maxPixels;
        Object[] lines;
        if (view.cachedNotices(key) instanceof Object[] cached && cached.length == notices.size()) {
            lines = cached;
        } else {
            lines = new Object[notices.size()];
            for (int i = 0; i < lines.length; i++) {
                NetworkNotices.Notice notice = notices.get(i);
                if (notice.key().isEmpty()) {
                    // A player's own words: display markup, one row.
                    lines[i] = RichLayout.build(RichText.parse(notice.text()), n -> "", font, maxPixels, 1);
                } else {
                    List<FormattedCharSequence> split = font.split(notice.line(), maxPixels);
                    lines[i] = split.isEmpty() ? FormattedCharSequence.EMPTY : split.getFirst();
                }
            }
            view.cacheNotices(lines, key);
        }
        float step = label * 1.5F;
        int room = Math.max(1, Math.min(DisplayView.NOTICE_LINES, (int) ((areaTop - margin) / step)));
        float y = margin + label * 0.6F;
        int shown = 0;
        for (int i = notices.size() - 1; i >= 0 && shown < room; i--) {
            if (gameTime - notices.get(i).gameTime() > DisplayView.NOTICE_TICKS) {
                break;
            }
            int colour = alpha(accent, shown == 0 ? 0xFF : 0xA0);
            if (lines[i] instanceof RichLayout layout) {
                float pixel = label / font.lineHeight;
                if (!layout.rows().isEmpty()) {
                    RichLayout.Row row = layout.rows().getFirst();
                    // Every notice line is one label high, whatever size its markup asks for.
                    float offset = switch (row.align()) {
                        case LEFT -> 0;
                        case CENTER -> Math.max(0, (maxPixels - row.width()) / 2F);
                        case RIGHT -> Math.max(0, maxPixels - row.width());
                    };
                    drawRow(pose, collector, row, x + offset * pixel, y + shown * step + label, pixel, colour);
                }
            } else if (lines[i] instanceof FormattedCharSequence line) {
                text(pose, collector, font, line, x, y + shown * step, label, colour, false, 0);
            }
            shown++;
        }
        if (shown == 0) {
            text(pose, collector, font, noNotices, w / 2, (areaTop + margin) / 2 - label / 2, label,
                    alpha(accent, 0x80), true, w);
        }
    }

    /** Font pixels a row's short history takes, and a gap's. */
    private static final int SPARK_PIXELS = 40;
    private static final int GAP_PIXELS = 4;

    /** Row colours by tone ({@link ScreenRows#NORMAL} takes the accent). */
    private static int toneColour(int tone, int accent) {
        return switch (tone) {
            case ScreenRows.DIM -> StatusPalette.dim();
            case ScreenRows.GOOD -> StatusPalette.good();
            case ScreenRows.WARN -> StatusPalette.warn();
            case ScreenRows.BAD -> StatusPalette.bad();
            default -> accent;
        };
    }

    /** The glyph that backs a row's tone colour, so colour is never the only signal; none for plain and dim rows. */
    private static String toneMark(int tone) {
        return switch (tone) {
            case ScreenRows.GOOD -> StatusPalette.mark(StatusPalette.Tone.GOOD);
            case ScreenRows.WARN -> StatusPalette.mark(StatusPalette.Tone.WARN);
            case ScreenRows.BAD -> StatusPalette.mark(StatusPalette.Tone.BAD);
            default -> "";
        };
    }

    /** One row laid out once: its texts cut to fit, and its icon's model. */
    private record LaidRow(ScreenRows.Row row, FormattedCharSequence text, FormattedCharSequence note, int noteWidth,
            @Nullable ItemStackRenderState model, ItemStack icon) {
    }

    /** A list screen laid out for one size, language, set of rows and minute (ages change by the minute). */
    private record LaidRows(FormattedCharSequence title, FormattedCharSequence status, List<LaidRow> rows) {
    }

    /** {@code ticks} as a short age: now, minutes, hours or days. */
    private static Component age(long ticks) {
        long minutes = Math.max(0, ticks) / 1200;
        if (minutes < 1) {
            return Component.translatable(DisplayContent.AGE_NOW_KEY);
        }
        if (minutes < 60) {
            return Component.translatable(DisplayContent.AGE_MINUTES_KEY, minutes);
        }
        long hours = minutes / 60;
        return hours < 48
                ? Component.translatable(DisplayContent.AGE_HOURS_KEY, hours)
                : Component.translatable(DisplayContent.AGE_DAYS_KEY, hours / 24);
    }

    private static LaidRows layRows(ScreenRows rows, Font font, int pixels, int shown, long gameTime) {
        FormattedCharSequence status = rows.status().getVisualOrderText();
        int statusWidth = font.width(status);
        List<FormattedCharSequence> title = font.split(rows.title(),
                Math.max(1, pixels - statusWidth - GAP_PIXELS * 2));
        LaidRow[] laid = new LaidRow[Math.min(shown, rows.rows().size())];
        for (int i = 0; i < laid.length; i++) {
            ScreenRows.Row row = rows.rows().get(i);
            Component noteText = row.note();
            if (row.time() != ScreenRows.NO_TIME) {
                Component age = age(gameTime - row.time());
                noteText = row.note().getString().isEmpty()
                        ? age
                        : Component.translatable(DisplayContent.NOTE_AGE_KEY, row.note(), age);
            }
            FormattedCharSequence note = noteText.getVisualOrderText();
            int noteWidth = font.width(note);
            int used = noteWidth + GAP_PIXELS;
            if (row.has(ScreenRows.BOX)) {
                used += font.lineHeight + 2;
            }
            if (row.icon().isPresent()) {
                used += RichLayout.ICON + 2;
            }
            if (!row.spark().isEmpty()) {
                used += SPARK_PIXELS + GAP_PIXELS;
            }
            Component text = row.has(ScreenRows.STRIKE)
                    ? Component.empty().append(row.text()).withStyle(ChatFormatting.STRIKETHROUGH)
                    : row.text();
            String mark = toneMark(row.tone());
            if (!mark.isEmpty()) {
                text = Component.literal(mark).append(text);
            }
            List<FormattedCharSequence> split = font.split(text, Math.max(1, pixels - used));
            ItemStack icon = row.icon().isPresent() ? new ItemStack(row.icon().get()) : ItemStack.EMPTY;
            laid[i] = new LaidRow(row, split.isEmpty() ? FormattedCharSequence.EMPTY : split.getFirst(), note,
                    noteWidth, icon.isEmpty() ? null : new ItemStackRenderState(), icon);
        }
        return new LaidRows(title.isEmpty() ? FormattedCharSequence.EMPTY : title.getFirst(), status, List.of(laid));
    }

    /**
     * A list screen ({@link ScreenRows}): the title and status on top with the header's bar, then as many rows as fit
     * ({@link ScreenRows#fit}), each with its band, checkbox, icon, text, short history, note and bar. Positions come
     * from {@link ScreenRows}'s layout, which the server's click lookup uses too. Laid out once per rows, size,
     * language and minute.
     */
    private static void rows(PoseStack pose, SubmitNodeCollector collector, Font font, Quads quads, DisplayView view,
            float bezel, float w, float h, int accent, long gameTime) {
        ScreenRows rows = view.rows();
        float label = ScreenRows.label(w, h);
        float margin = ScreenRows.margin(bezel);
        float x0 = margin + label * 0.5F;
        float x1 = w - margin - label * 0.5F;
        float pixel = label / font.lineHeight;
        int pixels = Math.max(1, (int) ((x1 - x0) / pixel));
        int shown = ScreenRows.fit(w, h, bezel);
        boolean ages = false;
        for (int i = 0; i < rows.rows().size() && !ages; i++) {
            ages = rows.rows().get(i).time() != ScreenRows.NO_TIME;
        }
        int key = (((generation * 31 + view.rowsVersion()) * 31 + Float.floatToIntBits(w)) * 31
                + Float.floatToIntBits(h)) * 31 + (ages ? (int) (gameTime / 1200) : 0);
        LaidRows laid;
        if (view.cachedRows(key) instanceof LaidRows cached) {
            laid = cached;
        } else {
            laid = layRows(rows, font, pixels, shown, gameTime);
            view.cacheRows(laid, key);
        }
        float titleBottom = ScreenRows.titleBottom(w, h, bezel);
        text(pose, collector, font, laid.title(), x0, titleBottom, label, alpha(accent, 0xC0), false, 0);
        text(pose, collector, font, laid.status(), x1, titleBottom, label, accent, false, -1);
        if (rows.fill() != ScreenRows.NO_FILL) {
            float barTop = titleBottom - label * 0.3F;
            float barBottom = barTop - label * 0.2F;
            quads.rect(x0, barBottom, x1, barTop, alpha(accent, 0x30));
            quads.rect(x0, barBottom, x0 + (x1 - x0) * rows.fill() / 1000F, barTop, alpha(accent, 0xB0));
        }
        float step = ScreenRows.step(label);
        for (int i = 0; i < laid.rows().size(); i++) {
            LaidRow row = laid.rows().get(i);
            int colour = toneColour(row.row().tone(), accent);
            float top = ScreenRows.rowTop(w, h, bezel, i);
            float textBottom = top - label * 1.05F;
            if (row.row().has(ScreenRows.BAND)) {
                quads.rect(margin + label * 0.15F, top - step + label * 0.04F, w - margin - label * 0.15F,
                        top - label * 0.04F, alpha(colour, 0x30));
                quads.rect(margin + label * 0.15F, top - step + label * 0.04F, margin + label * 0.3F,
                        top - label * 0.04F, alpha(colour, 0xE0));
            }
            float x = x0;
            if (row.row().has(ScreenRows.BOX)) {
                float size = label * 0.75F;
                float line = label * 0.1F;
                float boxBottom = textBottom + label * 0.05F;
                int edge = alpha(colour, 0xD0);
                quads.rect(x, boxBottom, x + size, boxBottom + line, edge);
                quads.rect(x, boxBottom + size - line, x + size, boxBottom + size, edge);
                quads.rect(x, boxBottom, x + line, boxBottom + size, edge);
                quads.rect(x + size - line, boxBottom, x + size, boxBottom + size, edge);
                if (row.row().has(ScreenRows.CHECKED)) {
                    quads.rect(x + line * 2, boxBottom + line * 2, x + size - line * 2, boxBottom + size - line * 2,
                            colour);
                }
                x += (font.lineHeight + 2) * pixel;
            }
            ItemStackRenderState model = row.model();
            if (model != null) {
                Minecraft minecraft = Minecraft.getInstance();
                // Resolved each frame, as the rich text's icons, so a resource reload never leaves a stale model.
                minecraft.getItemModelResolver().updateForTopItem(model, row.icon(), ItemDisplayContext.GUI,
                        minecraft.level, null, 0);
                float size = RichLayout.ICON * pixel;
                pose.pushPose();
                pose.translate(x + size / 2, textBottom + size / 2, 0.01F);
                pose.scale(size, size, size * 0.05F);
                model.submit(pose, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
                pose.popPose();
                x += (RichLayout.ICON + 2) * pixel;
            }
            text(pose, collector, font, row.text(), x, textBottom, label, colour, false, 0);
            text(pose, collector, font, row.note(), x1, textBottom, label, alpha(colour, 0xB0), false, -1);
            List<Long> spark = row.row().spark();
            if (!spark.isEmpty()) {
                float sparkRight = x1 - (row.noteWidth() + GAP_PIXELS) * pixel;
                float slot = SPARK_PIXELS * pixel / ScreenRows.MAX_SPARK;
                float start = sparkRight - slot * spark.size();
                long peak = 1;
                for (int s = 0; s < spark.size(); s++) {
                    peak = Math.max(peak, spark.get(s));
                }
                for (int s = 0; s < spark.size(); s++) {
                    float bar = Math.max(label * 0.08F, label * 0.9F * Math.max(0, spark.get(s)) / peak);
                    float left = start + slot * s;
                    quads.rect(left, textBottom, left + slot * 0.75F, textBottom + bar,
                            alpha(colour, s == spark.size() - 1 ? 0xF0 : 0xA0));
                }
            }
            if (row.row().fill() != ScreenRows.NO_FILL) {
                float barTop = top - label * 1.18F;
                float barBottom = top - label * 1.3F;
                quads.rect(x, barBottom, x1, barTop, alpha(colour, 0x30));
                quads.rect(x, barBottom, x + (x1 - x) * row.row().fill() / 1000F, barTop, alpha(colour, 0xB0));
            }
        }
    }

    /** Distance in blocks from the screen's edge to rich text: the frame, the picture's border and a gap. */
    private static float textInset(float bezel) {
        return bezel + BORDER_GAP + TEXT_GAP;
    }

    /** Font pixels per block of normal-size rich text on a single panel (a line about a ninth of a block high). */
    private static final float TEXT_PIXEL = 0.011F;

    /** The rich text shown for a placeholder: its value from the server, or a dash before the first. */
    private static String placeholder(DisplayView view, int index) {
        LongList values = view.values();
        return index < values.size() ? AmountFormat.compact(values.getLong(index)) : "-";
    }

    /**
     * The screen's rich text, top-aligned inside the frame: normal text about a ninth of a block high, wrapped to the
     * screen's width; text too tall for the screen is laid out smaller (down to a third) and what still does not fit is
     * cut at the bottom. The layout is kept in the view until the text, the screen's size, the language or the values
     * change.
     */
    private static void richText(PoseStack pose, SubmitNodeCollector collector, Font font, DisplayView view,
            DisplaySettings settings, float bezel, float w, float h, int accent) {
        if (settings.text().isEmpty()) {
            return;
        }
        int key = ((generation * 31 + view.valuesVersion()) * 31 + Float.floatToIntBits(w)) * 31
                + Float.floatToIntBits(h);
        Fitted fitted = view.cachedLayout(settings.text(), key) instanceof Fitted cached ? cached : null;
        if (fitted == null) {
            fitted = fit(DisplayText.parse(settings.text()), n -> placeholder(view, n), font, w, h, bezel);
            view.cacheLayout(fitted, settings.text(), key);
        }
        RichLayout layout = fitted.layout();
        float pixel = fitted.pixel();
        float inset = textInset(bezel);
        for (int r = 0; r < fitted.shownRows(); r++) {
            RichLayout.Row row = layout.rows().get(r);
            drawRow(pose, collector, row, inset + layout.left(row) * pixel, h - inset - row.top() * pixel,
                    pixel * row.scale(), accent);
        }
    }

    /**
     * A rich text layout fitted to a screen: {@code pixel} blocks per normal-size font pixel, the text area's size in
     * those pixels, and how many rows fit in it.
     */
    public record Fitted(RichLayout layout, float pixel, float areaWidth, float areaHeight, int shownRows) {
    }

    /**
     * Lays {@code text} out for a screen {@code w} by {@code h} blocks with a model frame {@code bezel} blocks wide
     * ({@link #PANEL_BEZEL} or {@link #PART_BEZEL}) as the world draws it: inside the frame, normal text about a ninth
     * of a block high, smaller in steps (to about a third) while it is too tall; the rows that still do not fit are
     * left out. The editor's preview uses it too, so it wraps exactly like the wall.
     */
    public static Fitted fit(RichText text, IntFunction<String> value, Font font, float w, float h, float bezel) {
        float inset = textInset(bezel);
        float areaW = Math.max(0.01F, w - 2 * inset);
        float areaH = Math.max(0.01F, h - 2 * inset);
        float pixel = TEXT_PIXEL * Math.min(1, Math.min(w, h));
        RichLayout layout = RichLayout.build(text, value, font, (int) (areaW / pixel), 0);
        for (int i = 0; i < 6 && layout.height() * pixel > areaH; i++) {
            pixel *= 0.82F;
            layout = RichLayout.build(text, value, font, (int) (areaW / pixel), 0);
        }
        float room = areaH / pixel + 0.01F;
        int shown = 0;
        while (shown < layout.rows().size()
                && layout.rows().get(shown).top() + layout.rows().get(shown).height() <= room) {
            shown++;
        }
        return new Fitted(layout, pixel, areaW / pixel, areaH / pixel, shown);
    }

    /**
     * Draws one row with its left edge at {@code left} and its top at {@code top} (blocks in the screen's plane),
     * {@code pixel} blocks per row pixel; text without a colour of its own takes {@code colour}.
     */
    private static void drawRow(PoseStack pose, SubmitNodeCollector collector, RichLayout.Row row, float left,
            float top, float pixel, int colour) {
        List<RichLayout.Piece> pieces = row.pieces();
        for (int i = 0; i < pieces.size(); i++) {
            RichLayout.Piece piece = pieces.get(i);
            float x = left + piece.x * pixel;
            FormattedCharSequence text = piece.text;
            ItemStackRenderState model = piece.model;
            if (text != null) {
                pose.pushPose();
                pose.translate(x, top, 0.003F);
                pose.scale(pixel, -pixel, pixel);
                collector.submitText(pose, 0, 0, text, false, Font.DisplayMode.POLYGON_OFFSET,
                        LightCoordsUtil.FULL_BRIGHT, colour, 0, 0);
                pose.popPose();
            } else if (model != null) {
                Minecraft minecraft = Minecraft.getInstance();
                // Resolved each frame, so a resource reload never leaves a stale model behind; cheap for an icon.
                minecraft.getItemModelResolver().updateForTopItem(model, piece.icon, ItemDisplayContext.GUI,
                        minecraft.level, null, 0);
                float size = RichLayout.ICON * pixel;
                pose.pushPose();
                pose.translate(x + size / 2, top - size / 2, 0.01F);
                // Flat on the screen: the GUI model's depth squashed to a twentieth.
                pose.scale(size, size, size * 0.05F);
                model.submit(pose, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
                pose.popPose();
            }
        }
    }

    /**
     * A history chart's texts, made once per chart, value, state and language: the value reference as the title, the
     * newest value, the window, the lowest and highest point, and the state line (empty while recording with points).
     */
    private record ChartLabels(int version, int generation, long value, long state, String reference,
            FormattedCharSequence title, FormattedCharSequence current, FormattedCharSequence window,
            FormattedCharSequence min, FormattedCharSequence max, FormattedCharSequence status, boolean hasStatus,
            long low, long high) {
    }

    /** {@code value} shortened like a count, with its sign. */
    private static String signed(long value) {
        return value < 0 ? "-" + AmountFormat.compact(-value) : AmountFormat.compact(value);
    }

    // The reference is the settings' own string, replaced and never changed, so identity says whether it is the same.
    @SuppressWarnings("ReferenceEquality")
    private static ChartLabels chartLabels(DisplayView view, DisplaySettings settings) {
        String reference = settings.chart().value();
        if (view.cachedChartLabels(0) instanceof ChartLabels labels && labels.version() == view.historyVersion()
                && labels.generation() == generation && labels.value() == view.value() && labels.state() == view.max()
                && labels.reference() == reference) {
            return labels;
        }
        long low = Long.MAX_VALUE;
        long high = Long.MIN_VALUE;
        for (int i = 0; i < view.samples(); i++) {
            long point = view.sampleAt(i);
            if (point != ValueRing.NO_VALUE) {
                low = Math.min(low, point);
                high = Math.max(high, point);
            }
        }
        boolean any = low <= high;
        String statusKey = view.max() == ChartRecorder.LIMIT
                ? DisplayContent.CHART_LIMIT_KEY
                : view.max() == ChartRecorder.UNSET
                        ? DisplayContent.CHART_UNSET_KEY
                        : any ? "" : DisplayContent.CHART_WAITING_KEY;
        FormattedCharSequence empty = FormattedCharSequence.EMPTY;
        ChartLabels labels = new ChartLabels(view.historyVersion(), generation, view.value(), view.max(), reference,
                Component.literal(reference).getVisualOrderText(),
                view.value() == ValueRing.NO_VALUE || view.max() != ChartRecorder.RECORDING
                        ? empty
                        : Component.literal(signed(view.value())).getVisualOrderText(),
                Component.translatable(DisplayContent.CHART_WINDOW_KEY, settings.chart().windowHours())
                        .getVisualOrderText(),
                any ? Component.translatable(DisplayContent.CHART_MIN_KEY, signed(low)).getVisualOrderText() : empty,
                any ? Component.translatable(DisplayContent.CHART_MAX_KEY, signed(high)).getVisualOrderText() : empty,
                statusKey.isEmpty() ? empty : Component.translatable(statusKey).getVisualOrderText(),
                !statusKey.isEmpty(), any ? low : 0, any ? high : 0);
        view.cacheChartLabels(labels, 0);
        return labels;
    }

    /**
     * A {@link DisplaySourceKind#HISTORY} screen: the value reference and the newest value on top, a grid, the recorded
     * points as a line scaled between their lowest and highest (gaps left open), the lowest and highest on the left and
     * the window under the chart; a state line instead of the chart while nothing is recorded.
     */
    private static void historyChart(PoseStack pose, SubmitNodeCollector collector, Font font, Quads quads,
            DisplayView view, DisplaySettings settings, float margin, float inner, float w, float h, float label,
            int accent, float small) {
        ChartLabels labels = chartLabels(view, settings);
        float top = h - margin - label * 1.6F;
        text(pose, collector, font, labels.title(), margin + label * 0.6F, top, label, alpha(accent, 0xC0), false,
                w * 0.6F);
        text(pose, collector, font, labels.current(), w - margin - label * 0.6F, top, label, accent, false, -1);
        float x0 = inner;
        float x1 = w - inner;
        float y0 = margin + label * 1.6F;
        float y1 = top - label * 0.5F;
        text(pose, collector, font, labels.window(), w - margin - label * 0.6F, margin + label * 0.3F, label * 0.8F,
                alpha(accent, 0x90), false, -1);
        if (y1 <= y0 || x1 <= x0) {
            return;
        }
        // The grid: four rows and six columns, faint.
        float line = Math.max(0.004F, 0.006F * small);
        for (int r = 0; r <= 4; r++) {
            float y = y0 + (y1 - y0) * r / 4F;
            quads.rect(x0, y - line / 2, x1, y + line / 2, alpha(accent, r == 0 ? 0x50 : 0x20));
        }
        for (int c = 0; c <= 6; c++) {
            float x = x0 + (x1 - x0) * c / 6F;
            quads.rect(x - line / 2, y0, x + line / 2, y1, alpha(accent, 0x20));
        }
        if (labels.hasStatus()) {
            text(pose, collector, font, labels.status(), w / 2, (y0 + y1) / 2 - label / 2, label,
                    view.max() == ChartRecorder.LIMIT ? OFFLINE_COLOUR : alpha(accent, 0xC0), true, w);
            return;
        }
        int count = view.samples();
        double low = labels.low();
        double span = Math.max(1, (double) labels.high() - low);
        float step = (x1 - x0) / Math.max(1, count);
        float thickness = Math.max(0.008F, 0.014F * small);
        boolean drawn = false;
        float lastX = 0;
        float lastY = 0;
        for (int i = 0; i < count; i++) {
            long point = view.sampleAt(i);
            if (point == ValueRing.NO_VALUE) {
                drawn = false;
                continue;
            }
            float x = x0 + step * (i + 0.5F);
            // A flat line sits in the middle rather than on the floor.
            float y = labels.high() == labels.low()
                    ? (y0 + y1) / 2
                    : y0 + (float) ((point - low) / span * (y1 - y0) * 0.9F + (y1 - y0) * 0.05F);
            quads.rect(x - step * 0.5F, y0, x + step * 0.5F, y, alpha(accent, 0x22));
            if (drawn) {
                quads.segment(lastX, lastY, x, y, thickness, alpha(accent, 0xE0));
            } else {
                quads.rect(x - thickness / 2, y - thickness / 2, x + thickness / 2, y + thickness / 2,
                        alpha(accent, 0xE0));
            }
            drawn = true;
            lastX = x;
            lastY = y;
        }
        text(pose, collector, font, labels.max(), x0 + label * 0.2F, y1 - label * 0.85F, label * 0.7F,
                alpha(accent, 0xB0), false, w * 0.4F);
        text(pose, collector, font, labels.min(), x0 + label * 0.2F, y0 + label * 0.15F, label * 0.7F,
                alpha(accent, 0xB0), false, w * 0.4F);
    }

    /** The window's points as a line with a faint fill under it, or as bars, scaled to the peak or the maximum. */
    private static void graph(Quads quads, DisplayView view, DisplayMode mode, float x0, float y0, float x1, float y1,
            int accent, float small, long max) {
        int count = view.samples();
        if (count == 0 || y1 <= y0) {
            return;
        }
        double scale = Math.max(1, max > 0 ? max : view.peak());
        float step = (x1 - x0) / DisplayView.SAMPLES;
        // The newest point sits at the right edge; a window not yet full grows from the right.
        float start = x1 - step * count;
        float thickness = Math.max(0.008F, 0.014F * small);
        float lastX = 0;
        float lastY = 0;
        for (int i = 0; i < count; i++) {
            float x = start + step * (i + 0.5F);
            float y = y0 + (float) (Math.clamp(view.sampleAt(i) / scale, 0, 1) * (y1 - y0));
            if (mode == DisplayMode.BAR) {
                quads.rect(x - step * 0.35F, y0, x + step * 0.35F, Math.max(y, y0 + thickness),
                        alpha(accent, 0x60 + 0x80 * (i + 1) / count));
            } else {
                quads.rect(x - step * 0.5F, y0, x + step * 0.5F, y, alpha(accent, 0x22));
                if (i > 0) {
                    quads.segment(lastX, lastY, x, y, thickness, alpha(accent, 0xE0));
                }
            }
            lastX = x;
            lastY = y;
        }
    }

    /**
     * Draws {@code text} {@code height} blocks tall with its left edge (or centre) at {@code x} and its bottom at
     * {@code y}, shrunk to fit {@code maxWidth} blocks when that is positive; right-aligned at {@code x} with a
     * negative {@code maxWidth}.
     */
    private static void text(PoseStack pose, SubmitNodeCollector collector, Font font, FormattedCharSequence sequence,
            float x, float y, float height, int colour, boolean centred, float maxWidth) {
        int pixels = Math.max(1, font.width(sequence));
        float scale = height / font.lineHeight;
        if (maxWidth > 0 && pixels * scale > maxWidth * 0.85F) {
            scale = maxWidth * 0.85F / pixels;
        }
        float left = centred ? x - pixels * scale / 2 : maxWidth < 0 ? x - pixels * scale : x;
        pose.pushPose();
        pose.translate(left, y + font.lineHeight * scale, 0.003F);
        pose.scale(scale, -scale, scale);
        collector.submitText(pose, 0, 0, sequence, false, Font.DisplayMode.POLYGON_OFFSET, LightCoordsUtil.FULL_BRIGHT,
                colour, 0, 0);
        pose.popPose();
    }

    private static int alpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0xFFFFFF);
    }

    /** The screen's quads, recorded on the render thread and written when the collector draws its batch. */
    private static final class Quads implements SubmitNodeCollector.CustomGeometryRenderer {
        /** Four corners (x, y) per quad, counter-clockwise as the viewer sees them. */
        private final FloatArrayList corners = new FloatArrayList();
        private final IntArrayList colours = new IntArrayList();

        void clear() {
            corners.clear();
            colours.clear();
        }

        void rect(float x0, float y0, float x1, float y1, int colour) {
            quad(x0, y0, x1, y0, x1, y1, x0, y1, colour);
        }

        /** A line from (x0, y0) to (x1, y1) {@code thickness} wide. */
        void segment(float x0, float y0, float x1, float y1, float thickness, int colour) {
            float dx = x1 - x0;
            float dy = y1 - y0;
            float length = (float) Math.sqrt(dx * dx + dy * dy);
            if (length <= 0) {
                return;
            }
            float nx = -dy / length * thickness / 2;
            float ny = dx / length * thickness / 2;
            quad(x0 - nx, y0 - ny, x1 - nx, y1 - ny, x1 + nx, y1 + ny, x0 + nx, y0 + ny, colour);
        }

        private void quad(float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy, int colour) {
            corners.add(ax);
            corners.add(ay);
            corners.add(bx);
            corners.add(by);
            corners.add(cx);
            corners.add(cy);
            corners.add(dx);
            corners.add(dy);
            colours.add(colour);
        }

        @Override
        public void render(PoseStack.Pose pose, VertexConsumer buffer) {
            for (int q = 0; q < colours.size(); q++) {
                int colour = colours.getInt(q);
                for (int v = 0; v < 4; v++) {
                    buffer.addVertex(pose, corners.getFloat(q * 8 + v * 2), corners.getFloat(q * 8 + v * 2 + 1), 0.002F)
                            .setColor(colour);
                }
            }
        }
    }
}
