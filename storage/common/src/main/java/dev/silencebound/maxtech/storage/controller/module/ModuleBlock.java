package dev.silencebound.maxtech.storage.controller.module;

/** A block of a controller cluster that is one {@link ModuleKind}: the expansions and the status lights. */
public interface ModuleBlock {
    /** The kind this block is. */
    ModuleKind kind();
}
