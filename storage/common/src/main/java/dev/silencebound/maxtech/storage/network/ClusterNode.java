package dev.silencebound.maxtech.storage.network;

/**
 * A block of a controller cluster: a block of a multiblock controller or an attached module (PRD 7.2, Controller; M7R
 * item 1). Cluster blocks link to each other on every side. Only those that {@link #linksOutside()} (controller blocks)
 * also link to storage cables and other devices; the network routes through them only over the root links their
 * controller allows ({@link NetworkController#rootLinks()}), never from one cluster block to another. Expansions do not
 * count as devices. Server thread only.
 */
public interface ClusterNode {
    /**
     * Whether this block links to storage cables and devices that are not part of a cluster. Read whenever links are
     * re-read, so the answer must not change while the block stays the same.
     */
    boolean linksOutside();
}
