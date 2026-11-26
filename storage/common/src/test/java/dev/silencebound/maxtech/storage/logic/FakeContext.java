package dev.silencebound.maxtech.storage.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** A logic context over maps: values by watch, running crafts, and every action as a line of text. */
class FakeContext implements LogicContext<String> {
    final Map<String, Long> values = new HashMap<>();
    final Set<String> runningCrafts = new HashSet<>();
    final List<String> actions = new ArrayList<>();
    int reads;

    /** The key a watch reads under: {@code kind/item/label/name}. */
    static String key(WatchKind kind, String item, String label, String name) {
        return kind + "/" + item + "/" + label + "/" + name;
    }

    static String key(Watch<String> watch) {
        String key = key(watch.kind(), watch.item().orElse(""), watch.label(), watch.name());
        // Stock keeper watches differ by which value they read.
        return watch.kind() == WatchKind.STOCK ? key + "/" + watch.number() : key;
    }

    /** Sets stock keeper value {@code value} ({@code WatchKind.STOCK_*}) for {@code item} in {@code label}. */
    FakeContext stock(String item, String label, long value, long amount) {
        values.put(key(WatchKind.STOCK, item, label, "") + "/" + value, amount);
        return this;
    }

    FakeContext set(WatchKind kind, String item, String label, String name, long value) {
        values.put(key(kind, item, label, name), value);
        return this;
    }

    FakeContext count(String item, long value) {
        return set(WatchKind.ITEM_COUNT, item, "", "", value);
    }

    @Override
    public long read(Watch<String> watch) {
        reads++;
        return values.getOrDefault(key(watch), 0L);
    }

    @Override
    public boolean craftRunning(String item) {
        return runningCrafts.contains(item);
    }

    @Override
    public void switchDevice(String label, boolean on) {
        actions.add("switch " + label + " " + on);
    }

    @Override
    public void setPriority(String label, long priority) {
        actions.add("priority " + label + " " + priority);
    }

    @Override
    public void setPowerPriority(String label, long priority) {
        actions.add("power " + label + " " + priority);
    }

    @Override
    public void redstone(String label, int strength) {
        actions.add("redstone " + label + " " + strength);
    }

    @Override
    public void channel(int channel, int strength) {
        actions.add("channel " + channel + " " + strength);
    }

    @Override
    public void pulseChannel(int channel, int strength, boolean on) {
        actions.add("pulse channel " + channel + " " + strength + (on ? "" : " off"));
    }

    @Override
    public void requestCraft(String item, long amount) {
        actions.add("craft " + item + " " + amount);
    }

    @Override
    public void requestCraft(String item, long amount, String label) {
        if (label.isEmpty()) {
            requestCraft(item, amount);
        } else {
            actions.add("craft " + item + " " + amount + " " + label);
        }
    }

    @Override
    public void moveItems(String item, String from, String to, long amount) {
        actions.add("move " + item + " " + from + ">" + to + " " + amount);
    }

    @Override
    public void notify(String text, int channels) {
        actions.add("notify " + text + " " + channels);
    }

    @Override
    public void pulse(String label, int strength, boolean on) {
        actions.add("pulse " + label + " " + strength + (on ? "" : " off"));
    }

    @Override
    public void cancelCraft(String item, boolean ownOnly) {
        actions.add("cancel " + item + (ownOnly ? " own" : " all"));
    }

    @Override
    public void writeDisplay(String label, String text, long a, long b) {
        actions.add("display " + label + " " + LogicContext.fillValues(text, a, b));
    }

    @Override
    public void setFilter(String label, @Nullable String item, int slot, int portMode) {
        actions.add("filter " + label + " " + item + " " + slot + " " + portMode);
    }

    @Override
    public void setVariable(String name, long value, boolean yesNo) {
        actions.add("variable " + name + " " + value + (yesNo ? " yes/no" : ""));
    }

    @Override
    public void setTask(String name, boolean done) {
        actions.add("task " + name.replace('\n', '|') + " " + done);
    }

    @Override
    public void runRule(String rule) {
        actions.add("run " + rule);
    }

    @Override
    public void runRule(String rule, @Nullable String item, String itemParam, long amount, String amountParam) {
        if (itemParam.isEmpty() && amountParam.isEmpty()) {
            runRule(rule);
        } else {
            actions.add("run " + rule + " " + itemParam + "=" + item + " " + amountParam + "=" + amount);
        }
    }

    @Override
    public void switchStockRule(String label, @Nullable String item, boolean on) {
        actions.add("stock " + label + " " + item + " " + on);
    }

    @Override
    public void setStockAmount(String label, String item, int field, long amount) {
        actions.add("stock " + label + " " + item + " field " + field + " " + amount);
    }

    /** Where the fake moves a pool send's turn: each send moves it on by one. */
    @Override
    public long sendToPool(String item, String label, long amount, int strategy, String idleValue, long idle,
            long cursor) {
        actions.add("pool " + item + " " + label + " " + amount + " strategy " + strategy + " idle " + idleValue + " "
                + Pools.idleCompare(idle) + " " + Pools.idleLimit(idle) + " at " + cursor);
        return cursor + 1;
    }

    // For each (M7L2 row 18): tags and stock keepers by name, the run's item limit and the team's checks left.
    final Map<String, List<String>> tags = new HashMap<>();
    final Map<String, List<String>> keepers = new HashMap<>();
    int maxEach = 64;
    long checksLeft = Long.MAX_VALUE;
    long actionPointsLeft = Long.MAX_VALUE;

    /** Reads like {@link #read}, under the same keys. */
    @Override
    public long readEach(WatchKind kind, String item, String label, long number) {
        reads++;
        String key = key(kind, item, label, "");
        return values.getOrDefault(kind == WatchKind.STOCK ? key + "/" + number : key, 0L);
    }

    @Override
    public void eachItem(String key, List<String> out, int max) {
        List<String> items = tags.get(key);
        if (items == null) {
            if (out.size() < max) {
                out.add(key);
            }
            return;
        }
        for (int i = 0; i < items.size() && out.size() < max; i++) {
            out.add(items.get(i));
        }
    }

    @Override
    public void stockItems(String label, List<String> out, int max) {
        for (String item : keepers.getOrDefault(label, List.of())) {
            if (out.size() < max) {
                out.add(item);
            }
        }
    }

    @Override
    public int maxEachItems() {
        return maxEach;
    }

    @Override
    public long checksLeft() {
        return checksLeft;
    }

    @Override
    public long actionPointsLeft() {
        return actionPointsLeft;
    }

    /** The actions so far, then forgets them. */
    List<String> take() {
        List<String> taken = List.copyOf(actions);
        actions.clear();
        return taken;
    }
}
