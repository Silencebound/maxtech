package dev.silencebound.maxtech.storage.crafting.cpu.client;

import dev.silencebound.maxtech.lib.api.client.gui.AccentToast;
import dev.silencebound.maxtech.lib.client.gui.accent.SideTabs;
import dev.silencebound.maxtech.storage.config.StorageClientConfig;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.job.JobAlertState;
import dev.silencebound.maxtech.storage.crafting.job.JobNotice;
import dev.silencebound.maxtech.storage.crafting.job.JobToastPayload;
import dev.silencebound.maxtech.storage.crafting.order.OrderList;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Shows the notices about crafting jobs (M7 C18, B09 item 21d): a toast for a job that finished, stopped or was
 * cancelled by someone else, inside an open screen that hosts one ({@link Host}, the terminal) or else as a vanilla
 * system toast; and the badge of the terminal's jobs tab, from the player's own jobs that wait or stopped. The client
 * option {@code crafting.toasts} hides the toasts, not the badge. Client only; loaded by the payload handler when it
 * runs.
 */
public final class JobToasts {
    /** A screen that shows the notice itself, as a toast the player can click to open the job. */
    public interface Host {
        /** Shows {@code title} and {@code detail} in {@code kind}; {@code job} is the job to open, null when gone. */
        void jobToast(Component title, Component detail, AccentToast.Kind kind, @Nullable UUID job);
    }

    private static final JobAlertState ALERTS = new JobAlertState();
    private static @Nullable Object connection;
    private static SideTabs.@Nullable Badge badge;
    private static int badgeVersion;

    private JobToasts() {
    }

    public static void show(JobToastPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        sameConnection(minecraft);
        if (payload.requester().isEmpty()) {
            ALERTS.apply(payload.job(), payload.notice());
        }
        if (!payload.toast() || !StorageClientConfig.craftingToasts()) {
            return;
        }
        Component title = title(payload);
        Component detail = detail(payload);
        if (minecraft.screen instanceof Host host) {
            host.jobToast(title, detail, kind(payload.notice()), payload.notice().gone() ? null : payload.job());
        } else {
            AccentToast.notice(title, detail);
        }
    }

    /**
     * The jobs tab's badge: red while a job of the player's stopped, amber while one waits for a CPU or power; null for
     * none. Built again only when a notice changed it.
     */
    public static SideTabs.@Nullable Badge jobsBadge() {
        sameConnection(Minecraft.getInstance());
        if (badgeVersion != ALERTS.version()) {
            badgeVersion = ALERTS.version();
            JobNotice worst = ALERTS.worst();
            badge = worst == null
                    ? null
                    : new SideTabs.Badge(
                            worst.alert() == JobNotice.Alert.ERROR ? SideTabs.Level.ERROR : SideTabs.Level.WARN,
                            Component.translatable(worst.reasonKey()));
        }
        return badge;
    }

    /** The job the jobs tab's badge is about, to open first; null for none. */
    public static @Nullable UUID badgeJob() {
        return ALERTS.worstJob();
    }

    /** Forgets the badges of another server or world. */
    @SuppressWarnings("ReferenceEquality") // a new connection is a new object; identity is the point
    private static void sameConnection(Minecraft minecraft) {
        Object now = minecraft.getConnection();
        if (now != connection) {
            connection = now;
            ALERTS.clear();
        }
    }

    private static AccentToast.Kind kind(JobNotice notice) {
        return switch (notice.alert()) {
            case ERROR -> AccentToast.Kind.ERROR;
            case WARN -> AccentToast.Kind.WARN;
            case NONE -> notice == JobNotice.CANCELLED ? AccentToast.Kind.WARN : AccentToast.Kind.INFO;
        };
    }

    private static Component title(JobToastPayload payload) {
        CraftingPlanner.Root missing = payload.missing().orElse(null);
        if (payload.notice() == JobNotice.MISSING && missing != null) {
            return Component.translatable(JobNotice.MISSING.toastKey(), missing.amount(),
                    JobToastPayload.name(missing.key()));
        }
        if (payload.notice() == JobNotice.CANCELLED && !payload.by().isEmpty()) {
            return Component.translatable(CpuContent.TOAST_CANCELLED_BY, payload.by());
        }
        if (payload.notice() == JobNotice.MISSING) {
            return Component.translatable(JobNotice.STOPPED.toastKey());
        }
        return Component.translatable(payload.notice().toastKey());
    }

    private static Component detail(JobToastPayload payload) {
        Component item = payload.total() > 1
                ? OrderList.summary(payload.named(), payload.total())
                : Component.translatable(CpuContent.TOAST_ITEM, payload.amount(), JobToastPayload.name(payload.key()));
        return payload.requester().isEmpty()
                ? item
                : Component.translatable(CpuContent.TOAST_TEAM, payload.requester(), item);
    }
}
