package dev.silencebound.maxtech.lib.client.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Stepping back through the modes a kind allows, as a right click on a face does. */
class FacesWidgetTest {
    @Test
    void previousStepsBackThroughAllowedModes() {
        Set<PortFaceMode> all = EnumSet.allOf(PortFaceMode.class);
        assertEquals(PortFaceMode.PULL, FacesWidget.previous(PortFaceMode.OFF, all));
        assertEquals(PortFaceMode.OFF, FacesWidget.previous(PortFaceMode.PUSH, all));
        assertEquals(PortFaceMode.PUSH, FacesWidget.previous(PortFaceMode.PULL, all));
        Set<PortFaceMode> pushOnly = EnumSet.of(PortFaceMode.OFF, PortFaceMode.PUSH);
        assertEquals(PortFaceMode.PUSH, FacesWidget.previous(PortFaceMode.OFF, pushOnly));
        assertEquals(PortFaceMode.OFF, FacesWidget.previous(PortFaceMode.PUSH, pushOnly));
        assertEquals(PortFaceMode.OFF, FacesWidget.previous(PortFaceMode.OFF, EnumSet.of(PortFaceMode.OFF)));
    }
}
