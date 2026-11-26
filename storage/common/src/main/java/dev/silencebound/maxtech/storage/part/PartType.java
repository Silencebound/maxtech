package dev.silencebound.maxtech.storage.part;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * One kind of cable part (the terminal, the port, later the interface, displays and the rest): a single-face Storage
 * device that sits on a storage cable's face, inside the cable's block, instead of standing in a block of its own. A
 * type names its item, its boxes (given for the north face, turned to each face), how deep it reaches into the block
 * (the cable's arm runs from the core to there) and a factory for the device. Registered in {@link PartTypes} during
 * mod construction; compared by identity.
 *
 * <p>
 * The client draws a part from its type's id and {@link CablePart#lookVariant()} through the cable model's part table;
 * the server never needs more than this class.
 */
public final class PartType {
    private final Identifier id;
    private final Supplier<? extends Item> item;
    private final List<AABB> boxes;
    private final double depth;
    private final Factory factory;
    private final VoxelShape[] shapes = new VoxelShape[6];
    private final AABB[][] turned = new AABB[6][];
    private BiConsumer<ItemStack, Consumer<Component>> tooltip = (stack, lines) -> {
    };
    private boolean inLine;

    /** Makes the device of one part on {@code face} of the cable that {@code host} holds. */
    @FunctionalInterface
    public interface Factory {
        CablePart create(PartType type, CableParts host, Direction face);
    }

    /**
     * @param boxes
     *            the part's boxes in pixels (0 to 16) for a part on the north face, i.e. lying against {@code z = 0}
     * @param factory
     *            makes the part's device
     */
    public PartType(Identifier id, Supplier<? extends Item> item, List<AABB> boxes, Factory factory) {
        if (boxes.isEmpty()) {
            throw new IllegalArgumentException("A part needs at least one box: " + id);
        }
        this.id = id;
        this.item = item;
        this.boxes = List.copyOf(boxes);
        this.factory = factory;
        double back = 0;
        for (AABB box : this.boxes) {
            back = Math.max(back, box.maxZ);
        }
        this.depth = back;
        for (Direction face : Direction.values()) {
            AABB[] faceBoxes = new AABB[this.boxes.size()];
            VoxelShape shape = Shapes.empty();
            for (int i = 0; i < faceBoxes.length; i++) {
                faceBoxes[i] = PartBoxes.turn(this.boxes.get(i), face);
                shape = Shapes.or(shape, PartBoxes.toShape(faceBoxes[i]));
            }
            turned[face.get3DDataValue()] = faceBoxes;
            shapes[face.get3DDataValue()] = shape.optimize();
        }
    }

    public Identifier id() {
        return id;
    }

    /** The part's item. Call only after items are registered. */
    public Item item() {
        return item.get();
    }

    /** The boxes given for the north face, in pixels. */
    public List<AABB> boxes() {
        return boxes;
    }

    /** The part's boxes on {@code face}, in pixels; do not change the array. */
    public AABB[] boxes(Direction face) {
        return turned[face.get3DDataValue()];
    }

    /** The part's hit shape on {@code face}, without the cable's arm behind it. */
    public VoxelShape shape(Direction face) {
        return shapes[face.get3DDataValue()];
    }

    /** How deep the part reaches from the block face into the block, in pixels; the cable's arm ends there. */
    public double depth() {
        return depth;
    }

    /**
     * Sets the tooltip summary of the part's item (its stored settings); none by default. Returns this type. During
     * construction, before the type is used.
     */
    public PartType tooltip(BiConsumer<ItemStack, Consumer<Component>> tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    /**
     * Marks the part as one that sits in the cable's line (the toggle bus) instead of closing its face: its face stays
     * linked for Lib, so the cable and its neighbour still look joined, and the network links past the part whenever
     * {@link CablePart#linksThrough()} says so. Returns this type. During construction, before the type is used.
     */
    public PartType inLine() {
        this.inLine = true;
        return this;
    }

    /** Whether the part sits in the cable's line ({@link #inLine()}). */
    public boolean isInLine() {
        return inLine;
    }

    /** Adds the item's tooltip summary for {@code stack} to {@code lines}. */
    public void appendTooltip(ItemStack stack, Consumer<Component> lines) {
        tooltip.accept(stack, lines);
    }

    CablePart create(CableParts host, Direction face) {
        return factory.create(this, host, face);
    }

    @Override
    public String toString() {
        return "PartType[" + id + "]";
    }
}
