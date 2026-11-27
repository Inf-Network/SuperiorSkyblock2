package com.bgsoftware.superiorskyblock.api.player;

import java.util.UUID;

/**
 * Maps an authenticated Minecraft account to the island data for its selected character.
 *
 * @deprecated SuperiorSkyblock2 now reads Skyblock-Core's shared {@code net.infnetwork.skyblock.profile.ProfileKeys}.
 * Kept for API compatibility; Skyblock-Core still registers it.
 */
@Deprecated
public interface ProfileIdentityProvider {

    /**
     * Returns the Minecraft account for a character, or null if the character is unknown.
     */
    UUID getAccountId(UUID characterId);

    /**
     * Returns the selected character, the account UUID for a legacy session, or null
     * for a migrated account without an active character.
     */
    UUID getSelectedCharacterId(UUID accountId);

    /**
     * A managed character uses a UUIDv8 with the SkyBlock prefix. Keep these records out of
     * vanilla player-name migration even when the external profile registry is unavailable.
     */
    static boolean isCharacterId(UUID id) {
        return id.version() == 8 && (id.getMostSignificantBits() >>> 48) == 0x5342L;
    }

}
