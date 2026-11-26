package dev.silencebound.maxtech.storage.display.material;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;

/** The build material list's lang keys and their English (M7C item 17). */
public final class MaterialTexts {
    private static final String KEY = "gui." + MaxtechStorage.MOD_ID + ".material_list.";

    public static final String TITLE = KEY + "title";
    public static final String STATUS = KEY + "status";
    public static final String STATUS_CUT = KEY + "status_cut";
    public static final String EMPTY = KEY + "empty";
    public static final String HINT = KEY + "hint";
    public static final String HAVE_NEED = KEY + "have_need";
    public static final String EDIT = KEY + "edit";
    public static final String UNDERSTOOD = KEY + "understood";
    public static final String PASTE = KEY + "paste";
    public static final String EDITOR_HINT = KEY + "editor_hint";
    public static final String CRAFT_MISSING = KEY + "craft_missing";
    public static final String CRAFTING = KEY + "crafting";
    public static final String COPY_CODE = KEY + "copy_code";
    public static final String TAKE_ALL = KEY + "take_all";
    public static final String TAKEN = KEY + "taken";
    public static final String TAKEN_SHORT = KEY + "taken_short";
    public static final String TAKE_NO_ACCESS = KEY + "take_no_access";
    public static final String COPY_LINE = KEY + "copy_line";
    public static final String COPY_TIP = KEY + "copy_tip";
    public static final String NOTHING_MISSING = KEY + "nothing_missing";
    public static final String NOTHING_CRAFTABLE = KEY + "nothing_craftable";
    public static final String QUEUED = KEY + "queued";
    public static final String NO_ACCESS = KEY + "no_access";
    public static final String BUSY = KEY + "busy";
    public static final String REFUSED = KEY + "refused";
    public static final String POOL_FULL = KEY + "pool_full";

    private MaterialTexts() {
    }

    /** Queues the English texts; during mod construction. */
    public static void init() {
        add(TITLE, "Build materials");
        add(STATUS, "%s of %s complete");
        add(STATUS_CUT, "%s of %s complete (first %s kept)");
        add(EMPTY, "No list yet: sneak and right-click to set one");
        add(HINT, "Counts a list code (mtlist1:)");
        add(HAVE_NEED, "%s / %s");
        add(EDIT, "Edit list");
        add(UNDERSTOOD, "%s entries understood");
        add(PASTE, "Paste");
        add(EDITOR_HINT, "Paste a mtlist1: code or type: stick*64, torch*16");
        add(CRAFT_MISSING, "Craft missing");
        add(CRAFTING, "Crafting…");
        add(COPY_CODE, "Copy code");
        add(TAKE_ALL, "Take all");
        add(TAKEN, "Took %s items");
        add(TAKEN_SHORT, "Took %s items; %s short in %s entries (not stored or no room)");
        add(TAKE_NO_ACCESS, "You need full access on this network to take items");
        add(COPY_LINE, "Material list code (click to copy)");
        add(COPY_TIP, "Copies the list code");
        add(NOTHING_MISSING, "Nothing is missing");
        add(NOTHING_CRAFTABLE, "None of the missing items can be crafted here");
        add(QUEUED, "Crafting %s missing items");
        add(NO_ACCESS, "You need craft access on this network");
        add(BUSY, "The last craft request is still being planned or crafted");
        add(REFUSED, "The network could not start that craft");
        add(POOL_FULL, "Too many plans are running; try again in a moment");
    }

    private static void add(String key, String english) {
        ModContent.CONTENT.translation(key, english);
    }
}
