package dev.silencebound.maxtech.storage.display.client;

import dev.silencebound.maxtech.lib.client.gui.accent.AccentButton;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.display.DisplayMenu;
import dev.silencebound.maxtech.storage.display.task.Task;
import dev.silencebound.maxtech.storage.display.task.TaskBoardData;
import dev.silencebound.maxtech.storage.display.task.TaskBoardRef;
import dev.silencebound.maxtech.storage.display.task.TaskBoardSync;
import dev.silencebound.maxtech.storage.display.task.TaskEdit;
import dev.silencebound.maxtech.storage.display.task.TaskGoal;
import dev.silencebound.maxtech.storage.display.task.TaskTexts;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The task board editor (M7C item 15), opened from a screen's settings over the still open display menu: the board's
 * name and scope on top, the tasks on the left (click to pick one, "+ New task" for a new one), and the picked task's
 * name and goal on the right with Save (Add for a new one) and Remove. Every change is a {@link TaskEdit}; the server
 * checks it and answers with the board ({@link TaskBoardSync}), which the menu keeps. Escape goes back to the settings.
 * Client only.
 */
public final class TaskBoardScreen extends Screen {
    public static final int WIDTH = 248;
    public static final int HEIGHT = 196;
    private static final int PAD = 8;
    private static final int LIST_TOP = 28;
    private static final int LIST_WIDTH = 104;
    private static final int ROW = 11;
    private static final int LIST_ROWS = 11;
    private static final int RIGHT = PAD + LIST_WIDTH + 6;
    private static final int RIGHT_WIDTH = WIDTH - RIGHT - PAD;
    private static final int STATUS_Y = 172;
    private static final int NEW = -1;

    private final DisplayMenu menu;
    private final Screen parent;
    private int left;
    private int top;
    private int seenVersion = -1;
    private int scroll;
    /** The picked task's id, or {@link #NEW}. */
    private int picked = NEW;
    private TaskGoal.Kind kind = TaskGoal.Kind.HAND;
    private boolean network;
    private Component status = Component.empty();
    private @Nullable EditBox board;
    private @Nullable EditBox name;
    private @Nullable EditBox target;
    private @Nullable EditBox amount;
    private @Nullable AccentButton remove;

    public TaskBoardScreen(DisplayMenu menu, Screen parent) {
        super(Component.translatable(TaskTexts.EDITOR_TITLE));
        this.menu = menu;
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        left = (width - WIDTH) / 2;
        top = AccentFrame.top(height, HEIGHT);
        TaskBoardSync sync = menu.tasks();
        TaskBoardRef ref = sync != null ? sync.board() : TaskBoardRef.DEFAULT;
        if (sync == null && minecraft != null && minecraft.level != null) {
            ref = menu.clientSettings(minecraft.level).board();
        }
        network = ref.network();
        board = box(PAD, 8, 150, TaskBoardData.MAX_BOARD_NAME, TaskTexts.BOARD_HINT);
        board.setValue(ref.name());
        add(PAD + 154, 8, WIDTH - PAD * 2 - 154,
                () -> Component.translatable(network ? TaskTexts.SCOPE_NETWORK : TaskTexts.SCOPE_TEAM), () -> {
                    network = !network;
                    sendBoard();
                }).setTooltip(Tooltip.create(Component.translatable(TaskTexts.SCOPE_TIP)));
        add(PAD, LIST_TOP + LIST_ROWS * ROW + 4, LIST_WIDTH, () -> Component.translatable(TaskTexts.NEW_TASK),
                () -> pick(NEW));
        name = box(RIGHT, LIST_TOP + 10, RIGHT_WIDTH, Task.MAX_NAME, TaskTexts.NAME);
        add(RIGHT, LIST_TOP + 36, RIGHT_WIDTH, () -> Component.translatable(kind.translationKey()), () -> {
            kind = kind.next();
            refreshGoalBoxes();
        });
        target = box(RIGHT, LIST_TOP + 54, RIGHT_WIDTH, TaskGoal.MAX_TARGET, TaskTexts.TARGET_ITEM);
        amount = box(RIGHT, LIST_TOP + 72, 60, 13, TaskTexts.AMOUNT);
        amount.setFilter(text -> text.chars().allMatch(Character::isDigit));
        add(RIGHT, LIST_TOP + 92, 60, () -> Component.translatable(picked == NEW ? TaskTexts.ADD : TaskTexts.SAVE),
                this::save);
        remove = add(RIGHT + RIGHT_WIDTH - 60, LIST_TOP + 92, 60, () -> Component.translatable(TaskTexts.REMOVE),
                this::removePicked);
        refreshGoalBoxes();
        if (seenVersion < 0) {
            send(TaskEdit.WATCH, 0, "", TaskGoal.HAND);
        }
    }

