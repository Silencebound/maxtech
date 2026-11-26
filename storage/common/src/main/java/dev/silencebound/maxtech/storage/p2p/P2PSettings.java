package dev.silencebound.maxtech.storage.p2p;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.StringRepresentable;

/**
 * An end's link, as saved and kept in its item ({@code maxtech_storage:p2p_link}): its role and frequency. Frequency 0
 * means not linked.
 */
public record P2PSettings(P2PRole role, long frequency) {
    public static final P2PSettings NONE = new P2PSettings(P2PRole.NONE, 0);
    private static final Codec<P2PRole> ROLE_CODEC = StringRepresentable.fromEnum(P2PRole::values);
    public static final Codec<P2PSettings> CODEC = RecordCodecBuilder
            .create(i -> i
                    .group(ROLE_CODEC.fieldOf("role").forGetter(P2PSettings::role),
                            Codec.LONG.fieldOf("frequency").forGetter(P2PSettings::frequency))
                    .apply(i, P2PSettings::new));

    /** Whether this names a link. */
    public boolean isLinked() {
        return role != P2PRole.NONE && frequency != 0;
    }
}
