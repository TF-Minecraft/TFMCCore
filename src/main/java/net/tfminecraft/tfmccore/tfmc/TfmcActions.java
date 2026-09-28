package net.tfminecraft.tfmccore.tfmc;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;

/** Side effects of the /tfmc subcommands, separated from the command tree for testing. */
public interface TfmcActions {

    /** A player's flight settings before a temporary change, so they can be put back. */
    record FlightState(boolean allowFlight, boolean flying, float flySpeed) {}

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

    /** Lets the player fly at {@code speed} and returns their previous flight settings. */
    FlightState startFlight(Player player, float speed);

    /** Restores flight settings, easing the player down if the change leaves them airborne. */
    void endFlight(Player player, FlightState previous);

    /** Runs {@code task} on the main thread after {@code ticks}; the returned action cancels it. */
    Runnable later(long ticks, Runnable task);
}
