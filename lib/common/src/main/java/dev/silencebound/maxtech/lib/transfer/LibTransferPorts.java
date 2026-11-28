package dev.silencebound.maxtech.lib.transfer;

import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import dev.silencebound.maxtech.lib.api.transfer.TransferPort;
import dev.silencebound.maxtech.lib.api.transfer.TransferPortFactory;
import dev.silencebound.maxtech.lib.inventory.LibItemSlots;
import dev.silencebound.maxtech.lib.inventory.RoleView;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Lib's {@link TransferPortFactory.Factory}, installed in {@code MaxtechLib.init}. This version moves items only; a
 * buffer of another kind is refused with {@link UnsupportedOperationException} (fluids T3, energy T4, ADR-0018).
 */
public final class LibTransferPorts implements TransferPortFactory.Factory {
    @Override
    public TransferPort create(TransferPortFactory.Spec spec) {
        List<RoleView> views = new ArrayList<>();
        List<TransferPortFactory.Buffer> itemBuffers = new ArrayList<>();
        EnumSet<PortFaceMode> allowed = EnumSet.of(PortFaceMode.OFF);
        for (TransferPortFactory.Buffer buffer : spec.buffers()) {
            if (buffer.kind() != ResourceKinds.ITEM || !(buffer.buffer() instanceof LibItemSlots slots)) {
                throw new UnsupportedOperationException(
                        "This Lib version's transfer ports move items only, not " + buffer.kind().id());
            }
            views.add(slots.automation());
            itemBuffers.add(buffer);
            allowed.addAll(buffer.modes());
        }
        boolean[] pushes = new boolean[views.size()];
        boolean[] pulls = new boolean[views.size()];
        for (int i = 0; i < views.size(); i++) {
            pushes[i] = itemBuffers.get(i).modes().contains(PortFaceMode.PUSH);
            pulls[i] = itemBuffers.get(i).modes().contains(PortFaceMode.PULL);
        }
        Long cap = spec.rates().get(ResourceKinds.ITEM);
        ItemChannel items = new ItemChannel(views.toArray(RoleView[]::new), pushes, pulls, allowed,
                cap == null ? Long.MAX_VALUE : cap);
        return new LibTransferPort(spec.tasks(), items, spec.defaults(), spec.onSettingsChanged());
    }
}
