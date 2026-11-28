package dev.silencebound.maxtech.lib.client.menu;

import dev.silencebound.maxtech.lib.api.client.gui.Accent;
import dev.silencebound.maxtech.lib.api.menu.Layout;
import dev.silencebound.maxtech.lib.api.menu.MenuValueView;
import dev.silencebound.maxtech.lib.api.menu.PortValue;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.style.AccentTheme;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import dev.silencebound.maxtech.lib.api.transfer.RelativeFace;
import dev.silencebound.maxtech.lib.menu.DefinedMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A {@link Layout.Faces} node on screen: the block preview on top, then one block net of face buttons per kind, named
 * relative to the host's facing ({@link RelativeFace}) or absolutely without one. Faces show their mode in colour (push
 * orange, pull blue, off untinted); a left click asks the server for the next mode the kind allows, a right click for
 * the previous one. The preview turns with a left drag, shows the modes of the kind last hovered (the first at the
 * start), and a click on one of its faces works like that face's button. Every face's tooltip names it, its kind and
 * mode; the widget narrates the hovered face. Client thread.
 */
final class FacesWidget extends AbstractWidget {
    /** The colour of a pushing face. */
    static final int PUSH = 0xFFE0802A;
    /** The colour of a pulling face. */
    static final int PULL = 0xFF2F82D8;
    /** Pixels a press may move and still count as a click on the preview, not a drag. */
    private static final double CLICK_SLOP = 3.0;
    /** GUI pixels per block in the preview: its diagonal fits the preview's height. */
    private static final float PREVIEW_SCALE = Layout.FACES_PREVIEW / 1.8F;
    /** Where each relative face sits in a net, in cells: top above front, then left, front, right, back, bottom. */
    private static final int[][] CELLS = {{1, 1}, {3, 1}, {0, 1}, {2, 1}, {1, 0}, {1, 2}};
    private static final RelativeFace[] FACES = RelativeFace.values();

    private final Layout.Faces node;
    private final PortValue port;
    private final DefinedMenu<?> menu;
    private final AccentTheme theme;
    private float yaw;
    private float pitch;
    private int shownKind;
    private boolean turned;
    private double pressX;
    private double pressY;
    private boolean pressedPreview;

    FacesWidget(int x, int y, Layout.Faces node, DefinedMenu<?> menu, AccentTheme theme) {
        super(x, y, node.width(), node.height(), Component.translatable(FaceTexts.PREVIEW_KEY));
        this.node = node;
        this.port = node.port();
        this.menu = menu;
        this.theme = theme;
    }

    /** The colour a face in {@code mode} shows, or 0 for none. */
    static int tint(PortFaceMode mode) {
        return switch (mode) {
            case PUSH -> PUSH;
            case PULL -> PULL;
            case OFF -> 0;
        };
    }

    private MenuValueView values() {
        return menu.values();
    }

