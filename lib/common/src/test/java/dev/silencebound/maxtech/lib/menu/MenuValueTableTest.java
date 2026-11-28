package dev.silencebound.maxtech.lib.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.menu.EnergyValue;
import dev.silencebound.maxtech.lib.api.menu.EnumValue;
import dev.silencebound.maxtech.lib.api.menu.IntValue;
import dev.silencebound.maxtech.lib.api.menu.LongValue;
import dev.silencebound.maxtech.lib.api.menu.MenuValues;
import dev.silencebound.maxtech.lib.api.menu.ProgressValue;
import dev.silencebound.maxtech.lib.api.menu.TankValue;
import dev.silencebound.maxtech.lib.api.resource.AmountUnit;
import dev.silencebound.maxtech.lib.api.resource.KindKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.resource.TestKindTable;
import dev.silencebound.maxtech.lib.api.resource.UnknownResource;
import io.netty.buffer.Unpooled;
import java.lang.management.ManagementFactory;
import java.util.Optional;
import com.mojang.serialization.Codec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * Menu values without registries: the fluid tank's content is a string here (fluid keys are covered by GameTests); kind
 * tanks hold keys of a test kind, which needs no registries.
 */
class MenuValueTableTest {
    enum Mode {
        OFF, SLOW, FAST
    }

    static final class Host {
        int count;
        long big;
        Mode mode = Mode.OFF;
        long stored;
        long capacity;
        long done;
        long total;
        String fluid = "";
        long amount;
        long tankCapacity;
    }

    private static final MenuValues.Builder<Host> BUILDER = MenuValues.builder();
    private static final IntValue COUNT = BUILDER.intValue(h -> h.count);
    private static final LongValue BIG = BUILDER.longValue(h -> h.big);
    private static final EnumValue<Mode> MODE = BUILDER.enumValue(Mode.class, h -> h.mode);
    private static final EnergyValue ENERGY = BUILDER.energy(h -> h.stored, h -> h.capacity);
    private static final ProgressValue PROGRESS = BUILDER.progress(h -> h.done, h -> h.total);
    private static final TankValue TANK = BUILDER.tank(h -> Optional.empty(), h -> h.amount, h -> h.tankCapacity);
    private static final MenuValues<Host> VALUES = BUILDER.build();
    /** The tank holds the host's string instead of a fluid. */
    private static final MenuLayout<Host> LAYOUT = MenuLayout.withTankContent(VALUES, TANK, h -> h.fluid,
            ByteBufCodecs.STRING_UTF8, "");

    private static final TestKindTable KINDS = new TestKindTable();
    /** A kind counted one by one, with integer keys. */
    private static final ResourceKind<Integer> SPARK = KINDS.register(
            Identifier.fromNamespaceAndPath("test", "menu_spark"), Codec.INT, ByteBufCodecs.VAR_INT.cast(),
            AmountUnit.ITEMS);

    static final class KindHost {
        Optional<KindKey<?>> resource = Optional.empty();
        Optional<Integer> spark = Optional.empty();
        long amount;
        int count;
    }

    private static final MenuValues.Builder<KindHost> KIND_BUILDER = MenuValues.builder();
    private static final IntValue KIND_COUNT = KIND_BUILDER.intValue(h -> h.count);
    private static final TankValue ANY = KIND_BUILDER.kindTank(h -> h.resource, h -> h.amount, h -> 9_000);
    private static final TankValue SPARKS = KIND_BUILDER.tank(SPARK, h -> h.spark, h -> h.amount * 2, h -> 64);
    private static final MenuValues<KindHost> KIND_VALUES = KIND_BUILDER.build();
    private static final MenuLayout<KindHost> KIND_LAYOUT = MenuLayout.withKindCodec(KIND_VALUES,
            ByteBufCodecs.optional(KINDS.clientbound()));

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    /** Collects and encodes {@code server}'s marked values and applies them to {@code client}; the body size. */
    private static int sync(MenuValueTable<Host> server, Host host, MenuValueTable<Host> client) {
        server.collect(host);
        RegistryFriendlyByteBuf buf = buffer();
        server.encode(buf);
        server.clearMarks();
        int size = buf.writerIndex();
        client.apply(buf);
        assertEquals(0, buf.readableBytes(), "whole body read");
        return size;
    }

