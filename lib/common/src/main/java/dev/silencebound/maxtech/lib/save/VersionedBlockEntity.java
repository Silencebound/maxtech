package dev.silencebound.maxtech.lib.save;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A block entity whose saved data carries a {@link SaveFormat} version. Saving writes the version next to the
 * subclass's fields; loading migrates older data first and hands the result to {@link #loadSaved}, which subclasses
 * override instead of {@code loadAdditional} so every class in the hierarchy reads the migrated data. Data loaded from
 * a newer version is saved again with that version ({@link SaveFormat}).
 *
 * <p>
 * Server and client thread, as vanilla's load and save.
 */
public abstract class VersionedBlockEntity extends BlockEntity {
    protected VersionedBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** The format this block entity's data is saved in; the same instance on every call. */
    protected abstract SaveFormat saveFormat();

    /**
     * Loads the subclass's fields from data already in the current version. Overrides call {@code super.loadSaved}
     * first, as they would {@code loadAdditional}.
     */
    protected void loadSaved(ValueInput input) {
    }

    @Override
    protected final void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        SaveFormat format = saveFormat();
        format.noteLoaded(this, input);
        loadSaved(format.isCurrent(input) ? input : format.migrate(kind(), input));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        saveFormat().write(output, this);
    }

    private Identifier kind() {
        Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(getType());
        return id != null ? id : Identifier.fromNamespaceAndPath("maxtech_lib", "unregistered");
    }
}
