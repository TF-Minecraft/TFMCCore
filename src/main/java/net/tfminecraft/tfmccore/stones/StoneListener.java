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
    // text is the typed name while a namestone waits for its colour, null before that.
    private record Pending(Kind kind, int slot, ItemStack snapshot, ItemStack stone, BukkitTask timeout,
            String text) {
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
                new Pending(kind, event.getSlot(), target.clone(), one, timeout, null));

        items.msg(player,
                kind == Kind.LORE ? LorestoneConfig.promptLoreMessage : LorestoneConfig.promptNameMessage,
                "%timeout%", String.valueOf(seconds));
    }

    // Retain Bukkit chat-event ordering and String message semantics for existing integrations.
    @SuppressWarnings("deprecation")
    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        Pending prompt = pending.get(player.getUniqueId());
        if (prompt == null) {
            return;
        }

        event.setCancelled(true);

        String raw = event.getMessage();
        TFMCCore plugin = TFMCCore.getInstance();
        if (plugin != null && plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> apply(player, raw, prompt));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        abort(event.getPlayer(), null);
    }

    // Main thread: the palette's click runs /tcore stones colour <colour>, which lands here.
    public void chooseColour(Player player, String input) {
        Pending pd = pending.get(player.getUniqueId());
        if (pd == null || pd.text() == null) {
            items.msg(player, LorestoneConfig.expiredMessage);
            return;
        }
        apply(player, input, pd);
    }

    // Main thread: everything that touches the inventory.
    private void apply(Player player, String raw, Pending queued) {
        // A timeout or quit may have ended this prompt before its queued chat is processed.
        // The entry stays in the map until a terminal outcome, so async chat arriving
        // meanwhile is still recognised as an answer and never leaks to public chat.
        // The timeout task identifies one stone use across its steps: an answer queued
        // before the name was processed still answers the colour step, but never a new prompt.
        Pending pd = pending.get(player.getUniqueId());
        if (pd == null || pd.timeout() != queued.timeout()) {
            items.msg(player, LorestoneConfig.expiredMessage);
            return;
        }

        String text = TextUtil.sanitize(raw);

        if (text.equalsIgnoreCase("cancel")) {
            end(player, pd);
            refund(player, pd, LorestoneConfig.cancelledMessage);
            return;
        }

        // Second step of a rename: the name is known, this answer picks its colour.
        if (pd.text() != null) {
            String code = StoneColours.code(text);
            if (code == null) {
                items.msg(player, LorestoneConfig.invalidColourMessage);
                return;
            }
            end(player, pd);
            edit(player, pd, code + pd.text());
            return;
        }

        if (text.isBlank()) {
            end(player, pd);
            refund(player, pd, LorestoneConfig.emptyMessage);
            return;
        }

        // The limit is for typed text; "clear" is a command and must work under a tiny max-length.
        boolean clear = pd.kind() == Kind.LORE && text.equalsIgnoreCase("clear");
        int max = LorestoneConfig.maxLength;
        if (!clear && text.length() > max) {
            end(player, pd);
            refund(player, pd, LorestoneConfig.tooLongMessage, "%max%", String.valueOf(max));
            return;
        }

        // A plain name gets the colour palette; one typed with & codes already has its colour.
        if (pd.kind() == Kind.NAME && TextUtil.color(text).equals(text)) {
            pending.replace(player.getUniqueId(), pd,
                    new Pending(pd.kind(), pd.slot(), pd.snapshot(), pd.stone(), pd.timeout(), text));
            player.sendMessage(StoneColours.palette());
            items.msg(player, LorestoneConfig.pickColourMessage);
            return;
        }

        end(player, pd);
        edit(player, pd, text);
    }

    // Terminal outcome: close the prompt before the item is edited or the stone refunded.
    private void end(Player player, Pending pd) {
        pending.remove(player.getUniqueId(), pd);
        pd.timeout().cancel();
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private void edit(Player player, Pending pd, String text) {
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
        String applied;

        if (pd.kind() == Kind.LORE && text.equalsIgnoreCase("clear")) {
            if (!meta.hasLore()) {
                refund(player, pd, LorestoneConfig.noLoreMessage);
                return;
            }
            meta.setLore(null);
            applied = LorestoneConfig.clearedLoreMessage;
        } else if (pd.kind() == Kind.LORE) {
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.replaceAll(l -> l.startsWith("§r") ? l : "§r" + l);
            int maxLines = LorestoneConfig.maxLoreLines;
            if (lore.size() >= maxLines) {
                refund(player, pd, LorestoneConfig.tooManyLinesMessage, "%max%", String.valueOf(maxLines));
                return;
            }
            lore.add(formatted);
            meta.setLore(lore);
            applied = LorestoneConfig.appliedLoreMessage;
        } else {
            meta.setDisplayName(formatted);
            applied = LorestoneConfig.appliedNameMessage;
        }

        current.setItemMeta(meta);
        inventory.setItem(pd.slot(), current);
        items.msg(player, applied);
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
