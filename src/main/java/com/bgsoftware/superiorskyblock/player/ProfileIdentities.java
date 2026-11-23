package com.bgsoftware.superiorskyblock.player;

import com.bgsoftware.superiorskyblock.api.player.ProfileIdentityProvider;
import org.bukkit.Bukkit;

import java.util.UUID;

public final class ProfileIdentities {

    private ProfileIdentities() {
    }

    public static UUID accountId(UUID characterId) {
        if (!ProfileIdentityProvider.isCharacterId(characterId))
            return characterId;

        ProfileIdentityProvider provider = Bukkit.getServicesManager().load(ProfileIdentityProvider.class);
        UUID accountId = provider == null ? null : provider.getAccountId(characterId);
        return accountId == null ? characterId : accountId;
    }

    public static UUID selectedCharacterId(UUID accountId) {
        ProfileIdentityProvider provider = Bukkit.getServicesManager().load(ProfileIdentityProvider.class);
        UUID characterId = provider == null ? accountId : provider.getSelectedCharacterId(accountId);
        if (characterId == null || characterId.equals(accountId))
            return characterId;
        if (!ProfileIdentityProvider.isCharacterId(characterId) || !accountId.equals(provider.getAccountId(characterId)))
            throw new IllegalStateException("Selected SkyBlock character does not belong to the authenticated account");
        return characterId;
    }

}
