package dev.silencebound.maxtech.storage.mail;

import dev.silencebound.maxtech.storage.save.KeptList;
import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * Every player's mail inbox (M6e area G), one per server: the stacks sent to them through mail ports and request
 * boards, waiting until they collect them at a mail port, plus the names players last used, so mail can be addressed to
 * a player who is offline. An inbox holds at most {@code services.mail.inboxStacks} stacks; mail to a full one is
 * refused, never dropped. Server thread.
 */
public final class MailData extends SavedData {
    private static final Codec<Inbox> INBOX_CODEC = RecordCodecBuilder.create(instance -> instance
            .group(UUIDUtil.CODEC.fieldOf("player").forGetter(Inbox::player),
                    Codec.STRING.fieldOf("name").forGetter(Inbox::name),
                    // A stack of a removed mod is kept as saved instead of failing every inbox.
                    KeptList.codec(ItemStack.CODEC).optionalFieldOf("stacks", KeptList.of(List.of()))
                            .forGetter(inbox -> KeptList.of(inbox.stacks(), inbox.kept())),
                    Level.RESOURCE_KEY_CODEC.optionalFieldOf("home").forGetter(Inbox::home))
            .apply(instance, (player, name, stacks, home) -> new Inbox(player, name, new ArrayList<>(stacks.known()),
                    stacks.kept(), home)));
    public static final Codec<MailData> CODEC = INBOX_CODEC.listOf().xmap(MailData::new,
            data -> new ArrayList<>(data.inboxes.values()));
    public static final SavedDataType<MailData> TYPE = StorageSaveFormats.savedData(MaxtechStorage.id("mail"),
            MailData::new, CODEC);

    private final Map<UUID, Inbox> inboxes = new Object2ObjectOpenHashMap<>();
    /** Lower-case name to player, for addressing. */
    private final Map<String, UUID> byName = new Object2ObjectOpenHashMap<>();

    /**
     * One player's inbox; {@code stacks} never holds an empty stack. {@code home} is the dimension of the mail port or
     * request board they last used, which decides whether mail to them while they are offline crosses dimensions.
     * {@code kept} are saved stacks that no longer load (a removed mod), written back unchanged; they take inbox room.
     */
    private record Inbox(UUID player, String name, List<ItemStack> stacks, List<Tag> kept,
            Optional<ResourceKey<Level>> home) {
    }

    private MailData() {
    }

    private MailData(List<Inbox> loaded) {
        for (Inbox inbox : loaded) {
            List<ItemStack> stacks = new ArrayList<>(inbox.stacks().size());
            for (ItemStack stack : inbox.stacks()) {
                if (!stack.isEmpty()) {
                    stacks.add(stack);
                }
            }
            Inbox clean = new Inbox(inbox.player(), inbox.name(), stacks, inbox.kept(), inbox.home());
            inboxes.put(clean.player(), clean);
            byName.put(clean.name().toLowerCase(Locale.ROOT), clean.player());
        }
    }

    /** The server's mail. */
    public static MailData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** Remembers {@code name} for {@code player}, so mail addressed to that name reaches them; keeps their home. */
    public void remember(UUID player, String name) {
        Inbox inbox = inboxes.get(player);
        remember(player, name, inbox == null ? Optional.empty() : inbox.home());
    }

    /**
     * Remembers {@code name} for {@code player} and {@code home} as the dimension they were last seen in at a mail port
     * or request board.
     */
    public void remember(UUID player, String name, ResourceKey<Level> home) {
        remember(player, name, Optional.of(home));
    }

    private void remember(UUID player, String name, Optional<ResourceKey<Level>> home) {
        Inbox inbox = inboxes.get(player);
        if (inbox != null && inbox.name().equals(name) && inbox.home().equals(home)) {
            return;
        }
        if (inbox != null) {
            byName.remove(inbox.name().toLowerCase(Locale.ROOT));
        }
        inboxes.put(player, new Inbox(player, name, inbox == null ? new ArrayList<>() : inbox.stacks(),
                inbox == null ? List.of() : inbox.kept(), home));
        byName.put(name.toLowerCase(Locale.ROOT), player);
        setDirty();
    }

    /** The dimension {@code player} last used a mail port or request board in, or null when unknown. */
    public @Nullable ResourceKey<Level> home(UUID player) {
        Inbox inbox = inboxes.get(player);
        return inbox == null ? null : inbox.home().orElse(null);
    }

    /** The player last seen with {@code name} (any case), or null for a name no player used at a mail port. */
    public @Nullable UUID find(String name) {
        return byName.get(name.toLowerCase(Locale.ROOT));
    }

    /** The name {@code player} was last seen with, or null. */
    public @Nullable String name(UUID player) {
        Inbox inbox = inboxes.get(player);
        return inbox == null ? null : inbox.name();
    }

    /** Stacks waiting for {@code player}. */
    public int size(UUID player) {
        Inbox inbox = inboxes.get(player);
        return inbox == null ? 0 : inbox.stacks().size();
    }

    /** Stacks {@code player}'s inbox still takes with a capacity of {@code capacity}; kept stacks take room too. */
    public int room(UUID player, int capacity) {
        Inbox inbox = inboxes.get(player);
        return Math.max(0, capacity - size(player) - (inbox == null ? 0 : inbox.kept().size()));
    }

    /**
     * Puts a copy of {@code stack} in {@code player}'s inbox; false (nothing changes) when the stack is empty, the
     * inbox already holds {@code capacity} stacks, or the player never used a mail port (no name known).
     */
    public boolean deliver(UUID player, ItemStack stack, int capacity) {
        Inbox inbox = inboxes.get(player);
        if (stack.isEmpty() || inbox == null || inbox.stacks().size() + inbox.kept().size() >= capacity) {
            return false;
        }
        inbox.stacks().add(stack.copy());
        setDirty();
        return true;
    }

    /** {@code player}'s waiting stacks, oldest first; read-only. */
    public List<ItemStack> inbox(UUID player) {
        Inbox inbox = inboxes.get(player);
        return inbox == null ? List.of() : Collections.unmodifiableList(inbox.stacks());
    }

    /**
     * Hands each of {@code player}'s waiting stacks to {@code taker}, which takes what it can from the stack it is
     * given (shrinking it); emptied stacks leave the inbox. Returns how many stacks left it.
     */
    public int collect(UUID player, Taker taker) {
        Inbox inbox = inboxes.get(player);
        if (inbox == null) {
            return 0;
        }
        int emptied = 0;
        List<ItemStack> stacks = inbox.stacks();
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            int before = stack.getCount();
            taker.take(stack);
            if (stack.getCount() != before) {
                setDirty();
            }
        }
        for (int i = stacks.size() - 1; i >= 0; i--) {
            if (stacks.get(i).isEmpty()) {
                stacks.remove(i);
                emptied++;
            }
        }
        return emptied;
    }

    /** Takes what it can of one stack by shrinking it. */
    @FunctionalInterface
    public interface Taker {
        void take(ItemStack stack);
    }
}
