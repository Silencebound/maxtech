package dev.silencebound.maxtech.lib.shim.saving;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

/**
 * Port only (1.21.1): the problem reporter of a block entity's rewritten {@code saveAdditional}/{@code loadAdditional}
 * (port/rules/saving.py). 26.1's BlockEntity collects the problems of one save or load and logs them as a tree; here
 * each problem is logged as it happens, with its path, at the same level.
 */
public final class PortSaving {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 26.1's {@code ExtraCodecs.NBT}: any tag, passed through as is with NbtOps and converted with other ops. */
    public static final Codec<Tag> NBT = Codec.PASSTHROUGH.xmap(d -> d.convert(NbtOps.INSTANCE).getValue(),
            tag -> new Dynamic<>(NbtOps.INSTANCE, tag));

    private PortSaving() {
    }

    /** A reporter that logs each problem with the field path below {@code owner} (a block entity's {@link #problemPath}). */
    public static ProblemReporter reporter(Object owner) {
        return new Logging(owner instanceof BlockEntity be ? problemPath(be).get() : owner.getClass().getSimpleName(),
                "");
    }

    /** 26.1's {@code BlockEntity.problemPath()}: the block entity's type and position. */
    public static ProblemReporter.PathElement problemPath(BlockEntity be) {
        return () -> {
            ResourceLocation type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
            return (type != null ? type.toString() : be.getClass().getName()) + "@" + be.getBlockPos();
        };
    }

    private record Logging(String owner, String path) implements ProblemReporter {
        @Override
        public ProblemReporter forChild(PathElement element) {
            return new Logging(owner, path + element.get());
        }

        @Override
        public void report(Problem problem) {
            LOGGER.warn("[{}] Serialization error at {}: {}", owner, path.isEmpty() ? "root" : path,
                    problem.description());
        }
    }
}
