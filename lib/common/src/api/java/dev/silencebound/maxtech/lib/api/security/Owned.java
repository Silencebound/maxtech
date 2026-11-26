package dev.silencebound.maxtech.lib.api.security;

/**
 * Something with an owner: a block entity, a network, an item's data. {@link OwnerSecurity#canAccess} checks players
 * against it.
 */
public interface Owned {
    /**
     * The current owner record; {@link OwnerRecord#UNOWNED} when nobody owns it, never null. Called on the thread the
     * caller is on; implementations hand out their immutable record.
     */
    OwnerRecord ownerRecord();
}
