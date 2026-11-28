package dev.silencebound.maxtech.lib.shim.saving;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jspecify.annotations.Nullable;

/**
 * Port only (1.21.1): 26.1's typed CompoundTag and ListTag getters, which 1.21.1 lacks; port/rules/saving.py turns
 * {@code tag.getIntOr("a", 0)} into {@code Nbt.getIntOr(tag, "a", 0)}. Like 26.1, a missing key or a value of another
 * type reads as the default (or empty), and an existing child is returned itself, not a copy. The ValueInput overloads
 * only pass the call on, for calls the rule can't tell apart from CompoundTag ones.
 */
public final class Nbt {
    private Nbt() {
    }

    private static @Nullable NumericTag number(@Nullable Tag tag) {
        return tag instanceof NumericTag numeric ? numeric : null;
    }

    private static @Nullable Tag at(ListTag list, int index) {
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    // CompoundTag

    public static boolean getBooleanOr(CompoundTag tag, String name, boolean defaultValue) {
        NumericTag n = number(tag.get(name));
        return n != null ? n.getAsByte() != 0 : defaultValue;
    }

    public static byte getByteOr(CompoundTag tag, String name, byte defaultValue) {
        NumericTag n = number(tag.get(name));
        return n != null ? n.getAsByte() : defaultValue;
    }

    public static short getShortOr(CompoundTag tag, String name, short defaultValue) {
        NumericTag n = number(tag.get(name));
        return n != null ? n.getAsShort() : defaultValue;
    }

    public static int getIntOr(CompoundTag tag, String name, int defaultValue) {
        NumericTag n = number(tag.get(name));
        return n != null ? n.getAsInt() : defaultValue;
    }

    public static long getLongOr(CompoundTag tag, String name, long defaultValue) {
        NumericTag n = number(tag.get(name));
        return n != null ? n.getAsLong() : defaultValue;
    }

    public static float getFloatOr(CompoundTag tag, String name, float defaultValue) {
        NumericTag n = number(tag.get(name));
        return n != null ? n.getAsFloat() : defaultValue;
    }

    public static double getDoubleOr(CompoundTag tag, String name, double defaultValue) {
        NumericTag n = number(tag.get(name));
        return n != null ? n.getAsDouble() : defaultValue;
    }

    public static String getStringOr(CompoundTag tag, String name, String defaultValue) {
        return tag.get(name) instanceof StringTag string ? string.getAsString() : defaultValue;
    }

    public static CompoundTag getCompoundOrEmpty(CompoundTag tag, String name) {
        return tag.get(name) instanceof CompoundTag compound ? compound : new CompoundTag();
    }

    public static ListTag getListOrEmpty(CompoundTag tag, String name) {
        return tag.get(name) instanceof ListTag list ? list : new ListTag();
    }

    // ListTag

    public static CompoundTag getCompoundOrEmpty(ListTag list, int index) {
        return at(list, index) instanceof CompoundTag compound ? compound : new CompoundTag();
    }

    public static ListTag getListOrEmpty(ListTag list, int index) {
        return at(list, index) instanceof ListTag inner ? inner : new ListTag();
    }

    public static short getShortOr(ListTag list, int index, short defaultValue) {
        NumericTag n = number(at(list, index));
        return n != null ? n.getAsShort() : defaultValue;
    }

    public static int getIntOr(ListTag list, int index, int defaultValue) {
        NumericTag n = number(at(list, index));
        return n != null ? n.getAsInt() : defaultValue;
    }

    public static float getFloatOr(ListTag list, int index, float defaultValue) {
        NumericTag n = number(at(list, index));
        return n != null ? n.getAsFloat() : defaultValue;
    }

    public static double getDoubleOr(ListTag list, int index, double defaultValue) {
        NumericTag n = number(at(list, index));
        return n != null ? n.getAsDouble() : defaultValue;
    }

    public static String getStringOr(ListTag list, int index, String defaultValue) {
        return at(list, index) instanceof StringTag string ? string.getAsString() : defaultValue;
    }

    // ValueInput: same names, passed on

    public static boolean getBooleanOr(ValueInput input, String name, boolean defaultValue) {
        return input.getBooleanOr(name, defaultValue);
    }

    public static byte getByteOr(ValueInput input, String name, byte defaultValue) {
        return input.getByteOr(name, defaultValue);
    }

    public static int getShortOr(ValueInput input, String name, short defaultValue) {
        return input.getShortOr(name, defaultValue);
    }

    public static int getIntOr(ValueInput input, String name, int defaultValue) {
        return input.getIntOr(name, defaultValue);
    }

    public static long getLongOr(ValueInput input, String name, long defaultValue) {
        return input.getLongOr(name, defaultValue);
    }

    public static float getFloatOr(ValueInput input, String name, float defaultValue) {
        return input.getFloatOr(name, defaultValue);
    }

    public static double getDoubleOr(ValueInput input, String name, double defaultValue) {
        return input.getDoubleOr(name, defaultValue);
    }

    public static String getStringOr(ValueInput input, String name, String defaultValue) {
        return input.getStringOr(name, defaultValue);
    }
}
