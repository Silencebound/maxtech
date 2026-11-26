package dev.silencebound.maxtech.storage.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A network's bundled redstone channels (M7L2): the strongest share wins, readers can count only some owners' shares,
 * and listeners hear only share changes.
 */
class RedstoneChannelsTest {
    private static final OwnerRecord ALICE = new OwnerRecord(UUID.randomUUID(), "alice", List.of(), false);
    private static final OwnerRecord BOB = new OwnerRecord(UUID.randomUUID(), "bob", List.of(), false);
    private final Object a = new Object();
    private final Object b = new Object();

    @Test
    void strongestShareWinsAndLeavingDropsIt() {
        RedstoneChannels channels = new RedstoneChannels();
        channels.write(a, ALICE, 14, 7);
        channels.write(b, ALICE, 14, 12);
        assertEquals(12, channels.value(14));
        channels.write(b, ALICE, 14, 3);
        assertEquals(7, channels.value(14), "a weaker share leaves the stronger one");
        channels.remove(a);
        assertEquals(3, channels.value(14));
        channels.write(b, ALICE, 14, 0);
        assertEquals(0, channels.value(14));
        assertFalse(channels.any());
    }

    @Test
    void sharesAreClampedAndOtherChannelsUntouched() {
        RedstoneChannels channels = new RedstoneChannels();
        channels.write(a, ALICE, 0, 99);
        channels.write(a, ALICE, 16, 5);
        channels.write(a, ALICE, -1, 5);
        assertEquals(15, channels.value(0));
        for (int channel = 1; channel < RedstoneChannels.COUNT; channel++) {
            assertEquals(0, channels.value(channel), "channel " + channel);
        }
        assertTrue(channels.any());
    }

    @Test
    void readersCountOnlyTheSharesTheirFilterAllows() {
        RedstoneChannels channels = new RedstoneChannels();
        channels.write(a, ALICE, 2, 6);
        channels.write(b, BOB, 2, 15);
        assertEquals(15, channels.value(2), "network-wide");
        assertEquals(6, channels.value(2, (writer, owner) -> owner.equals(ALICE)), "only alice's");
        assertEquals(0, channels.value(2, (writer, owner) -> false));
        channels.write(b, ALICE, 2, 15);
        assertEquals(15, channels.value(2, (writer, owner) -> owner.equals(ALICE)), "the share changed owner");
    }

    @Test
    void listenersHearOnlyShareChanges() {
        RedstoneChannels channels = new RedstoneChannels();
        List<String> heard = new ArrayList<>();
        RedstoneChannels.Listener listener = (channel, value) -> heard.add(channel + "=" + value);
        channels.listen(listener);
        channels.listen(listener);
        channels.write(a, ALICE, 2, 9);
        channels.write(a, ALICE, 2, 9);
        channels.write(b, ALICE, 3, 0);
        assertEquals(List.of("2=9"), heard, "equal writes and empty shares change nothing");
        channels.write(b, BOB, 2, 4);
        assertEquals(List.of("2=9", "2=9"), heard, "a weaker share is told: a reader may count only it");
        assertEquals(1, channels.changes(), "the network-wide value changed once");
        channels.remove(a);
        assertEquals("2=4", heard.get(heard.size() - 1));
        channels.unlisten(listener);
        channels.write(a, ALICE, 2, 1);
        assertEquals(3, heard.size(), "no longer listening");
    }
}
