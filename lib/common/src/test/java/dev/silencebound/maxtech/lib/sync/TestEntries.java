package dev.silencebound.maxtech.lib.sync;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/** A plain entry type for the synced list tests: name, mod and tags, encoded as three strings. */
record TestEntries(String name, String mod, List<String> tags) {
    static final StreamCodec<RegistryFriendlyByteBuf, TestEntries> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, TestEntries::name, ByteBufCodecs.STRING_UTF8, TestEntries::mod,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), TestEntries::tags, TestEntries::new);
    static final SyncedListType<TestEntries> TYPE = SyncedListType
            .unregistered(Identifier.fromNamespaceAndPath("test", "entries"), CODEC);
    static final SyncedListIndex.EntryDescriber<TestEntries> DESCRIBER = new SyncedListIndex.EntryDescriber<>() {
        @Override
        public String name(TestEntries entry) {
            return entry.name();
        }

        @Override
        public String modId(TestEntries entry) {
            return entry.mod();
        }

        @Override
        public List<String> tags(TestEntries entry) {
            return entry.tags();
        }
    };
}