    /** The facing faces are named against: the host's, or north for one without (names are then absolute). */
    private Direction reference() {
        return port.facing(values()).orElse(Direction.NORTH);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!turned) {
            Direction facing = reference();
            yaw = FacePick.startYaw(facing);
            pitch = FacePick.startPitch(facing);
            turned = true;
        }
        Hit hit = hit(mouseX, mouseY);
        if (hit != null) {
            shownKind = hit.kind();
        }
        extractPreview(graphics, hit);
        for (int k = 0; k < node.kinds().size(); k++) {
            extractNet(graphics, k, hit, mouseX, mouseY);
        }
        if (hit != null) {
            graphics.setComponentTooltipForNextFrame(minecraft.font, tooltip(hit), mouseX, mouseY);
        }
    }

    private void extractPreview(GuiGraphicsExtractor graphics, @Nullable Hit hit) {
        int x = getX();
        int y = getY();
        graphics.fill(x, y, x + width, y + Layout.FACES_PREVIEW, Accent.SLOT_EDGE);
        graphics.fill(x + 1, y + 1, x + width - 1, y + Layout.FACES_PREVIEW - 1, Accent.SLOT);
        ResourceKind<?> kind = node.kinds().get(shownKind);
        int blockId = port.blockState(values()).map(net.minecraft.world.level.block.Block::getId).orElse(-1);
        int hovered = hit != null && hit.kind() == shownKind ? hit.face().get3DDataValue() : -1;
        graphics.submitPictureInPictureRenderState(
                new FacePreviewState(blockId, port.faces(values(), kind), hovered, yaw, pitch, x + 1, y + 1,
                        x + width - 1, y + Layout.FACES_PREVIEW - 1, PREVIEW_SCALE, graphics.peekScissorStack()));
    }

    private void extractNet(GuiGraphicsExtractor graphics, int k, @Nullable Hit hit, int mouseX, int mouseY) {
        ResourceKind<?> kind = node.kinds().get(k);
        int top = getY() + node.netY(k);
        Minecraft minecraft = Minecraft.getInstance();
        // The kind's name in the empty cells right of the top face.
        Accent.text(graphics, minecraft.font, kindName(kind), getX() + 2 * Layout.FACE + 2, top + 5,
                2 * Layout.FACE - 2, theme.text(), mouseX, mouseY);
        Set<PortFaceMode> allowed = port.allowed(values(), kind);
        Direction facing = reference();
        for (int f = 0; f < FACES.length; f++) {
            Direction face = FACES[f].toAbsolute(facing);
            int cx = getX() + CELLS[f][0] * Layout.FACE;
            int cy = top + CELLS[f][1] * Layout.FACE;
            boolean over = hit != null && hit.kind() == k && hit.face() == face && !hit.preview();
            PortFaceMode mode = port.mode(values(), kind, face);
            int colour = tint(mode);
            if (allowed.size() <= 1) {
                Accent.face(graphics, cx, cy, Layout.FACE, Layout.FACE, false, theme);
                graphics.fill(cx + 1, cy + 1, cx + Layout.FACE - 1, cy + Layout.FACE - 1, 0x80000000);
            } else if (colour == 0) {
                Accent.face(graphics, cx, cy, Layout.FACE, Layout.FACE, over, theme);
            } else {
                graphics.fill(cx, cy, cx + Layout.FACE, cy + Layout.FACE, over ? theme.accent() : Accent.SLOT_EDGE);
                graphics.fill(cx + 1, cy + 1, cx + Layout.FACE - 1, cy + Layout.FACE - 1, colour);
            }
        }
    }

    private Component kindName(ResourceKind<?> kind) {
        return Component.translatableWithFallback(FaceTexts.kindKey(kind), kind.id().getPath());
    }

    private Component faceName(Direction face) {
        Optional<Direction> facing = port.facing(values());
        if (facing.isEmpty()) {
            return Component.translatable(FaceTexts.directionKey(face));
        }
        return Component.translatable(FaceTexts.FACE_KEY,
                Component.translatable(FaceTexts.relativeKey(RelativeFace.of(facing.get(), face))),
                Component.translatable(FaceTexts.directionKey(face)));
    }

    private List<Component> tooltip(Hit hit) {
        ResourceKind<?> kind = node.kinds().get(hit.kind());
        List<Component> lines = new ArrayList<>();
        lines.add(faceName(hit.face()));
        lines.add(Component.translatable(FaceTexts.MODE_KEY, kindName(kind),
                Component.translatable(FaceTexts.modeKey(port.mode(values(), kind, hit.face())))));
        lines.add(Component
                .translatable(port.allowed(values(), kind).size() <= 1 ? FaceTexts.FIXED_KEY : FaceTexts.HINT_KEY));
        return lines;
    }

    /** What the mouse is over: a face of a kind, in a net or on the preview; null for nothing. */
    private @Nullable Hit hit(double mouseX, double mouseY) {
        double rx = mouseX - getX();
        double ry = mouseY - getY();
        if (rx < 0 || rx >= width || ry < 0 || ry >= height) {
            return null;
        }
        if (ry < Layout.FACES_PREVIEW) {
            Direction face = FacePick.pick(yaw, pitch, PREVIEW_SCALE, rx - width / 2.0,
                    ry - Layout.FACES_PREVIEW / 2.0);
            return face == null ? null : new Hit(shownKind, face, true);
        }
        Direction facing = reference();
        for (int k = 0; k < node.kinds().size(); k++) {
            double ny = ry - node.netY(k);
            if (ny < 0 || ny >= Layout.FACES_NET_HEIGHT) {
                continue;
            }
            int column = (int) (rx / Layout.FACE);
            int row = (int) (ny / Layout.FACE);
            for (int f = 0; f < FACES.length; f++) {
                if (CELLS[f][0] == column && CELLS[f][1] == row) {
                    return new Hit(k, FACES[f].toAbsolute(facing), false);
                }
            }
        }
        return null;
    }

    @Override
    protected boolean isValidClickButton(MouseButtonInfo info) {
        return info.button() == 0 || info.button() == 1;
    }

    @Override
    public void playDownSound(net.minecraft.client.sounds.SoundManager sounds) {
        // Played on a face click instead: a press that turns the preview makes no sound.
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        pressX = event.x();
        pressY = event.y();
        pressedPreview = event.y() - getY() < Layout.FACES_PREVIEW;
        if (event.button() == 1 || !pressedPreview) {
            ask(hit(event.x(), event.y()), event.button() == 1);
            pressedPreview = false;
        }
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        if (pressedPreview && event.button() == 0) {
            yaw += (float) dx * FacePick.DEGREES_PER_PIXEL;
            pitch = Math.clamp(pitch + (float) dy * FacePick.DEGREES_PER_PIXEL, -FacePick.MAX_PITCH,
                    FacePick.MAX_PITCH);
        }
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        if (pressedPreview && event.button() == 0 && Math.abs(event.x() - pressX) <= CLICK_SLOP
                && Math.abs(event.y() - pressY) <= CLICK_SLOP) {
            ask(hit(event.x(), event.y()), false);
        }
        pressedPreview = false;
    }

    /** Asks the server for the next (or previous) allowed mode of the hit face. */
    private void ask(@Nullable Hit hit, boolean back) {
        Minecraft minecraft = Minecraft.getInstance();
        if (hit == null || minecraft.gameMode == null) {
            return;
        }
        ResourceKind<?> kind = node.kinds().get(hit.kind());
        Set<PortFaceMode> allowed = port.allowed(values(), kind);
        PortFaceMode current = port.mode(values(), kind, hit.face());
        PortFaceMode wanted = back ? previous(current, allowed) : current.next(allowed);
        if (wanted == current) {
            return;
        }
        int id = menu.faceButton(port, kind, hit.face(), wanted);
        if (id >= 0) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
            playButtonClickSound(minecraft.getSoundManager());
        }
    }

    /** The mode before {@code mode} in the order off, push, pull among {@code allowed}. */
    static PortFaceMode previous(PortFaceMode mode, Set<PortFaceMode> allowed) {
        PortFaceMode step = mode;
        // With three modes, two steps forward are one step back.
        for (int i = 0; i < 2; i++) {
            PortFaceMode next = step.next(allowed);
            if (next == mode) {
                return step;
            }
            step = next;
        }
        return step;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }

    /** A face under the mouse: the kind's position, the absolute face, and whether it is on the preview. */
    private record Hit(int kind, Direction face, boolean preview) {
    }
}
