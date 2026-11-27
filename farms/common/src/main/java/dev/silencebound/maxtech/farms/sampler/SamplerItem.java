package dev.silencebound.maxtech.farms.sampler;

import dev.silencebound.maxtech.farms.config.FarmsServerConfig;
import dev.silencebound.maxtech.farms.registry.FarmsContent;
import dev.silencebound.maxtech.farms.specimen.Specimen;
import dev.silencebound.maxtech.farms.specimen.SpecimenItem;
import dev.silencebound.maxtech.farms.specimen.SpecimenKind;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Takes a specimen from a living mob: hold use on the mob for {@link #USE_TICKS} ticks. Releasing, the mob dying or
 * despawning, or leaving reach cancels at no cost; completing spends {@code sampler.muPerSample} MU and gives a fresh
 * specimen, leaving the mob unharmed. Players, {@code c:bosses} and anything that is not a mob are refused, as is a
 * sampler without the charge for one sample. The charge is MU in {@link FarmsContent#SAMPLER_CHARGE}.
 */
public class SamplerItem extends Item {
    /** How long sampling takes. */
    public static final int USE_TICKS = 40;
    /** The Farms accent, mint. */
    public static final int BAR_COLOR = 0x5fe08a;
    /** Mobs no sampler may sample. */
    public static final TagKey<EntityType<?>> BOSSES = TagKey.create(Registries.ENTITY_TYPE,
            Identifier.fromNamespaceAndPath("c", "bosses"));

    /** Mob each player is sampling; one map per side, since singleplayer runs both in one game. Owner thread only. */
    private static final Map<UUID, Target> SERVER_TARGETS = new HashMap<>();
    private static final Map<UUID, Target> CLIENT_TARGETS = new HashMap<>();

    public SamplerItem(Item.Properties properties) {
        super(properties);
    }

    /** Why a sample of {@code target} is refused; the lang key of the action bar message. */
    public enum Refusal {
        PLAYER("player"), NOT_A_MOB("not_a_mob"), BOSS("boss"), NO_CHARGE("no_charge");

        private final String key;

        Refusal(String name) {
            this.key = "message.maxtech_farms.sampler." + name;
        }

        public String translationKey() {
            return key;
        }
    }

    /** MU in {@code components}: a sampler without the charge component is full. Never above the capacity. */
    public static long charge(DataComponentGetter components) {
        long capacity = capacity();
        Long stored = components.get(FarmsContent.SAMPLER_CHARGE.get());
        return stored == null ? capacity : Mth.clamp(stored, 0, capacity);
    }

    /** Sets the charge of {@code stack} to {@code mu}. */
    public static void setCharge(ItemStack stack, long mu) {
        stack.set(FarmsContent.SAMPLER_CHARGE.get(), Math.max(0, mu));
    }

    /** The capacity in MU ({@code sampler.capacityMu}). */
    public static long capacity() {
        return FarmsServerConfig.samplerCapacityMu();
    }

    /** MU one sample costs ({@code sampler.muPerSample}). */
    public static long costPerSample() {
        return FarmsServerConfig.samplerMuPerSample();
    }

    /** Why {@code player} may not sample {@code target} with {@code sampler}, or null when they may. */
    public static @Nullable Refusal refusal(ItemStack sampler, Entity target) {
        if (target instanceof Player) {
            return Refusal.PLAYER;
        }
        if (!(target instanceof Mob) || !target.isAlive()) {
            return Refusal.NOT_A_MOB;
        }
        if (target.is(BOSSES)) {
            return Refusal.BOSS;
        }
        if (charge(sampler) < costPerSample()) {
            return Refusal.NO_CHARGE;
        }
        return null;
    }

    /**
     * Starts sampling {@code target} with the sampler in {@code hand}, or refuses with an action bar message. Both
     * sides call it (the client to show the use animation); the loader glue calls it ahead of the mob's own
     * interaction, so villagers and horses can be sampled too.
     */
    public static InteractionResult startSampling(Player player, InteractionHand hand, Entity target) {
        ItemStack sampler = player.getItemInHand(hand);
        if (!(sampler.getItem() instanceof SamplerItem) || player.isUsingItem()) {
            return InteractionResult.PASS;
        }
        Refusal refusal = refusal(sampler, target);
        if (refusal != null) {
            if (!player.level().isClientSide()) {
                player.sendOverlayMessage(Component.translatable(refusal.translationKey()));
            }
            return InteractionResult.FAIL;
        }
        targets(player.level()).put(player.getUUID(), new Target(target.getId(), target.getUUID()));
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    /** The mob {@code player} is sampling, while it is still alive and in reach; else null. */
    public static @Nullable LivingEntity currentTarget(Player player) {
        Target target = targets(player.level()).get(player.getUUID());
        if (target == null) {
            return null;
        }
        Entity entity = player.level().getEntity(target.id());
        if (!(entity instanceof LivingEntity living) || !entity.getUUID().equals(target.uuid()) || !entity.isAlive()
                || !player.isWithinEntityInteractionRange(entity, 0)) {
            return null;
        }
        return living;
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target,
            InteractionHand hand) {
        return startSampling(player, hand, target);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity user) {
        return USE_TICKS;
    }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        return ItemUseAnimation.BOW;
    }

    @Override
    public void onUseTick(Level level, LivingEntity user, ItemStack stack, int ticksRemaining) {
        if (!(user instanceof Player player) || currentTarget(player) == null) {
            user.stopUsingItem();
        }
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity user) {
        if (user instanceof Player player) {
            complete(player, stack);
        }
        return stack;
    }

    /**
     * Finishes a sample: spends the MU and gives {@code player} a fresh specimen of the mob in reach, into the
     * inventory or dropped at the player when it is full. Returns whether a specimen was given; nothing changes
     * otherwise.
     */
    public static boolean complete(Player player, ItemStack sampler) {
        LivingEntity target = currentTarget(player);
        targets(player.level()).remove(player.getUUID());
        if (player.level().isClientSide() || target == null || refusal(sampler, target) != null) {
            return false;
        }
        setCharge(sampler, charge(sampler) - costPerSample());
        ItemStack specimen = SpecimenItem.of(Specimen.fresh(SpecimenKind.MOB, EntityType.getKey(target.getType())));
        if (!player.getInventory().add(specimen)) {
            player.drop(specimen, false);
        }
        return true;
    }

    @Override
    public void onStopUsing(ItemStack stack, LivingEntity entity, int count) {
        targets(entity.level()).remove(entity.getUUID());
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return (int) Math.round(13.0 * charge(stack) / capacity());
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return BAR_COLOR;
    }

    @Override
    @SuppressWarnings("deprecation") // overriding is intended; only calling it is deprecated
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
            Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable("tooltip.maxtech_farms.sampler.charge", charge(stack), capacity())
                .withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("tooltip.maxtech_farms.sampler.cost", costPerSample())
                .withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("tooltip.maxtech_farms.sampler.usage").withStyle(ChatFormatting.GRAY));
    }

    private static Map<UUID, Target> targets(Level level) {
        return level.isClientSide() ? CLIENT_TARGETS : SERVER_TARGETS;
    }

    /** The sampled mob by network id, checked against its UUID in case the id was reused. */
    private record Target(int id, UUID uuid) {
    }
}
