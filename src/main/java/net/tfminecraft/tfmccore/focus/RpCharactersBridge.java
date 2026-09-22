package net.tfminecraft.tfmccore.focus;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.RPCharacters;

public final class RpCharactersBridge {

    private RpCharactersBridge() {}

    public static boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("RPCharacters");
    }

    public static RPCharacter getActiveCharacter(Player player) {
        if (!isAvailable() || player == null) {
            return null;
        }
        return RPCharacters.getActiveCharacter(player);
    }
}
