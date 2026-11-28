package dev.silencebound.maxtech.lib.api.transfer;

import static dev.silencebound.maxtech.lib.api.transfer.PortFaceMode.OFF;
import static dev.silencebound.maxtech.lib.api.transfer.PortFaceMode.PULL;
import static dev.silencebound.maxtech.lib.api.transfer.PortFaceMode.PUSH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyPriority;
import dev.silencebound.maxtech.lib.api.energy.EnergyScheduler;
import dev.silencebound.maxtech.lib.api.inventory.ResourceTank;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.resource.TestKinds;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.junit.jupiter.api.Test;

class TransferPortsTest {
    private static TransferPorts.Builder builder() {
        return TransferPorts.builder();
    }

    @Test
    void takesSeveralBuffersOfOneKindWithTheirOwnModes() {
        TransferPorts.Builder builder = builder().tank(new StubTank<>(TestKinds.TEXT), Set.of(PULL));
        assertEquals(EnumSet.of(OFF, PULL), builder.allowed(TestKinds.TEXT));
        builder.tank(new StubTank<>(TestKinds.TEXT), Set.of(PUSH));
        assertEquals(EnumSet.allOf(PortFaceMode.class), builder.allowed(TestKinds.TEXT), "union of the buffers' modes");
        assertEquals(EnumSet.noneOf(PortFaceMode.class), builder.allowed(TestKinds.NUMBER));
        IllegalStateException noFactory = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(String.valueOf(noFactory.getMessage()).contains("not set up"),
                "checks pass and reach the factory: " + noFactory);
    }

    @Test
    void refusesABufferThatTakesPartInNothing() {
        StubTank<String> tank = new StubTank<>(TestKinds.TEXT);
        assertThrows(IllegalArgumentException.class, () -> builder().tank(tank, Set.of(OFF)));
        assertThrows(IllegalArgumentException.class, () -> builder().tank(tank, Set.of()));
    }

    @Test
    void refusesARateForAKindWithoutBuffer() {
        TransferPorts.Builder builder = builder().tank(new StubTank<>(TestKinds.TEXT), Set.of(PUSH))
                .rate(TestKinds.NUMBER, 10);
        assertThrows(IllegalStateException.class, builder::build);
        assertThrows(IllegalStateException.class, () -> builder().build(), "no buffer");
    }

    @Test
    void refusesEnergyThatIsNotAStandaloneBuffer() {
        assertThrows(IllegalArgumentException.class, () -> builder().energy(EnergyEndpoint.producer(), Set.of(PUSH)));
        assertThrows(IllegalArgumentException.class,
                () -> builder().energy(EnergyEndpoint.consumer(EnergyPriority.DEFAULT), Set.of(PULL)));
        EnergyEndpoint networked = EnergyEndpoint.buffer(1000, 10, 10, EnergyPriority.DEFAULT);
        new EnergyScheduler(() -> 0L).createNetwork().add(networked);
        assertThrows(IllegalArgumentException.class, () -> builder().energy(networked, Set.of(PUSH)), "in a network");
    }

    @Test
    void keepsTheDefaultSettings() {
        PortSettings defaults = PortSettings.EMPTY.withMode(TestKinds.TEXT, Direction.UP, PUSH);
        assertSame(PortSettings.EMPTY, builder().defaults(), "no defaults set");
        assertSame(defaults, builder().defaults(defaults).defaults());
    }

    private record StubTank<K>(ResourceKind<K> kind) implements ResourceTank<K> {
        @Override
        public Optional<K> resource() {
            return Optional.empty();
        }

        @Override
        public long amount() {
            return 0;
        }

        @Override
        public long capacity() {
            return 1;
        }

        @Override
        public boolean accepts(K key) {
            return true;
        }

        @Override
        public long insert(K key, long amount, boolean simulate) {
            return 0;
        }

        @Override
        public long extract(K key, long amount, boolean simulate) {
            return 0;
        }

        @Override
        public boolean unknown() {
            return false;
        }

        @Override
        public void discardUnknown() {
        }

        @Override
        public void set(K key, long amount) {
        }

        @Override
        public void clear() {
        }

        @Override
        public void save(ValueOutput output) {
        }

        @Override
        public void load(ValueInput input) {
        }
    }
}
