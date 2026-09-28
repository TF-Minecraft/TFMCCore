package net.tfminecraft.tfmccore.tfmc;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;

/** Side effects of the /tfmc subcommands, separated from the command tree for testing. */
public interface TfmcActions {

    void consoleCommand(String command);

    void playerCommand(Player player, String command);

    void sendResourcePack(Player player);

    void broadcast(Component message);

    /** Gives one of each item, dropping anything that does not fit at the player's feet. */
    void giveItems(Player player, List<Material> items);

    /** Sets a LuckPerms permission to true or false for the player in this server's context. */
    CompletableFuture<Void> setPermission(Player player, String permission, boolean granted);

    /** Moves the player one step along a LuckPerms track; completes with the new group. */
    CompletableFuture<Optional<String>> stepTrack(Player player, String track, boolean promote);
}
