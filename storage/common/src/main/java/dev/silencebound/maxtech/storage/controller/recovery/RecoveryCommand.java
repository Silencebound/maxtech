package dev.silencebound.maxtech.storage.controller.recovery;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.crafting.job.JobToastPayload;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * {@code /maxtech network recovery <pos>} (M7C item 20), for game masters: lists the recovery bin of the network at
 * {@code pos}, newest first, with each row's amount and time left. Part of the {@code network} literal, which checks
 * the permission. Recovering is done on the controller screen.
 */
public final class RecoveryCommand {
    private static final String HEADER_KEY = key("header");
    private static final String ROW_KEY = key("row");
    private static final String EMPTY_KEY = key("empty");
    private static final String NO_NETWORK_KEY = key("no_network");
    private static final String NO_CONTROLLER_KEY = key("no_controller");

    private RecoveryCommand() {
    }

    private static String key(String path) {
        return "commands." + MaxtechStorage.MOD_ID + ".network.recovery." + path;
    }

    static void register() {
        ModContent.CONTENT.translation(HEADER_KEY, "Recovery bin of the network at %s: %s rows");
        ModContent.CONTENT.translation(ROW_KEY, "%s. %s x %s, %s left");
        ModContent.CONTENT.translation(EMPTY_KEY, "The recovery bin of the network at %s is empty");
        ModContent.CONTENT.translation(NO_NETWORK_KEY, "No storage network at %s");
        ModContent.CONTENT.translation(NO_CONTROLLER_KEY,
                "The network at %s has no single controller, so it keeps no recovery bin");
    }

    /** The {@code recovery} literal. */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("recovery").then(Commands.argument("pos", BlockPosArgument.blockPos())
                .executes(context -> list(context.getSource(), BlockPosArgument.getBlockPos(context, "pos"))));
    }

    /** Lists the bin of the network at {@code pos}. Returns the rows listed, 0 when there is no bin. */
    public static int list(CommandSourceStack source, BlockPos pos) {
        NetworkState network = StorageNetworks.network(source.getLevel(), StorageNetworks.ID, pos);
        String at = pos.toShortString();
        if (network == null) {
            source.sendFailure(Component.translatable(NO_NETWORK_KEY, at));
            return 0;
        }
        NetworkDevice device = network.controllerDevice();
        if (!(device instanceof ControllerBlockEntity core)) {
            source.sendFailure(Component.translatable(NO_CONTROLLER_KEY, at));
            return 0;
        }
        long now = source.getLevel().getGameTime();
        List<RecoveryBin.Row> rows = core.recovery().rows(now);
        if (rows.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(EMPTY_KEY, at), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(HEADER_KEY, at, rows.size()), false);
        for (int i = 0; i < rows.size(); i++) {
            RecoveryBin.Row row = rows.get(i);
            String amount = row.key() instanceof FluidKey
                    ? InfoFormat.number(row.amount()) + " mB"
                    : InfoFormat.number(row.amount());
            Component line = Component.translatable(ROW_KEY, i + 1, JobToastPayload.name(row.key()), amount,
                    RecoveryBin.duration(RecoveryBin.expiry(row) - now));
            source.sendSuccess(() -> line, false);
        }
        return rows.size();
    }
}
