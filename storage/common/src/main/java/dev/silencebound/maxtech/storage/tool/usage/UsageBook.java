package dev.silencebound.maxtech.storage.tool.usage;

import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

/**
 * Writes a {@link UsageReport} as a vanilla written book: a summary page with the team's use of the server caps, one
 * page per network, then the devices per owner. Every line is a lang key, so the reader's client shows it in their
 * language; the book's name is the report item's lang key, since a written book's title is a plain string. Server
 * thread.
 */
public final class UsageBook {
    private static final String PREFIX = "book." + MaxtechStorage.MOD_ID + ".usage.";
    static final String NAME = PREFIX + "name";
    static final String HEADING = PREFIX + "heading";
    static final String FOR = PREFIX + "for";
    static final String NETWORKS = PREFIX + "networks";
    static final String DEVICES = PREFIX + "devices";
    static final String ZONES = PREFIX + "zones";
    static final String BYTES = PREFIX + "bytes";
    static final String REFUSED_HINT = PREFIX + "refused_hint";
    static final String NETWORK = PREFIX + "network";
    static final String AT = PREFIX + "at";
    static final String NO_ROOT = PREFIX + "no_root";
    static final String OWNER = PREFIX + "owner";
    static final String LIMIT = PREFIX + "limit";
    static final String NO_LIMIT = PREFIX + "no_limit";
    static final String ROLES = PREFIX + "roles";
    static final String REFUSED_CONTROLLER = PREFIX + "refused_controller";
    static final String REFUSED_TEAM = PREFIX + "refused_team";
    static final String CELL_BYTES = PREFIX + "cell_bytes";
    static final String NETWORK_ZONES = PREFIX + "network_zones";
    static final String OFFLINE = PREFIX + "offline";
    static final String OWNERS = PREFIX + "owners";
    static final String OWNER_ROW = PREFIX + "owner_row";
    static final String NO_CAP = PREFIX + "no_cap";
    /** Owner lines per page after the heading; a book page shows 14 lines. */
    static final int OWNERS_PER_PAGE = 12;

    private UsageBook() {
    }

    /** A written book holding {@code report}, written by {@code author}. */
    public static ItemStack write(UsageReport report, String author) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        List<Filterable<Component>> pages = new ArrayList<>();
        for (Component page : pages(report)) {
            pages.add(Filterable.passThrough(page));
        }
        // A blank title makes the stack fall back to its item name, which can be translated.
        book.set(DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(Filterable.passThrough(""), author, 0, pages, true));
        book.set(DataComponents.ITEM_NAME, Component.translatable(NAME));
        return book;
    }

    /** The book's pages for {@code report}. */
    public static List<Component> pages(UsageReport report) {
        List<Component> pages = new ArrayList<>();
        pages.add(summary(report));
        for (UsageReport.NetworkRow row : report.networks()) {
            pages.add(network(row));
        }
        for (List<UsageReport.OwnerCount> chunk : chunk(report.owners(), OWNERS_PER_PAGE)) {
            List<Component> lines = new ArrayList<>(chunk.size() + 1);
            lines.add(Component.translatable(OWNERS).withStyle(ChatFormatting.BOLD));
            for (UsageReport.OwnerCount owner : chunk) {
                lines.add(Component.translatable(OWNER_ROW, owner.name(), InfoFormat.number(owner.devices())));
            }
            pages.add(page(lines));
        }
        return pages;
    }

    private static Component summary(UsageReport report) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(HEADING).withStyle(ChatFormatting.BOLD));
        lines.add(Component.translatable(FOR, report.subject()));
        lines.add(Component.translatable(NETWORKS, InfoFormat.number(report.networks().size())));
        lines.add(Component.empty());
        lines.add(Component.translatable(DEVICES, InfoFormat.number(report.devices()),
                cap(StorageServerConfig.CAP_DEVICES_PER_TEAM.getAsInt())));
        lines.add(Component.translatable(ZONES, InfoFormat.number(report.zones()),
                cap(StorageServerConfig.CAP_ZONES_PER_TEAM.getAsInt())));
        lines.add(Component.translatable(BYTES, InfoFormat.number(report.bytes()),
                cap(StorageServerConfig.CAP_BYTES_PER_TEAM.getAsLong())));
        for (UsageReport.NetworkRow row : report.networks()) {
            if (row.refused() > 0) {
                lines.add(Component.empty());
                lines.add(Component.translatable(REFUSED_HINT).withStyle(ChatFormatting.DARK_RED));
                break;
            }
        }
        return page(lines);
    }

    private static Component network(UsageReport.NetworkRow row) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(NETWORK, row.id()).withStyle(ChatFormatting.BOLD));
        BlockPos root = row.root();
        lines.add(root == null ? Component.translatable(NO_ROOT) : Component.translatable(AT, root.toShortString()));
        lines.add(Component.translatable(OWNER, row.owner()));
        lines.add(Component.translatable(LIMIT, InfoFormat.number(row.limited()),
                row.limit() == Integer.MAX_VALUE
                        ? Component.translatable(NO_LIMIT)
                        : Component.literal(InfoFormat.number(row.limit()))));
        lines.add(Component.translatable(ROLES, InfoFormat.number(row.storage()), InfoFormat.number(row.access()),
                InfoFormat.number(row.transfer()), InfoFormat.number(row.other())));
        if (row.refused() > 0) {
            lines.add(Component.translatable(row.teamCapped() ? REFUSED_TEAM : REFUSED_CONTROLLER,
                    InfoFormat.number(row.refused())).withStyle(ChatFormatting.DARK_RED));
        }
        lines.add(Component.translatable(CELL_BYTES, InfoFormat.number(row.usedBytes()),
                InfoFormat.number(row.totalBytes())));
        lines.add(Component.translatable(NETWORK_ZONES, InfoFormat.number(row.zones())));
        if (row.offlineCells() > 0) {
            lines.add(Component.translatable(OFFLINE, InfoFormat.number(row.offlineCells())));
        }
        return page(lines);
    }

    private static Component cap(long cap) {
        return cap > 0 ? Component.literal(InfoFormat.number(cap)) : Component.translatable(NO_CAP);
    }

    private static Component page(List<Component> lines) {
        MutableComponent page = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                page.append("\n");
            }
            page.append(lines.get(i));
        }
        return page;
    }

    /** {@code list} cut into runs of at most {@code size}, in order; none for an empty list. */
    public static <T> List<List<T>> chunk(List<T> list, int size) {
        List<List<T>> chunks = new ArrayList<>((list.size() + size - 1) / size);
        for (int from = 0; from < list.size(); from += size) {
            chunks.add(list.subList(from, Math.min(list.size(), from + size)));
        }
        return chunks;
    }
}
