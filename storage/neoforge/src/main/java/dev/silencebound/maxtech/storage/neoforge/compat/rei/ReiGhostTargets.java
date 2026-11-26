package dev.silencebound.maxtech.storage.neoforge.compat.rei;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.board.client.BoardScreen;
import dev.silencebound.maxtech.storage.cell.CellFilter;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternPanel;
import dev.silencebound.maxtech.storage.port.PortFilter;
import dev.silencebound.maxtech.storage.port.client.PortScreen;
import dev.silencebound.maxtech.storage.terminal.client.TerminalScreen;
import dev.silencebound.maxtech.storage.terminal.filter.client.ViewFilterScreen;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import me.shedaniel.math.Point;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.drag.DraggableStack;
import me.shedaniel.rei.api.client.gui.drag.DraggableStackVisitor;
import me.shedaniel.rei.api.client.gui.drag.DraggedAcceptorResult;
import me.shedaniel.rei.api.client.gui.drag.DraggingContext;
import me.shedaniel.rei.api.client.registry.screen.ScreenRegistry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jspecify.annotations.Nullable;

/**
 * REI's entries dragged onto Storage's ghost slots, the counterpart of the JEI handlers: the pattern terminal's draft
 * slots (an item or a fluid sets the slot), the request board's request slot (an item picks what to ask for), a port's
 * filter slots (an item names itself, a fluid its filled bucket) and the view filter's entries. Each drop goes through
 * the screen method the JEI handler calls, so the same server payloads are sent; no item moves. Client only.
 */
final class ReiGhostTargets {
    private ReiGhostTargets() {
    }

    /** One drop area on a screen and what dropping a value there does; the value is an item or fluid stack. */
    private record Target(Rect2i area, Predicate<Object> accepts, Consumer<Object> drop) {
    }

    static void register(ScreenRegistry registry) {
        registry.registerDraggableStackVisitor(new Visitor<>(TerminalScreen.class, screen -> {
            List<Target> targets = new ArrayList<>();
            for (int slot = 0; slot < PatternPanel.SLOTS; slot++) {
                Rect2i area = screen.patternSlotArea(slot);
                if (area != null) {
                    int target = slot;
                    targets.add(new Target(area, v -> key(v) != null, v -> {
                        StackKey key = key(v);
                        if (key != null) {
                            screen.dropOnPattern(target, key);
                        }
                    }));
                }
            }
            return targets;
        }));
        registry.registerDraggableStackVisitor(new Visitor<>(BoardScreen.class,
                screen -> List.of(new Target(screen.requestSlotArea(), v -> key(v) instanceof ItemKey, v -> {
                    if (key(v) instanceof ItemKey item) {
                        screen.pick(item);
                    }
                }))));
        registry.registerDraggableStackVisitor(new Visitor<>(ViewFilterScreen.class, screen -> {
            List<Target> targets = new ArrayList<>(CellFilter.SIZE);
            for (int index = 0; index < CellFilter.SIZE; index++) {
                int target = index;
                targets.add(new Target(screen.entryArea(index), v -> key(v) != null, v -> {
                    StackKey key = key(v);
                    if (key != null) {
                        screen.dropOnEntry(target, key);
                    }
                }));
            }
            return targets;
        }));
        registry.registerDraggableStackVisitor(new Visitor<>(PortScreen.class, screen -> {
            List<Target> targets = new ArrayList<>(PortFilter.SIZE);
            for (int slot = 0; slot < PortFilter.SIZE; slot++) {
                int target = slot;
                targets.add(new Target(screen.filterSlotArea(slot), v -> !ghost(v).isEmpty(),
                        v -> screen.dropOnFilter(target, ghost(v))));
            }
            return targets;
        }));
    }

    /** The key an entry stands for: an item or a fluid, one of it; null for anything else. */
    private static @Nullable StackKey key(Object value) {
        if (value instanceof ItemStack stack && !stack.isEmpty()) {
            return ItemKey.of(stack.copyWithCount(1));
        }
        if (value instanceof FluidStack fluid && !fluid.isEmpty()) {
            return FluidKey.of(fluid.getFluid(), fluid.getComponentsPatch());
        }
        return null;
    }

    /** What a port filter slot holds for an entry: the item, or a fluid's filled bucket; empty for anything else. */
    private static ItemStack ghost(Object value) {
        if (value instanceof ItemStack stack && !stack.isEmpty()) {
            return stack.copyWithCount(1);
        }
        if (value instanceof FluidStack fluid && !fluid.isEmpty()) {
            return new ItemStack(fluid.getFluid().getBucket());
        }
        return ItemStack.EMPTY;
    }

    private static Rectangle rectangle(Rect2i area) {
        return new Rectangle(area.getX(), area.getY(), area.getWidth(), area.getHeight());
    }

    /** A visitor for one screen class; the targets are read when a drag starts and again on the drop. */
    private static final class Visitor<T extends Screen> implements DraggableStackVisitor<T> {
        private final Class<T> type;
        private final Function<T, List<Target>> targets;

        Visitor(Class<T> type, Function<T, List<Target>> targets) {
            this.type = type;
            this.targets = targets;
        }

        @Override
        public <R extends Screen> boolean isHandingScreen(R screen) {
            return type.isInstance(screen);
        }

        @Override
        public Stream<BoundsProvider> getDraggableAcceptingBounds(DraggingContext<T> context, DraggableStack stack) {
            Object value = stack.getStack().getValue();
            List<Rectangle> bounds = new ArrayList<>();
            for (Target target : targets.apply(context.getScreen())) {
                if (target.accepts().test(value)) {
                    bounds.add(rectangle(target.area()));
                }
            }
            return bounds.isEmpty() ? Stream.empty() : Stream.of(BoundsProvider.ofRectangles(bounds));
        }

        @Override
        public DraggedAcceptorResult acceptDraggedStack(DraggingContext<T> context, DraggableStack stack) {
            Object value = stack.getStack().getValue();
            Point at = context.getCurrentPosition();
            for (Target target : targets.apply(context.getScreen())) {
                if (target.accepts().test(value) && rectangle(target.area()).contains(at)) {
                    target.drop().accept(value);
                    return DraggedAcceptorResult.CONSUMED;
                }
            }
            return DraggedAcceptorResult.PASS;
        }
    }
}
