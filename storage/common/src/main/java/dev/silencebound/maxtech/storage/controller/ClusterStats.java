package dev.silencebound.maxtech.storage.controller;

import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.card.CardKind;
import dev.silencebound.maxtech.storage.controller.module.ModuleKind;

/**
 * What a controller multiblock gives its network (PRD 7.2, Controller): the sum of its active controller blocks' FE
 * buffer and input and what its upgrade cards add (M7R item 2). The security module and network anchor are network
 * devices since M7R item 4, read from the network, not from here. Root links are not counted here: every face of an
 * active block that touches a cable or device is one ({@code NetworkState}). Pure arithmetic over counts and
 * {@link Numbers}, so it is unit-tested; sums saturate instead of overflowing.
 *
 * @param blocks
 *            active controller blocks, the primary included; at least 1
 * @param bufferMu
 *            FE the controller holds
 * @param inputPerTick
 *            FE the controller takes in per tick
 * @param batchPercent
 *            device operation size in percent of {@code network.batch.*}
 * @param energyPercent
 *            what an operation's FE cost is reduced to (efficiency cores), 100 without
 * @param surgeReservePercent
 *            FE level in percent below which automation waits (surge regulators), 0 without
 */
public record ClusterStats(int blocks, long bufferMu, long inputPerTick, int batchPercent, int energyPercent,
        int surgeReservePercent) {
    /** The config numbers the stats are made of; {@link #fromConfig()} reads the server config. */
    public record Numbers(long blockBufferMu, long blockInputPerTick, int amplifierPercent, int amplifierMaxPercent,
            Modules modules, Cards cards) {
        /** The numbers as configured now; server thread. */
        public static Numbers fromConfig() {
            return new Numbers(StorageServerConfig.CONTROLLER_BUFFER_MU.getAsLong(),
                    StorageServerConfig.CONTROLLER_INPUT_PER_TICK.getAsLong(),
                    StorageServerConfig.AMPLIFIER_BATCH_PERCENT.getAsInt(),
                    StorageServerConfig.AMPLIFIER_MAX_BATCH_PERCENT.getAsInt(), Modules.fromConfig(),
                    Cards.fromConfig());
        }
    }

    /**
     * The upgrade card slots of a controller (M7R item 2): {@code base} slots for one block and {@code perBlock} more
     * for each further active block, never more than {@code max} (nor {@link #MOST}), and how many cards of each kind
     * count.
     *
     * @param amplifiers
     *            batch amplifier cards that count; more add nothing
     * @param efficiencyCores
     *            efficiency core cards that count
     * @param surgeRegulators
     *            surge regulator cards that count
     */
    public record Cards(int base, int perBlock, int max, int amplifiers, int efficiencyCores, int surgeRegulators) {
        /** The most card slots a controller has, whatever the config says; its saved slot list has this size. */
        public static final int MOST = 12;

        /** The numbers as configured now; server thread. */
        public static Cards fromConfig() {
            return new Cards(StorageServerConfig.CARD_SLOTS_BASE.getAsInt(),
                    StorageServerConfig.CARD_SLOTS_PER_BLOCK.getAsInt(), StorageServerConfig.CARD_SLOTS_MAX.getAsInt(),
                    StorageServerConfig.CARD_LIMIT_AMPLIFIERS.getAsInt(),
                    StorageServerConfig.CARD_LIMIT_EFFICIENCY.getAsInt(),
                    StorageServerConfig.CARD_LIMIT_SURGE.getAsInt());
        }

        /** The card slots of a controller of {@code blocks} active blocks: from 0 to {@link #MOST}. */
        public int slots(int blocks) {
            long slots = (long) Math.max(0, base) + (long) Math.max(0, perBlock) * Math.max(0, blocks - 1);
            return (int) Math.max(0, Math.min(Math.min(max, MOST), slots));
        }

        /** How many cards of {@code kind} count at most; more may sit in the slots but add nothing. */
        public int limit(CardKind kind) {
            return Math.max(0, switch (kind) {
                case BATCH_AMPLIFIER -> amplifiers;
                case EFFICIENCY_CORE -> efficiencyCores;
                case SURGE_REGULATOR -> surgeRegulators;
            });
        }
    }

    /**
     * The config numbers of the further modules (M6c2 area G).
     *
     * @param efficiencyPercent
     *            percent each efficiency core takes off an operation's FE cost
     * @param efficiencyMaxPercent
     *            most percent all cores take off together
     * @param surgeReservePercent
     *            FE level in percent below which a controller with a surge regulator holds automation back
     */
    public record Modules(int efficiencyPercent, int efficiencyMaxPercent, int surgeReservePercent) {
        /** No module does anything. */
        public static final Modules NONE = new Modules(0, 0, 0);

        /** The numbers as configured now; server thread. */
        public static Modules fromConfig() {
            return new Modules(StorageServerConfig.EFFICIENCY_PERCENT.getAsInt(),
                    StorageServerConfig.EFFICIENCY_MAX_PERCENT.getAsInt(),
                    StorageServerConfig.SURGE_RESERVE_PERCENT.getAsInt());
        }
    }

    /**
     * The size steps of a multiblock (M7R item 1): {@code base} blocks without research, {@code step1} to {@code step3}
     * after each size research node, never more than {@code max}.
     */
    public record Size(int base, int step1, int step2, int step3, int max) {
        /** The steps as configured now; server thread. */
        public static Size fromConfig() {
            return new Size(StorageServerConfig.SIZE_BASE.getAsInt(), StorageServerConfig.SIZE_STEP_1.getAsInt(),
                    StorageServerConfig.SIZE_STEP_2.getAsInt(), StorageServerConfig.SIZE_STEP_3.getAsInt(),
                    StorageServerConfig.SIZE_MAX.getAsInt());
        }

        /**
         * The cap for a team that has unlocked the size research nodes {@code first}, {@code second} and {@code third}:
         * the highest unlocked step (a later node counts even when an earlier one is missing, so a pack that reorders
         * the tree still gets its steps), never below 1 or above {@link #max()}.
         */
        public int cap(boolean first, boolean second, boolean third) {
            int step = third ? step3 : second ? step2 : first ? step1 : base;
            return Math.max(1, Math.min(Math.max(1, max), step));
        }
    }

    /** One controller block without modules or cards. */
    public static ClusterStats single(Numbers numbers) {
        return of(1, new int[ModuleKind.count()], numbers);
    }

    /**
     * The stats of {@code blocks} active controller blocks with {@code counts[kind.index()]} attached modules of each
     * kind (a shorter array counts the missing kinds as 0). For the card kinds ({@link ModuleKind#card()}) the count is
     * the installed cards, of which at most {@link Cards#limit} count. Channel arrays, capacitor banks and device buses
     * add nothing any more (M7R item 1).
     */
    public static ClusterStats of(int blocks, int[] counts, Numbers n) {
        int size = Math.max(1, blocks);
        Cards cards = n.cards();
        int amplifiers = Math.min(count(counts, ModuleKind.BATCH_AMPLIFIER), cards.limit(CardKind.BATCH_AMPLIFIER));
        Modules m = n.modules();
        long amplified = Math.min((long) amplifiers * n.amplifierPercent(), n.amplifierMaxPercent());
        int cores = Math.min(count(counts, ModuleKind.EFFICIENCY_CORE), cards.limit(CardKind.EFFICIENCY_CORE));
        long saved = Math.min((long) cores * m.efficiencyPercent(), m.efficiencyMaxPercent());
        int regulators = Math.min(count(counts, ModuleKind.SURGE_REGULATOR), cards.limit(CardKind.SURGE_REGULATOR));
        int surge = regulators > 0 ? m.surgeReservePercent() : 0;
        return new ClusterStats(size, times(size, n.blockBufferMu()), times(size, n.blockInputPerTick()),
                saturatedInt(100 + amplified), (int) Math.max(1, 100 - clampPercent(saved)), clampPercent(surge));
    }

    private static int count(int[] counts, ModuleKind kind) {
        int i = kind.index();
        return i < counts.length ? Math.max(0, counts[i]) : 0;
    }

    private static long times(int count, long each) {
        try {
            return Math.multiplyExact(count, Math.max(0, each));
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static int saturatedInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }

    private static int clampPercent(long value) {
        return (int) Math.max(0, Math.min(100, value));
    }
}
