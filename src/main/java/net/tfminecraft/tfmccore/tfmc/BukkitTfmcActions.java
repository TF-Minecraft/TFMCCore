package net.tfminecraft.tfmccore.tfmc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ContextSetFactory;
import net.luckperms.api.context.DefaultContextKeys;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.track.Track;
import net.luckperms.api.track.TrackManager;
import net.tfminecraft.tfmccore.TFMCCore;

/** Live implementation: Bukkit for commands and items, the LuckPerms API for permissions and tracks. */
public final class BukkitTfmcActions implements TfmcActions {

    private static final Logger LOGGER = Logger.getLogger("TFMCCore");

    private final TFMCCore plugin;
    private final Supplier<LuckPerms> luckPerms;
    // Tail of each player's LuckPerms changes, so a quick promote then demote cannot interleave
    private final Map<UUID, CompletableFuture<?>> pending = new ConcurrentHashMap<>();

    public BukkitTfmcActions(TFMCCore plugin) {
        this(plugin, BukkitTfmcActions::lookUpLuckPerms);
    }

    BukkitTfmcActions(TFMCCore plugin, Supplier<LuckPerms> luckPerms) {
        this.plugin = plugin;
        this.luckPerms = luckPerms;
    }

    @Override
    public void consoleCommand(String command) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    @Override
    public void playerCommand(Player player, String command) {
        player.performCommand(command);
    }

    @Override
    public void sendResourcePack(Player player) {
        plugin.sendResourcePack(player);
    }

    @Override
    public void broadcast(Component message) {
        Bukkit.getServer().sendMessage(message);
    }

    @Override
    public void giveItems(Player player, List<Material> items) {
        for (Material item : items) {
            player.getInventory().addItem(new ItemStack(item)).values()
                    .forEach(leftover -> player.getWorld().dropItem(player.getLocation(), leftover));
        }
    }

    @Override
    public CompletableFuture<Void> setPermission(Player player, String permission, boolean granted) {
        LuckPerms api = luckPerms.get();
        if (api == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("LuckPerms is not available"));
        }
        // Matches the old "lp user <player> permission set|unset <node> server=<this server>"
        String server = api.getServerName();
        ContextSetFactory contexts = api.getContextManager().getContextSetFactory();
        ImmutableContextSet context = "global".equalsIgnoreCase(server)
                ? contexts.immutableEmpty()
                : contexts.immutableOf(DefaultContextKeys.SERVER_KEY, server);
        return withFreshUser(api, player, user -> {
            user.data().clear(NodeType.PERMISSION.predicate(node ->
                    node.getPermission().equalsIgnoreCase(permission) && node.getContexts().equals(context)));
            // An explicit false overrides a true inherited from a group or the global context
            user.data().add(api.getNodeBuilderRegistry().forPermission()
                    .permission(permission).value(granted).context(context).build());
            return save(api, user);
        });
    }

    @Override
    public CompletableFuture<Optional<String>> stepTrack(Player player, String trackName, boolean promote) {
        LuckPerms api = luckPerms.get();
        if (api == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("LuckPerms is not available"));
        }
        TrackManager tracks = api.getTrackManager();
        Track track = tracks.getTrack(trackName);
        if (track == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No LuckPerms track " + trackName));
        }
        // Global context, like "lp user <player> promote|demote <track>"
        ImmutableContextSet global = api.getContextManager().getContextSetFactory().immutableEmpty();
        return withFreshUser(api, player, user -> {
            Optional<String> group;
            String status;
            if (promote) {
                var result = track.promote(user, global);
                group = result.wasSuccessful() ? result.getGroupTo() : Optional.empty();
                status = result.getStatus().name();
            } else {
                var result = track.demote(user, global);
                group = result.wasSuccessful() ? result.getGroupTo() : Optional.empty();
                status = result.getStatus().name();
            }
            if (group.isEmpty()) {
                // e.g. AMBIGUOUS_CALL when the player holds more than one group on the track
                LOGGER.warning("LuckPerms could not " + (promote ? "promote " : "demote ") + player.getName()
                        + " on " + trackName + ": " + status);
                return CompletableFuture.completedFuture(group);
            }
            return save(api, user).thenApply(done -> group);
        });
    }

    @Override
    public FlightState startFlight(Player player, float speed) {
        FlightState previous = new FlightState(player.getAllowFlight(), player.isFlying(), player.getFlySpeed());
        player.setAllowFlight(true);
        player.setFlySpeed(Math.max(-1f, Math.min(1f, speed)));
        return previous;
    }

    @Override
    public void endFlight(Player player, FlightState previous) {
        player.setFlySpeed(previous.flySpeed());
        player.setAllowFlight(previous.allowFlight());
        player.setFlying(previous.allowFlight() && previous.flying());
        if (!player.getAllowFlight() && !player.isOnGround()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 100, 0, false, false, true));
        }
    }

    @Override
    public Runnable later(long ticks, Runnable task) {
        BukkitTask scheduled = Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
        return scheduled::cancel;
    }

    /**
     * Reloads the user from storage, then changes and saves them, one change per player at a time.
     * This is what the lp commands and UserManager#modifyUser do. LuckPerms saves only the changes
     * recorded since the last load, so changing the cached user instead can lose the removal of
     * the old group and leave the player in two groups on one track.
     */
    private <T> CompletableFuture<T> withFreshUser(LuckPerms api, Player player, Function<User, CompletableFuture<T>> change) {
        UUID id = player.getUniqueId();
        CompletableFuture<T> done = new CompletableFuture<>();
        CompletableFuture<?> previous = pending.put(id, done);
        CompletableFuture<?> ready = previous == null
                ? CompletableFuture.completedFuture(null)
                : previous.handle((value, error) -> null);
        ready.thenCompose(ignored -> api.getUserManager().loadUser(id))
                .thenCompose(change)
                .whenComplete((value, error) -> {
                    pending.remove(id, done);
                    if (error != null) {
                        done.completeExceptionally(error);
                    } else {
                        done.complete(value);
                    }
                });
        return done;
    }

    private static CompletableFuture<Void> save(LuckPerms api, User user) {
        // The lp command pushes changes to the other servers; the API leaves that to us
        return api.getUserManager().saveUser(user)
                .thenRun(() -> api.getMessagingService().ifPresent(service -> service.pushUserUpdate(user)));
    }

    private static LuckPerms lookUpLuckPerms() {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            return null;
        }
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        return provider == null ? null : provider.getProvider();
    }
}
