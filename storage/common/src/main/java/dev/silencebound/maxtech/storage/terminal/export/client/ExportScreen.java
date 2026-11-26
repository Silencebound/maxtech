package dev.silencebound.maxtech.storage.terminal.export.client;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.client.KeyDisplay;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentButton;
import dev.silencebound.maxtech.storage.terminal.client.TerminalScreen;
import dev.silencebound.maxtech.storage.terminal.client.TerminalSubScreen;
import dev.silencebound.maxtech.storage.terminal.client.TerminalTab;
import dev.silencebound.maxtech.storage.terminal.client.TerminalTabs;
import dev.silencebound.maxtech.storage.terminal.export.ExportOutputs;
import dev.silencebound.maxtech.storage.terminal.export.InventoryExport;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The terminal's export tab (M7C item 10): the list the terminal shows (its search and craftable filter) or everything,
 * copied as CSV, plain text or a list code, saved as a CSV in {@code maxtech_exports/} of the player's game folder, or
 * sent to the server for a written book or a labelled display. The text forms read only what the client already has;
 * the book and display ask the server ({@link ExportOutputs}). Mouse only. Client only.
 */
public final class ExportScreen extends TerminalSubScreen {
    public static final int WIDTH = 210;
    public static final int HEIGHT = 168;
    private static final int MARGIN = 8;
    private static final int ERROR = 0xFFE05A4C;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId();

    private boolean all;
    private int labelIndex;
    private @Nullable Component status;
    private boolean statusError;
    private List<StackKey> cachedKeys = List.of();
    private int cachedStored = -1;
    private int cachedCraftables = -1;
    private boolean cachedAll;

    public ExportScreen(TerminalMenu menu, @Nullable TerminalScreen parent, Component terminalTitle) {
        super(Component.translatable(ExportOutputs.TITLE_KEY), menu, parent, terminalTitle, WIDTH, HEIGHT);
    }

    /** Adds the tab to network terminals; called once from client setup. */
    public static void registerTab() {
        TerminalTabs.register(new TerminalTab(MaxtechStorage.id("export"), TerminalTab.sprite("tab_export"),
                Component.translatable(ExportOutputs.TITLE_KEY), TerminalMenu::networked,
                (parent, menu) -> new ExportScreen(menu, parent, parent.getTitle())));
    }

    @Override
    protected void init() {
        super.init();
        int x = left + MARGIN;
        int inner = WIDTH - 2 * MARGIN;
        int half = (inner - 4) / 2;
        addRenderableWidget(AccentButton.dynamic(x, top + 20, inner, 16,
                () -> Component.translatable(all ? ExportOutputs.ALL_KEY : ExportOutputs.SHOWN_KEY, keys().size()),
                () -> true, input -> all = !all));
        addRenderableWidget(new AccentButton(x, top + 42, half, 16, Component.translatable(ExportOutputs.COPY_CSV_KEY),
                this::any, () -> copy(InventoryExport.csv(rows()))));
        addRenderableWidget(new AccentButton(x + half + 4, top + 42, half, 16,
                Component.translatable(ExportOutputs.COPY_TEXT_KEY), this::any, () -> copy(InventoryExport.plain(rows(),
                        Component.translatable(ExportOutputs.CRAFTABLE_MARK_KEY).getString()))));
        addRenderableWidget(new AccentButton(x, top + 62, half, 16, Component.translatable(ExportOutputs.SAVE_CSV_KEY),
                this::any, this::save));
        addRenderableWidget(
                new AccentButton(x + half + 4, top + 62, half, 16, Component.translatable(ExportOutputs.COPY_CODE_KEY),
                        this::any, () -> copy(InventoryExport.listCode(rows()))));
        AccentButton book = new AccentButton(x, top + 82, inner, 16, Component.translatable(ExportOutputs.BOOK_KEY),
                this::any, this::book);
        book.setTooltip(
                Tooltip.create(Component.translatable(ExportOutputs.BOOK_HINT_KEY, ExportOutputs.MAX_BOOK_ENTRIES)));
        addRenderableWidget(book);
        AccentButton display = AccentButton.dynamic(x, top + 104, inner - 54, 16, this::displayLabel,
                () -> !labels().isEmpty(), input -> labelIndex = (labelIndex + 1) % Math.max(1, labels().size()));
        addRenderableWidget(display);
        AccentButton send = new AccentButton(x + inner - 50, top + 104, 50, 16,
                Component.translatable(ExportOutputs.SEND_KEY), () -> any() && !labels().isEmpty(), this::display);
        send.setTooltip(Tooltip
                .create(Component.translatable(ExportOutputs.DISPLAY_HINT_KEY, ExportOutputs.MAX_DISPLAY_ENTRIES)));
        addRenderableWidget(send);
        addRenderableWidget(new AccentButton(x, top + HEIGHT - MARGIN - 16, 50, 16,
                Component.translatable(RequestContent.BACK_KEY), () -> true, this::back));
        Platform.get().network().sendToServer(new ExportOutputs.ListDisplays(menu.containerId));
    }

    @Override
    public void tick() {
        super.tick();
        ExportOutputs.Done done = ExportOutputs.takeDone(menu.containerId);
        if (done != null) {
            show(done.message(), !done.result().done());
        }
    }

    private boolean any() {
        return !keys().isEmpty();
    }

    private List<DeviceLabel> labels() {
        return ExportOutputs.shownLabels(menu.containerId);
    }

