package net.tfminecraft.tfmccore.stones;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.tfmccore.TFMCCore;
import net.tfminecraft.tfmccore.stones.StoneItems.Kind;
import net.tfminecraft.tfmccore.util.TextUtil;

// ====================================
// Drives the whole stone flow: click a stone onto an item, type the text in
// chat, get it applied. Every exit path removes the pending entry before it
// acts on it, so a stone is never refunded twice.
// ====================================
public class StoneListener implements Listener {

    // Snapshot of the target lets us notice the item being moved while the player types.
    private record Pending(Kind kind, int slot, ItemStack snapshot, ItemStack stone, BukkitTask timeout) {
    }

    // Read from the async chat thread, written from the main thread only.
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    private final StoneItems items;

    public StoneListener(StoneItems items) {
        this.items = items;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        TFMCCore plugin = TFMCCore.getInstance();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // Creative cursor handling is client-authoritative, so leave it alone.
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }

        ItemStack cursor = event.getCursor();
        Kind kind = items.kindOf(cursor);
        if (kind == null) {
            return;
        }

        // Only the player's own inventory: chests and other menus keep normal behaviour.
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(player.getInventory())) {
            return;
        }

        ItemStack target = event.getCurrentItem();
        if (target == null || target.getType().isAir()) {
            return;
        }

        // Target is itself a stone: let vanilla stacking/swap happen untouched.
        if (items.kindOf(target) != null) {
            return;
        }

        if (!player.hasPermission("tfmccore.stones.use")) {
            return;
        }

        if (pending.containsKey(player.getUniqueId())) {
            return;
        }

        if (items.isBlacklisted(target)) {
            event.setCancelled(true);
            items.msg(player, LorestoneConfig.cannotApplyMessage);
            return;
        }

        if (target.getAmount() > 1) {
            event.setCancelled(true);
            items.msg(player, LorestoneConfig.stackedMessage);
            return;
        }

        event.setCancelled(true);

        // Consume exactly one stone from the cursor; keep a single one for refunds.
        ItemStack one = cursor.clone();
        one.setAmount(1);

        ItemStack rest = cursor.clone();
        rest.setAmount(cursor.getAmount() - 1);
        event.getView().setCursor(rest.getAmount() <= 0 ? null : rest);

        long seconds = Math.max(1, LorestoneConfig.promptTimeoutSeconds);
        BukkitTask timeout = plugin.getServer().getScheduler()
                .runTaskLater(plugin, () -> abort(player, LorestoneConfig.timeoutMessage), seconds * 20L);

        // getSlot(), not getRawSlot(): the clicked inventory is the player inventory.
        pending.put(player.getUniqueId(),
                new Pending(kind, event.getSlot(), target.clone(), one, timeout));

        items.msg(player,
                kind == Kind.LORE ? LorestoneConfig.promptLoreMessage : LorestoneConfig.promptNameMessage,
                "%timeout%", String.valueOf(seconds));
    }

    // Retain Bukkit chat-event ordering and String message semantics for existing integrations.
    @SuppressWarnings("deprecation")
    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!pending.containsKey(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);

        String raw = event.getMessage();
        TFMCCore plugin = TFMCCore.getInstance();
        if (plugin != null && plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> apply(player, raw));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        abort(event.getPlayer(), null);
    }

    // Main thread: everything that touches the inventory.
    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private void apply(Player player, String raw) {
        Pending pd = pending.remove(player.getUniqueId());
        if (pd == null) {
            items.msg(player, LorestoneConfig.expiredMessage);
            return;
        }
        pd.timeout().cancel();

        String text = TextUtil.sanitize(raw);

        if (text.equalsIgnoreCase("cancel")) {
            refund(player, pd, LorestoneConfig.cancelledMessage);
            return;
        }

        if (text.isBlank()) {
            refund(player, pd, LorestoneConfig.emptyMessage);
            return;
        }

        int max = LorestoneConfig.maxLength;
        if (text.length() > max) {
            refund(player, pd, LorestoneConfig.tooLongMessage, "%max%", String.valueOf(max));
            return;
        }

        PlayerInventory inventory = player.getInventory();
        ItemStack current = inventory.getItem(pd.slot());
        if (current == null || current.getAmount() != 1 || !current.isSimilar(pd.snapshot())) {
            refund(player, pd, LorestoneConfig.itemMovedMessage);
            return;
        }

        ItemMeta meta = current.getItemMeta();
        if (meta == null) {
            refund(player, pd, LorestoneConfig.itemMovedMessage);
            return;
        }

        // The leading reset code makes CraftChatMessage emit an explicit italic=false, so the
        // line renders upright instead of picking up the vanilla italic lore/name default.
        // ponytail: pre-existing lore lines lose that explicit italic=false when round-tripped
        // through getLore()/setLore(), so every line below is re-stamped with the same reset
        // prefix rather than only the newly added one. Upgrade path is the Paper API plus
        // Adventure components, which carry decorations explicitly and don't need this.
        String formatted = "§r" + TextUtil.color(text);

        if (pd.kind() == Kind.LORE) {
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.replaceAll(l -> l.startsWith("§r") ? l : "§r" + l);
            int maxLines = LorestoneConfig.maxLoreLines;
            if (lore.size() >= maxLines) {
                refund(player, pd, LorestoneConfig.tooManyLinesMessage, "%max%", String.valueOf(maxLines));
                return;
            }
            lore.add(formatted);
            meta.setLore(lore);
        } else {
            meta.setDisplayName(formatted);
        }

        current.setItemMeta(meta);
        inventory.setItem(pd.slot(), current);

        items.msg(player, pd.kind() == Kind.LORE
                ? LorestoneConfig.appliedLoreMessage
                : LorestoneConfig.appliedNameMessage);
    }

    // Ends a prompt without applying anything. A null message stays silent (quit, shutdown).
    private void abort(Player player, String message) {
        Pending pd = pending.remove(player.getUniqueId());
        if (pd == null) {
            return;
        }
        pd.timeout().cancel();
        refund(player, pd, message);
    }

    private void refund(Player player, Pending pd, String message, String... pairs) {
        items.giveOrDrop(player, pd.stone());
        if (message != null) {
            items.msg(player, message, pairs);
        }
    }

    // Called on disable so a reload does not swallow held stones.
    public void refundAll() {
        TFMCCore plugin = TFMCCore.getInstance();
        if (plugin == null) {
            pending.clear();
            return;
        }
        for (UUID uuid : new ArrayList<>(pending.keySet())) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                abort(player, null);
            } else {
                // Player already offline: happens on a real server shutdown, where the
                // server disconnects players before plugins are disabled, not only /reload.
                Pending pd = pending.get(uuid);
                if (pd != null) {
                    plugin.getLogger().warning("Could not refund " + pd.kind() + " stone ("
                            + pd.stone().getType() + ") to offline player " + uuid);
                }
                pending.remove(uuid);
            }
        }
    }
}