    private EditBox box(int x, int y, int w, int length, String hint) {
        EditBox box = new EditBox(font, left + x, top + y + 1, w, 14, Component.translatable(hint));
        box.setMaxLength(length);
        box.setHint(Component.translatable(hint));
        box.setTextColor(AccentFrame.TEXT);
        return addRenderableWidget(box);
    }

    private AccentButton add(int x, int y, int w, Supplier<Component> label, Runnable action) {
        return addRenderableWidget(new AccentButton(left + x, top + y, w, 14, label.get(), action).label(label));
    }

    private void send(int op, int task, String text, TaskGoal goal) {
        Platform.get().network().sendToServer(new TaskEdit(menu.containerId, op, task, text, goal, network));
    }

    private void sendBoard() {
        EditBox box = board;
        if (box != null) {
            send(TaskEdit.BOARD, 0, box.getValue(), TaskGoal.HAND);
        }
    }

    private List<Task> tasks() {
        TaskBoardSync sync = menu.tasks();
        return sync == null ? List.of() : sync.tasks();
    }

    /** Shows task {@code id} (or an empty new one) on the right. */
    private void pick(int id) {
        picked = id;
        Task task = null;
        for (Task each : tasks()) {
            if (each.id() == id) {
                task = each;
            }
        }
        if (task == null) {
            picked = NEW;
        }
        kind = task == null ? TaskGoal.Kind.HAND : task.goal().kind();
        setValue(name, task == null ? "" : task.name());
        setValue(target, task == null ? "" : task.goal().target());
        setValue(amount, task == null || task.goal().amount() <= 0 ? "" : Long.toString(task.goal().amount()));
        refreshGoalBoxes();
    }

    private static void setValue(@Nullable EditBox box, String value) {
        if (box != null) {
            box.setValue(value);
        }
    }

    /** Shows the target and amount boxes the goal kind uses, with a fitting hint. */
    private void refreshGoalBoxes() {
        EditBox targetBox = target;
        EditBox amountBox = amount;
        if (targetBox != null) {
            targetBox.visible = kind.auto();
            String hint = switch (kind) {
                case LABEL_COUNT -> TaskTexts.TARGET_LABEL;
                case RESEARCH -> TaskTexts.TARGET_RESEARCH;
                default -> TaskTexts.TARGET_ITEM;
            };
            targetBox.setHint(Component.translatable(hint));
            targetBox.setTooltip(Tooltip.create(Component.translatable(hint)));
        }
        if (amountBox != null) {
            amountBox.visible = kind.auto() && kind != TaskGoal.Kind.RESEARCH;
        }
        AccentButton button = remove;
        if (button != null) {
            button.visible = picked != NEW;
        }
    }

    private TaskGoal goal() {
        EditBox targetBox = target;
        EditBox amountBox = amount;
        long count = 1;
        if (amountBox != null && !amountBox.getValue().isEmpty()) {
            try {
                count = Long.parseLong(amountBox.getValue());
            } catch (NumberFormatException tooLong) {
                count = TaskGoal.MAX_AMOUNT;
            }
        }
        return new TaskGoal(kind, targetBox == null ? "" : targetBox.getValue(), count);
    }

    private void save() {
        EditBox box = name;
        if (box == null) {
            return;
        }
        if (picked == NEW) {
            send(TaskEdit.ADD, 0, box.getValue(), goal());
        } else {
            send(TaskEdit.SAVE, picked, box.getValue(), goal());
        }
    }