    private static Host full() {
        Host host = new Host();
        host.count = -12345;
        host.big = Integer.MAX_VALUE + 5_000_000_000L;
        host.mode = Mode.FAST;
        host.stored = 3_000_000_000L;
        host.capacity = Long.MAX_VALUE;
        host.done = 17;
        host.total = 200;
        host.fluid = "minecraft:water{hot}";
        host.amount = 4_000_000_000L;
        host.tankCapacity = 8_000_000_000L;
        return host;
    }

    @Test
    void everyKindRoundTrips() {
        Host host = full();
        MenuValueTable<Host> server = new MenuValueTable<>(LAYOUT);
        MenuValueTable<Host> client = new MenuValueTable<>(LAYOUT);
        server.markAll();
        sync(server, host, client);
        assertEquals(-12345, client.get(COUNT));
        assertEquals(Integer.MAX_VALUE + 5_000_000_000L, client.get(BIG));
        assertEquals(Mode.FAST, client.get(MODE));
        assertEquals(3_000_000_000L, client.stored(ENERGY));
        assertEquals(Long.MAX_VALUE, client.capacity(ENERGY));
        assertEquals(17, client.current(PROGRESS));
        assertEquals(200, client.total(PROGRESS));
        assertEquals("minecraft:water{hot}", client.content(TANK));
        assertEquals(4_000_000_000L, client.amount(TANK));
        assertEquals(8_000_000_000L, client.capacity(TANK));
        assertEquals(server.get(BIG), client.get(BIG), "server view holds the last read");
    }

    @Test
    void onlyChangedValuesTravel() {
        Host host = full();
        MenuValueTable<Host> server = new MenuValueTable<>(LAYOUT);
        MenuValueTable<Host> client = new MenuValueTable<>(LAYOUT);
        server.markAll();
        int first = sync(server, host, client);

        assertFalse(server.collect(host), "nothing changed");
        host.stored = 3_000_000_001L;
        assertTrue(server.collect(host));
        RegistryFriendlyByteBuf buf = buffer();
        server.encode(buf);
        assertTrue(server.dirty(), "marks stay until the payload is sent");
        server.clearMarks();
        assertFalse(server.dirty());
        assertEquals(1L << ENERGY.index(), buf.readVarLong(), "mask names only the energy");
        assertEquals(3_000_000_001L, buf.readVarLong());
        assertEquals(Long.MAX_VALUE, buf.readVarLong(), "capacity travels with stored");
        assertEquals(0, buf.readableBytes());

        // A fresh client given only this change keeps its zeros elsewhere.
        MenuValueTable<Host> late = new MenuValueTable<>(LAYOUT);
        host.count = 7;
        int second = sync(server, host, late);
        assertEquals(7, late.get(COUNT));
        assertEquals(0, late.get(BIG));
        assertEquals("", late.content(TANK));
        assertTrue(second < first, second + " bytes for one value, " + first + " for all");
    }

    @Test
    void firstSyncCarriesValuesEqualToTheDefaults() {
        MenuValueTable<Host> server = new MenuValueTable<>(LAYOUT);
        assertFalse(server.collect(new Host()), "all getters return the initial values");
        server.markAll();
        RegistryFriendlyByteBuf buf = buffer();
        server.encode(buf);
        assertEquals((1L << VALUES.size()) - 1, buf.readVarLong(), "every value marked");
    }

