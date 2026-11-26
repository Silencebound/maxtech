package dev.silencebound.maxtech.storage.crafting.job;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: a {@link JobNotice} about a job (M7 C18, B09 item 21d). Sent once per notice change to the player
 * who started the job; with {@code crafting.teamToasts} on, the toasting ones go to their online team members too. The
 * client shows a toast when {@link #toast} is set and keeps the requester's live notices for the jobs tab badge.
 *
 * @param job
 *            the job's id, to open it from the toast
 * @param notice
 *            what happened
 * @param toast
 *            whether the client shows a toast: an ending notice, except a cancel by the requester themself
 * @param key
 *            the job's first request
 * @param amount
 *            how many of {@code key}
 * @param more
 *            an order list's further requests (M7C item 6), at most the ones its toast names
 * @param total
 *            how many requests the job had, 1 for a single request
 * @param missing
 *            for {@link JobNotice#MISSING}, the first missing item and how many
 * @param by
 *            for {@link JobNotice#CANCELLED}, the name of the player who cancelled it; empty when not a player
 * @param requester
 *            the requester's name when this goes to a team member; empty for the requester
 */
public record JobToastPayload(UUID job, JobNotice notice, boolean toast, StackKey key, long amount,
        List<CraftingPlanner.Root> more, int total, Optional<CraftingPlanner.Root> missing, String by,
        String requester) implements CustomPacketPayload {
    /** Further requests a toast carries at most. */
    public static final int MORE = 2;
    /** Longest name the payload carries. */
    public static final int NAME_LENGTH = 64;
    public static final Type<JobToastPayload> TYPE = new Type<>(MaxtechStorage.id("job_toast"));
    private static final StreamCodec<RegistryFriendlyByteBuf, CraftingPlanner.Root> ROOT = StreamCodec.composite(
            StackKey.STREAM_CODEC, CraftingPlanner.Root::key, ByteBufCodecs.VAR_LONG, CraftingPlanner.Root::amount,
            CraftingPlanner.Root::new);
    public static final StreamCodec<RegistryFriendlyByteBuf, JobToastPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, JobToastPayload::job, JobNotice.STREAM_CODEC.cast(), JobToastPayload::notice,
            ByteBufCodecs.BOOL, JobToastPayload::toast, StackKey.STREAM_CODEC, JobToastPayload::key,
            ByteBufCodecs.VAR_LONG, JobToastPayload::amount, ROOT.apply(ByteBufCodecs.list(MORE)),
            JobToastPayload::more, ByteBufCodecs.VAR_INT, JobToastPayload::total, ByteBufCodecs.optional(ROOT).cast(),
            JobToastPayload::missing, ByteBufCodecs.stringUtf8(NAME_LENGTH), JobToastPayload::by,
            ByteBufCodecs.stringUtf8(NAME_LENGTH), JobToastPayload::requester, JobToastPayload::new);

    public JobToastPayload {
        more = List.copyOf(more);
    }

    /**
     * The notice {@code notice} about {@code job} for its requester: its first request and the next ones it names,
     * toasting when the notice is an ending one.
     */
    public static JobToastPayload of(CraftingJob job, JobNotice notice) {
        List<CraftingPlanner.Root> more = job.more();
        return new JobToastPayload(job.id(), notice, notice.ending(), job.key(), job.amount(),
                more.size() > MORE ? more.subList(0, MORE) : more, 1 + more.size(), Optional.empty(), "", "");
    }

    /** This notice with the first missing item. */
    public JobToastPayload withMissing(StackKey missingKey, long missingAmount) {
        return new JobToastPayload(job, notice, toast, key, amount, more, total,
                Optional.of(new CraftingPlanner.Root(missingKey, missingAmount)), by, requester);
    }

    /** This notice naming who cancelled it ({@code name} cut to {@link #NAME_LENGTH}). */
    public JobToastPayload withBy(String name) {
        return new JobToastPayload(job, notice, toast, key, amount, more, total, missing, cut(name), requester);
    }

    /** This notice for a team member of requester {@code name}. */
    public JobToastPayload forTeam(String name) {
        return new JobToastPayload(job, notice, toast, key, amount, more, total, missing, by, cut(name));
    }

    /** This notice with {@code show} as its toast flag. */
    public JobToastPayload withToast(boolean show) {
        return new JobToastPayload(job, notice, show, key, amount, more, total, missing, by, requester);
    }

    private static String cut(String name) {
        return name.length() > NAME_LENGTH ? name.substring(0, NAME_LENGTH) : name;
    }

    /** Every request the toast names, the first one first. */
    public List<CraftingPlanner.Root> named() {
        List<CraftingPlanner.Root> all = new ArrayList<>(1 + more.size());
        all.add(new CraftingPlanner.Root(key, amount));
        all.addAll(more);
        return all;
    }

    @Override
    public Type<JobToastPayload> type() {
        return TYPE;
    }

    /** The display name of {@code key}, for toasts and info lines. */
    public static Component name(StackKey key) {
        return switch (key) {
            case ItemKey item -> item.toStack(1).getHoverName();
            case FluidKey fluid -> StackKeys.toResource(fluid).getHoverName();
        };
    }
}
