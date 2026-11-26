package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.authlib.GameProfile;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.machine.MachineRuns;
import dev.silencebound.maxtech.storage.machine.StorageMachineBlockEntity;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.jspecify.annotations.Nullable;

/**
 * The hooks research reads from machines: a placed machine is owned by its placer and keeps the owner across a save,
 * and a finished run reports its machine, owner, mode and outputs to {@link MachineRuns} listeners.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class MachineRunsGameTests {
    private static final BlockPos MACHINE = new BlockPos(2, 1, 1);
    private static final String NAME = "machine_runs_report_owner_and_outputs";
    /** The last run a lithograph at a test's position reported; tests run one at a time in their batch. */
    private static @Nullable Run last;

    private record Run(BlockPos pos, OwnerRecord owner, MachineKind kind, String mode, int wafers) {
    }

    static {
        StorageTests.function(NAME, StorageTestEnvironment.clearing(MachineRunsGameTests::reports));
        MachineRuns.listen((level, machine, owner, kind, mode, made) -> {
            int count = 0;
            for (ItemStack stack : made) {
                // The kerf is a chance output; only the wafers are certain.
                if (stack.is(MaterialContent.SILICON_WAFER.get())) {
                    count += stack.getCount();
                }
            }
            last = new Run(machine.getBlockPos(), owner, kind, mode.orElse(""), count);
        });
    }

    private MachineRunsGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("machine_runs"),
                StorageTestEnvironment.plain());
        StorageTests.test(event, environment, NAME, StorageTests.SMALL, 400);
    }

    private static void reports(GameTestHelper helper) {
        helper.setBlock(MACHINE, MachineContent.block(MachineKind.LITHOGRAPH));
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "placer"), ClientInformation.createDefault());
        BlockPos absolute = helper.absolutePos(MACHINE);
        helper.getBlockState(MACHINE).getBlock().setPlacedBy(helper.getLevel(), absolute, helper.getBlockState(MACHINE),
                player, ItemStack.EMPTY);
        StorageMachineBlockEntity machine = helper.getBlockEntity(MACHINE, StorageMachineBlockEntity.class);
        helper.assertTrue(machine.ownerRecord().isOwner(player.getUUID()), "the placer owns the machine");
        CompoundTag saved = machine.saveWithoutMetadata(helper.getLevel().registryAccess());
        machine.setOwner(OwnerRecord.UNOWNED);
        machine.loadCustomOnly(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
        helper.assertTrue(machine.ownerRecord().isOwner(player.getUUID()), "the owner is saved");
        last = null;
        MachineGameTests.insert(machine, new ItemStack(MaterialContent.SILICON.get()));
        MachineGameTests.charge(machine, 5_000);
        helper.startSequence().thenWaitUntil(() -> {
            Run run = last;
            if (run == null || !run.pos().equals(absolute)) {
                throw helper.assertionException(Component.literal("no run reported yet"));
            }
            helper.assertTrue(run.owner().isOwner(player.getUUID()), "owner reported");
            helper.assertValueEqual(run.kind(), MachineKind.LITHOGRAPH, "kind reported");
            helper.assertValueEqual(run.mode(), "slice", "mode reported");
            helper.assertValueEqual(run.wafers(), 3, "wafers reported");
        }).thenSucceed();
    }
}
