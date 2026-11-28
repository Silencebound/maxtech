package dev.silencebound.maxtech.lib.transfer;

import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import dev.silencebound.maxtech.lib.api.transfer.TransferPort;
import dev.silencebound.maxtech.lib.api.transfer.TransferPortFactory;
import dev.silencebound.maxtech.lib.inventory.LibItemSlots;
import dev.silencebound.maxtech.lib.inventory.LibResourceTank;
import dev.silencebound.maxtech.lib.inventory.RoleView;
import dev.silencebound.maxtech.lib.platform.KindLookup;
import dev.silencebound.maxtech.lib.platform.Platform;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lib's {@link TransferPortFactory.Factory}, installed in {@code MaxtechLib.init}: one channel per kind, in the order
 * the builder first added a buffer of it. Item slots go to an {@link ItemChannel} on Lib's item caches; tanks of any
 * kind with a transfer bridge go to a {@link TankChannel}. Energy buffers are refused with
 * {@link UnsupportedOperationException} until T4 (ADR-0018).
 */
public final class LibTransferPorts implements TransferPortFactory.Factory {
    @Override
    public TransferPort create(TransferPortFactory.Spec spec) {
        Map<ResourceKind<?>, List<TransferPortFactory.Buffer>> byKind = new LinkedHashMap<>();
        for (TransferPortFactory.Buffer buffer : spec.buffers()) {
            if (buffer.kind() == ResourceKinds.ENERGY) {
                throw new UnsupportedOperationException("This Lib version's transfer ports move no energy yet");
            }
            byKind.computeIfAbsent(buffer.kind(), kind -> new ArrayList<>()).add(buffer);
        }
        List<PortChannel> channels = new ArrayList<>(byKind.size());
        for (Map.Entry<ResourceKind<?>, List<TransferPortFactory.Buffer>> entry : byKind.entrySet()) {
            Long cap = spec.rates().get(entry.getKey());
            long ownerCap = cap == null ? Long.MAX_VALUE : cap;
            channels.add(entry.getKey() == ResourceKinds.ITEM
                    ? items(entry.getValue(), ownerCap)
                    : tanks(entry.getKey(), entry.getValue(), ownerCap));
        }
        return new LibTransferPort(spec.tasks(), channels.toArray(PortChannel[]::new), spec.defaults(),
                spec.onSettingsChanged());
    }

    private static ItemChannel items(List<TransferPortFactory.Buffer> buffers, long ownerCap) {
        RoleView[] views = new RoleView[buffers.size()];
        for (int i = 0; i < views.length; i++) {
            if (!(buffers.get(i).buffer() instanceof LibItemSlots slots)) {
                throw new IllegalArgumentException("A port's item buffer must be ItemSlots made by ItemSlots.builder(),"
                        + " not " + buffers.get(i).buffer().getClass().getName());
            }
            views[i] = slots.automation();
        }
        return new ItemChannel(views, pushes(buffers), pulls(buffers), allowed(buffers), ownerCap);
    }

    private static <K> TankChannel<K> tanks(ResourceKind<K> kind, List<TransferPortFactory.Buffer> buffers,
            long ownerCap) {
        List<LibResourceTank<K>> tanks = new ArrayList<>(buffers.size());
        for (TransferPortFactory.Buffer buffer : buffers) {
            if (!(buffer.buffer() instanceof LibResourceTank<?> tank) || tank.kind() != kind) {
                throw new IllegalArgumentException("A port's " + kind.id() + " buffer must be a tank of that kind made"
                        + " by ResourceTank.create or FluidTank.create, not " + buffer.buffer().getClass().getName());
            }
            @SuppressWarnings("unchecked") // checked: the tank's kind is this kind
            LibResourceTank<K> own = (LibResourceTank<K>) tank;
            tanks.add(own);
        }
        KindLookup<K> lookup = Platform.get().transfer().kind(kind).orElseThrow(() -> new IllegalArgumentException(
                "No transfer bridge is registered for " + kind.id() + " (KindBridges.register)"));
        return new TankChannel<>(kind, tanks, pushes(buffers), pulls(buffers), lookup, allowed(buffers), ownerCap);
    }

    private static boolean[] pushes(List<TransferPortFactory.Buffer> buffers) {
        boolean[] result = new boolean[buffers.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = buffers.get(i).modes().contains(PortFaceMode.PUSH);
        }
        return result;
    }

    private static boolean[] pulls(List<TransferPortFactory.Buffer> buffers) {
        boolean[] result = new boolean[buffers.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = buffers.get(i).modes().contains(PortFaceMode.PULL);
        }
        return result;
    }

    private static EnumSet<PortFaceMode> allowed(List<TransferPortFactory.Buffer> buffers) {
        EnumSet<PortFaceMode> allowed = EnumSet.of(PortFaceMode.OFF);
        for (TransferPortFactory.Buffer buffer : buffers) {
            allowed.addAll(buffer.modes());
        }
        return allowed;
    }
}
