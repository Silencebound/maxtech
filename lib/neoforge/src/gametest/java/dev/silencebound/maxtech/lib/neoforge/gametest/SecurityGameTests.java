package dev.silencebound.maxtech.lib.neoforge.gametest;

import com.mojang.authlib.GameProfile;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.api.security.OwnerSecurity;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.api.config.ConfigFile;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.registry.ModDataComponents;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Owner and team security: the owner, trusted players and the owner's scoreboard team get in, strangers do not, team
 * access can be switched off, and an owner record survives the item component and its codecs.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class SecurityGameTests {
    private static final String[] NAMES = {"security_owner_trusted_and_strangers", "security_scoreboard_team",
            "security_owner_record_round_trip", "security_ops_bypass_config"};
    private static final String OPTION = "security.opsBypassOwnership";
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        FUNCTIONS.register(NAMES[0], () -> SecurityGameTests::ownerTrustedAndStrangers);
        FUNCTIONS.register(NAMES[1], () -> SecurityGameTests::scoreboardTeam);
        FUNCTIONS.register(NAMES[2], () -> SecurityGameTests::ownerRecordRoundTrip);
        FUNCTIONS.register(NAMES[3], () -> SecurityGameTests::opsBypassConfig);
    }

    private SecurityGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("security"));
        for (String name : NAMES) {
            Identifier id = MaxtechLib.id(name);
            event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                    new TestData<>(environment, LibGameTests.EMPTY_STRUCTURE, 20, 0, true)));
        }
    }

    static ServerPlayer player(ServerLevel level, String name) {
        return new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), name),
                ClientInformation.createDefault());
    }

    private static void ownerTrustedAndStrangers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = player(level, "sec_owner");
        ServerPlayer friend = player(level, "sec_friend");
        ServerPlayer stranger = player(level, "sec_stranger");
        helper.assertFalse(stranger.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),
                "test players must not be ops");
        OwnerRecord record = OwnerRecord.of(owner).withTrusted(friend.getUUID());
        helper.assertTrue(OwnerSecurity.canAccess(owner, record), "owner");
        helper.assertTrue(OwnerSecurity.canAccess(friend, record), "trusted");
        helper.assertFalse(OwnerSecurity.canAccess(stranger, record), "stranger");
        helper.assertTrue(OwnerSecurity.canAccess(stranger, OwnerRecord.UNOWNED), "unowned lets everyone in");
        OwnerRecord untrusted = record.withoutTrusted(friend.getUUID());
        helper.assertFalse(OwnerSecurity.canAccess(friend, untrusted), "trust removed");
        helper.assertValueEqual(record.withTrusted(friend.getUUID()).trusted().size(), 1, "no duplicate trust");
        helper.assertValueEqual(record.withTrusted(owner.getUUID()).trusted().size(), 1, "the owner is not trusted");
        helper.succeed();
    }

    private static void scoreboardTeam(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = player(level, "sec_team_owner");
        ServerPlayer mate = player(level, "sec_team_mate");
        ServerPlayer other = player(level, "sec_team_other");
        Scoreboard scoreboard = level.getScoreboard();
        PlayerTeam team = scoreboard.addPlayerTeam("lib_sec_" + UUID.randomUUID().toString().substring(0, 8));
        PlayerTeam otherTeam = scoreboard.addPlayerTeam("lib_sec_" + UUID.randomUUID().toString().substring(0, 8));
        try {
            scoreboard.addPlayerToTeam(owner.getScoreboardName(), team);
            scoreboard.addPlayerToTeam(mate.getScoreboardName(), team);
            scoreboard.addPlayerToTeam(other.getScoreboardName(), otherTeam);
            OwnerRecord record = OwnerRecord.of(owner);
            helper.assertTrue(OwnerSecurity.canAccess(mate, record), "team mate");
            helper.assertFalse(OwnerSecurity.canAccess(other, record), "other team");
            helper.assertFalse(OwnerSecurity.canAccess(mate, record.withTeamAccess(false)), "team access off");
            helper.assertFalse(OwnerSecurity.canAccess(mate, new OwnerRecord(owner.getUUID(), "", List.of(), true)),
                    "no owner name: vanilla teams cannot match");
        } finally {
            scoreboard.removePlayerTeam(team);
            scoreboard.removePlayerTeam(otherTeam);
        }
        helper.succeed();
    }

    private static void opsBypassConfig(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = player(level, "sec_ops_owner");
        ServerPlayer op = new ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "sec_ops_admin"), ClientInformation.createDefault()) {
            @Override
            public PermissionSet permissions() {
                return LevelBasedPermissionSet.GAMEMASTER;
            }
        };
        ServerPlayer stranger = player(level, "sec_ops_stranger");
        ConfigFile config = ServerConfig.configFile();
        if (config == null || !config.isLoaded()) {
            throw helper.assertionException("server config not loaded");
        }
        OwnerRecord record = OwnerRecord.of(owner);
        Object previous = config.get(OPTION);
        try {
            // In memory only (no save): nothing is written to disk or reloaded, and the finally restores it.
            config.set(OPTION, true);
            helper.assertTrue(OwnerSecurity.canAccess(op, record), "op gets in with the bypass on");
            helper.assertFalse(OwnerSecurity.canAccess(stranger, record), "stranger stays out with the bypass on");
            config.set(OPTION, false);
            helper.assertFalse(OwnerSecurity.canAccess(op, record), "op stays out with the bypass off");
            helper.assertTrue(OwnerSecurity.canAccess(owner, record), "owner with the bypass off");
            helper.assertTrue(OwnerSecurity.canAccess(op, record.withTrusted(op.getUUID())), "trusted op, bypass off");
            helper.assertTrue(OwnerSecurity.canAccess(op, OwnerRecord.UNOWNED), "unowned, bypass off");
        } finally {
            config.set(OPTION, previous);
        }
        helper.succeed();
    }

    private static void ownerRecordRoundTrip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        OwnerRecord record = OwnerRecord.of(player(level, "sec_saved")).withTrusted(UUID.randomUUID())
                .withTeamAccess(false);
        Tag saved = OwnerRecord.CODEC.encodeStart(NbtOps.INSTANCE, record).getOrThrow();
        helper.assertValueEqual(OwnerRecord.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow(), record, "saved form");
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        OwnerRecord.STREAM_CODEC.encode(buf, record);
        helper.assertValueEqual(OwnerRecord.STREAM_CODEC.decode(buf), record, "synced form");
        ItemStack stack = new ItemStack(Items.CHEST);
        stack.set(ModDataComponents.OWNER.get(), record);
        Tag stackTag = ItemStack.CODEC
                .encodeStart(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack).getOrThrow();
        ItemStack loaded = ItemStack.CODEC
                .parse(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), stackTag).getOrThrow();
        helper.assertValueEqual(loaded.getOrDefault(ModDataComponents.OWNER.get(), OwnerRecord.UNOWNED), record,
                "item component");
        helper.succeed();
    }
}