    @Test
    void manyValuesUseMoreMaskWords() {
        MenuValues.Builder<Host> builder = MenuValues.builder();
        IntValue[] handles = new IntValue[70];
        for (int i = 0; i < handles.length; i++) {
            int n = i;
            handles[i] = builder.intValue(h -> h.count + n);
        }
        MenuValues<Host> values = builder.build();
        MenuValueTable<Host> server = new MenuValueTable<>(values);
        MenuValueTable<Host> client = new MenuValueTable<>(values);
        Host host = new Host();
        host.count = 1;
        server.markAll();
        sync(server, host, client);
        assertEquals(1, client.get(handles[0]));
        assertEquals(70, client.get(handles[69]));
        host.count = 2;
        sync(server, host, client);
        assertEquals(71, client.get(handles[69]));
    }

    @Test
    void unknownOrdinalIsClamped() {
        RegistryFriendlyByteBuf buf = buffer();
        buf.writeVarLong(1L << MODE.index());
        buf.writeVarInt(9);
        MenuValueTable<Host> client = new MenuValueTable<>(LAYOUT);
        client.apply(buf);
        assertEquals(Mode.FAST, client.get(MODE));
        buf = buffer();
        buf.writeVarLong(1L << MODE.index());
        buf.writeVarInt(-3);
        client.apply(buf);
        assertEquals(Mode.OFF, client.get(MODE));
    }

    @Test
    void foreignHandleIsRefused() {
        MenuValues.Builder<Host> builder = MenuValues.builder();
        builder.longValue(h -> h.big);
        MenuValueTable<Host> table = new MenuValueTable<>(builder.build());
        assertThrows(IllegalArgumentException.class, () -> table.get(COUNT), "kind differs at index 0");
        assertThrows(IllegalArgumentException.class, () -> table.get(MODE), "index past the end");
    }

    @Test
    void fluidReadOfAnotherTankIsRefused() {
        MenuValueTable<Host> table = new MenuValueTable<>(LAYOUT);
        assertThrows(IllegalArgumentException.class, () -> table.fluid(TANK), "tank holds a string");
        assertEquals("", table.content(TANK));
    }

    /** Collects and encodes {@code server}'s marked values and applies them to {@code client}; the body. */
    private static RegistryFriendlyByteBuf syncKinds(MenuValueTable<KindHost> server, KindHost host,
            MenuValueTable<KindHost> client) {
        server.collect(host);
        RegistryFriendlyByteBuf buf = buffer();
        server.encode(buf);
        server.clearMarks();
        RegistryFriendlyByteBuf copy = new RegistryFriendlyByteBuf(buf.copy(), RegistryAccess.EMPTY);
        client.apply(buf);
        assertEquals(0, buf.readableBytes(), "whole body read");
        return copy;
    }

    @Test
    void kindTanksRoundTrip() {
        KindHost host = new KindHost();
        host.resource = Optional.of(new KindKey<>(SPARK, 42));
        host.spark = Optional.of(7);
        host.amount = 5_000_000_000L;
        MenuValueTable<KindHost> server = new MenuValueTable<>(KIND_LAYOUT);
        MenuValueTable<KindHost> client = new MenuValueTable<>(KIND_LAYOUT);
        server.markAll();
        syncKinds(server, host, client);
        assertEquals(Optional.of(new KindKey<>(SPARK, 42)), client.resource(ANY));
        assertEquals(5_000_000_000L, client.amount(ANY));
        assertEquals(9_000, client.capacity(ANY));
        assertEquals(Optional.of(new KindKey<>(SPARK, 7)), client.resource(SPARKS), "typed key with its kind");
        assertEquals(10_000_000_000L, client.amount(SPARKS));
        assertEquals(64, client.capacity(SPARKS));
        assertEquals(client.resource(SPARKS), server.resource(SPARKS), "server view holds the last read");
        assertThrows(IllegalArgumentException.class, () -> client.fluid(ANY), "any kind is not a fluid tank");
        assertThrows(IllegalArgumentException.class, () -> client.fluid(SPARKS), "spark tank is not a fluid tank");

        host.resource = Optional.empty();
        host.spark = Optional.empty();
        syncKinds(server, host, client);
        assertEquals(Optional.empty(), client.resource(ANY));
        assertEquals(Optional.empty(), client.resource(SPARKS));
    }