    private void removePicked() {
        if (picked != NEW) {
            send(TaskEdit.REMOVE, picked, "", TaskGoal.HAND);
            pick(NEW);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (minecraft != null && (minecraft.player == null || minecraft.player.containerMenu != menu)) {
            minecraft.setScreen(null);
            return;
        }
        if (menu.tasksVersion() != seenVersion) {
            seenVersion = menu.tasksVersion();
            TaskBoardSync sync = menu.tasks();
            if (sync != null) {
                status = sync.message().isEmpty()
                        ? Component.empty()
                        : Component.translatable(sync.message(), TaskBoardData.MAX_TASKS);
                network = sync.board().network();
                // A new task was added: pick the newest, so it can be edited at once.
                if (picked == NEW && TaskTexts.SAVED.equals(sync.message()) && !sync.tasks().isEmpty()) {
                    pick(sync.tasks().getLast().id());
                } else {
                    pick(picked);
                }
            }
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        EditBox box = board;
        if (box != null && box.isFocused() && event.isConfirmation()) {
            sendBoard();
            box.setFocused(false);
            setFocused(null);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x() - left - PAD;
        double y = event.y() - top - LIST_TOP;
        if (event.button() == 0 && x >= 0 && x < LIST_WIDTH && y >= 0 && y < LIST_ROWS * ROW) {
            int index = scroll + (int) (y / ROW);
            List<Task> tasks = tasks();
            if (index < tasks.size()) {
                pick(tasks.get(index).id());
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int most = Math.max(0, tasks().size() - LIST_ROWS);
        scroll = Math.clamp(scroll - (int) Math.signum(scrollY), 0, most);
        return true;
    }

    /** Escape goes back to the settings instead of closing the menu. */
    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        AccentFrame.panel(graphics, font, left, top, WIDTH, HEIGHT, title, mouseX, mouseY);
        graphics.fill(left + PAD, top + LIST_TOP, left + PAD + LIST_WIDTH, top + LIST_TOP + LIST_ROWS * ROW,
                AccentFrame.SLOT);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        List<Task> tasks = tasks();
        for (int row = 0; row < LIST_ROWS && scroll + row < tasks.size(); row++) {
            Task task = tasks.get(scroll + row);
            int y = top + LIST_TOP + row * ROW;
            if (task.id() == picked) {
                graphics.fill(left + PAD, y, left + PAD + LIST_WIDTH, y + ROW, AccentFrame.ACCENT_DIM);
            }
            Component line = Component.literal((task.done() ? "✓ " : "· ") + task.name());
            AccentFrame.text(graphics, font, line, left + PAD + 2, y + 2, LIST_WIDTH - 4,
                    task.done() ? AccentFrame.LABEL : AccentFrame.TEXT, mouseX, mouseY);
        }
        AccentFrame.text(graphics, font, Component.translatable(TaskTexts.NAME), left + RIGHT, top + LIST_TOP,
                RIGHT_WIDTH, AccentFrame.LABEL, mouseX, mouseY);
        AccentFrame.text(graphics, font, Component.translatable(TaskTexts.DONE_WHEN), left + RIGHT, top + LIST_TOP + 27,
                RIGHT_WIDTH, AccentFrame.LABEL, mouseX, mouseY);
        for (Task task : tasks) {
            if (task.id() == picked && task.done() && !task.by().isEmpty()) {
                AccentFrame.text(graphics, font, Component.translatable(TaskTexts.DONE_BY, task.by()), left + RIGHT,
                        top + LIST_TOP + 112, RIGHT_WIDTH, AccentFrame.LABEL, mouseX, mouseY);
            }
        }
        AccentFrame.text(graphics, font, status, left + PAD, top + STATUS_Y, WIDTH - 2 * PAD, 0xFFE0A040, mouseX,
                mouseY);
        AccentFrame.text(graphics, font, Component.translatable(TaskTexts.HINT), left + PAD, top + STATUS_Y + 11,
                WIDTH - 2 * PAD, AccentFrame.LABEL, mouseX, mouseY);
    }
}
