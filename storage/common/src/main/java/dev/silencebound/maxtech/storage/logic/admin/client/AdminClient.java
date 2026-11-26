package dev.silencebound.maxtech.storage.logic.admin.client;

import dev.silencebound.maxtech.storage.logic.admin.LogicAdmin;
import net.minecraft.client.Minecraft;

/** Opens the admin overview screen when the server sends it. Client only. */
public final class AdminClient {
    private AdminClient() {
    }

    public static void open(LogicAdmin.View view) {
        Minecraft.getInstance().setScreen(new LogicAdminScreen(view));
    }
}
