package com.bgsoftware.superiorskyblock.player;

import net.infnetwork.skyblock.profile.ProfileKeys;
import org.bukkit.Bukkit;

import java.util.UUID;

/**
 * Maps an authenticated Minecraft account to the island data of its selected Skyblock-Core character.
 * Without Skyblock-Core the account is the character, as upstream. The profile API classes are provided by
 * Skyblock-Core at runtime, so they are only touched once found.
 */
public final class ProfileIdentities {

    private static volatile Boolean available;

    private ProfileIdentities() {
    }

    /**
     * A managed character uses a UUIDv8 with the SkyBlock prefix. Keep these records out of
     * vanilla player-name migration even when Skyblock-Core is unavailable.
     * Same check as {@code ProfileKeys.isCharacterId}, kept here so it works without Skyblock-Core.
     */
    public static boolean isCharacterId(UUID id) {
        return id != null && id.version() == 8 && (id.getMostSignificantBits() >>> 48) == 0x5342L;
    }

    public static UUID accountId(UUID characterId) {
        if (!isCharacterId(characterId))
            return characterId;

        ProfileKeys keys = keys();
        UUID accountId = keys == null ? null : keys.accountId(characterId);
        return accountId == null ? characterId : accountId;
    }

    public static UUID selectedCharacterId(UUID accountId) {
        ProfileKeys keys = keys();
        UUID characterId = keys == null ? accountId : keys.selectedCharacterId(accountId);
        if (characterId == null || characterId.equals(accountId))
            return characterId;
        if (!isCharacterId(characterId) || !accountId.equals(keys.accountId(characterId)))
            throw new IllegalStateException("Selected SkyBlock character does not belong to the authenticated account");
        return characterId;
    }

    private static ProfileKeys keys() {
        Boolean known = available;
        if (known == null) {
            try {
                Class.forName("net.infnetwork.skyblock.profile.ProfileKeys", false,
                        ProfileIdentities.class.getClassLoader());
                known = true;
            } catch (ClassNotFoundException | LinkageError error) {
                known = false;
            }
            available = known;
        }
        return known ? Bukkit.getServicesManager().load(ProfileKeys.class) : null;
    }

}