    /** Unknown contents travel as their kind id alone and arrive unknown, not empty. */
    @Test
    void unknownContentsArriveAsUnknown() {
        Identifier gone = Identifier.fromNamespaceAndPath("removed", "essence");
        CompoundTag raw = new CompoundTag();
        raw.putString(KindKey.KIND_FIELD, gone.toString());
        raw.putInt("value", 12);
        KindHost host = new KindHost();
        host.resource = Optional.of(UnknownResource.key(gone, raw));
        host.amount = 300;
        MenuValueTable<KindHost> server = new MenuValueTable<>(KIND_LAYOUT);
        MenuValueTable<KindHost> client = new MenuValueTable<>(KIND_LAYOUT);
        server.markAll();
        syncKinds(server, host, client);
        KindKey<?> received = client.resource(ANY).orElseThrow();
        assertTrue(received.isUnknown());
        assertEquals(gone, received.kindId());
        assertEquals(300, client.amount(ANY));
    }

    @Test
    void kindTankSendsOnlyWhenItsKeyChanges() {
        KindHost host = new KindHost();
        host.resource = Optional.of(new KindKey<>(SPARK, 1));
        MenuValueTable<KindHost> server = new MenuValueTable<>(KIND_LAYOUT);
        MenuValueTable<KindHost> client = new MenuValueTable<>(KIND_LAYOUT);
        server.markAll();
        syncKinds(server, host, client);

        host.resource = Optional.of(new KindKey<>(SPARK, 1));
        assertFalse(server.collect(host), "a new but equal key is no change");
        host.count = 3;
        RegistryFriendlyByteBuf body = syncKinds(server, host, client);
        assertEquals(1L << KIND_COUNT.index(), body.readVarLong(), "mask names only the int");

        host.resource = Optional.of(new KindKey<>(SPARK, 2));
        body = syncKinds(server, host, client);
        assertEquals(1L << ANY.index(), body.readVarLong(), "mask names only the kind tank");
        assertEquals(Optional.of(new KindKey<>(SPARK, 2)), client.resource(ANY));
        assertEquals(3, client.get(KIND_COUNT));
    }

    @Test
    void builderIsSingleUse() {
        MenuValues.Builder<Host> builder = MenuValues.builder();
        builder.build();
        assertThrows(IllegalStateException.class, () -> builder.intValue(h -> h.count));
    }

    /** The per-tick path with nothing changed reads getters into primitive arrays and allocates nothing. */
    @Test
    void idleCollectAllocatesNothing() {
        MenuValueTable<Host> server = new MenuValueTable<>(LAYOUT);
        Host host = full();
        assertIdleAllocatesNothing(server, host);
    }

    /** Kind tanks too: the typed tank's key is wrapped only when it changes. */
    @Test
    void idleKindCollectAllocatesNothing() {
        KindHost host = new KindHost();
        host.resource = Optional.of(new KindKey<>(SPARK, 42));
        host.spark = Optional.of(7);
        host.amount = 10;
        MenuValueTable<KindHost> server = new MenuValueTable<>(KIND_LAYOUT);
        assertIdleAllocatesNothing(server, host);
    }

    private static <H> void assertIdleAllocatesNothing(MenuValueTable<H> server, H host) {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        server.collect(host);
        server.clearMarks();
        for (int i = 0; i < 20_000; i++) {
            server.collect(host);
        }
        long thread = Thread.currentThread().threadId();
        long before = threads.getThreadAllocatedBytes(thread);
        boolean dirty = false;
        for (int i = 0; i < 100_000; i++) {
            dirty |= server.collect(host);
        }
        long allocated = threads.getThreadAllocatedBytes(thread) - before;
        assertFalse(dirty);
        // One boxed value or array per call would be well over a megabyte; allow the measurement's own noise.
        assertTrue(allocated < 4096, allocated + " bytes allocated over 100,000 idle collects");
    }
}