    private Component displayLabel() {
        List<DeviceLabel> labels = labels();
        if (labels.isEmpty()) {
            return Component.translatable(ExportOutputs.NO_DISPLAYS_KEY);
        }
        return Component.translatable(ExportOutputs.DISPLAY_KEY, labels.get(labelIndex % labels.size()).text());
    }

    /**
     * The keys exported: the terminal grid's current list, or everything the client knows (stored entries, then
     * craftable ones not stored), by name. Rebuilt only when the contents or the choice changed.
     */
    private List<StackKey> keys() {
        int stored = menu.clientList().version();
        int craftables = menu.craftingData().craftables().version();
        if (stored != cachedStored || craftables != cachedCraftables || all != cachedAll) {
            cachedStored = stored;
            cachedCraftables = craftables;
            cachedAll = all;
            TerminalScreen parent = parentTerminal();
            cachedKeys = !all && parent != null ? parent.shownKeys() : everything();
        }
        return cachedKeys;
    }

    private List<StackKey> everything() {
        Object2LongOpenHashMap<StackKey> stored = stored();
        List<StackKey> keys = new ArrayList<>(stored.keySet());
        for (IntIterator it = menu.craftingData().craftables().ids().iterator(); it.hasNext();) {
            StackKey key = menu.craftingData().craftables().entry(it.nextInt());
            if (key != null && !stored.containsKey(key)) {
                keys.add(key);
            }
        }
        keys.sort(Comparator.comparing(key -> KeyDisplay.name(key).getString().toLowerCase(Locale.ROOT)));
        return keys;
    }

    /** What the client's copy of the contents holds, by key. */
    private Object2LongOpenHashMap<StackKey> stored() {
        Object2LongOpenHashMap<StackKey> stored = new Object2LongOpenHashMap<>();
        for (IntIterator it = menu.clientList().ids().iterator(); it.hasNext();) {
            int id = it.nextInt();
            StackKey key = menu.clientList().entry(id);
            if (key != null) {
                stored.addTo(key, menu.clientList().count(id));
            }
        }
        return stored;
    }

    private List<InventoryExport.Row> rows() {
        Object2LongOpenHashMap<StackKey> stored = stored();
        List<StackKey> keys = keys();
        List<InventoryExport.Row> rows = new ArrayList<>(keys.size());
        for (StackKey key : keys) {
            String id = switch (key) {
                case ItemKey item -> BuiltInRegistries.ITEM.getKey(item.item()).toString();
                case FluidKey fluid -> BuiltInRegistries.FLUID.getKey(fluid.fluid()).toString();
            };
            rows.add(new InventoryExport.Row(KeyDisplay.name(key).getString(), id, key instanceof FluidKey,
                    stored.getLong(key), menu.craftingData().isCraftable(key)));
        }
        return rows;
    }

    /** The exported keys the client counts above 0, in order, at most {@code max}; for the server outputs. */
    private List<StackKey> storedKeys(int max) {
        Object2LongOpenHashMap<StackKey> stored = stored();
        List<StackKey> out = new ArrayList<>();
        for (StackKey key : keys()) {
            if (out.size() >= max) {
                break;
            }
            if (stored.getLong(key) > 0) {
                out.add(key);
            }
        }
        return out;
    }

    private void copy(String text) {
        if (minecraft != null) {
            minecraft.keyboardHandler.setClipboard(text);
            show(Component.translatable(ExportOutputs.COPIED_KEY), false);
        }
    }

    /** Saves the CSV under {@code maxtech_exports/} with a time-stamped name and toasts the path. */
    private void save() {
        if (minecraft == null) {
            return;
        }
        Path folder = minecraft.gameDirectory.toPath().resolve(InventoryExport.FOLDER);
        Path file = folder.resolve(InventoryExport.fileName(LocalDateTime.now(ZoneId.systemDefault())));
        Component title;
        Component message;
        boolean failed = false;
        try {
            Files.createDirectories(folder);
            Files.writeString(file, InventoryExport.csv(rows()), StandardCharsets.UTF_8);
            title = Component.translatable(ExportOutputs.SAVED_KEY);
            message = Component.literal(InventoryExport.FOLDER + "/" + file.getFileName());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not save the inventory list to {}", file, e);
            title = Component.translatable(ExportOutputs.SAVE_FAILED_KEY);
            message = Component.literal(String.valueOf(e.getMessage()));
            failed = true;
        }
        SystemToast.add(minecraft.getToastManager(), TOAST, title, message);
        show(Component.empty().append(title).append(": ").append(message), failed);
    }

    private void book() {
        Platform.get().network().sendToServer(new ExportOutputs.Book(menu.containerId, all,
                all ? List.of() : storedKeys(ExportOutputs.MAX_BOOK_ENTRIES)));
    }

    private void display() {
        List<DeviceLabel> labels = labels();
        if (labels.isEmpty()) {
            return;
        }
        List<StackKey> items = new ArrayList<>();
        if (!all) {
            for (StackKey key : storedKeys(Integer.MAX_VALUE)) {
                if (key instanceof ItemKey && items.size() < ExportOutputs.MAX_DISPLAY_ENTRIES) {
                    items.add(key);
                }
            }
        }
        Platform.get().network().sendToServer(
                new ExportOutputs.Display(menu.containerId, labels.get(labelIndex % labels.size()).text(), all, items));
    }

    private void show(Component message, boolean error) {
        status = message;
        statusError = error;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        Component line = status;
        if (line != null) {
            AccentFrame.text(graphics, font, line, left + MARGIN, top + 128, WIDTH - 2 * MARGIN,
                    statusError ? ERROR : AccentFrame.LABEL, mouseX, mouseY);
        }
    }
}
