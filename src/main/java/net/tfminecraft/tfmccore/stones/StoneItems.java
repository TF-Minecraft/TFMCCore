package net.tfminecraft.tfmccore.stones;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tfmccore.TFMCCore;
import net.tfminecraft.tfmccore.util.TextUtil;

public class StoneItems {

    public enum Kind { LORE, NAME }

    // Which stone this is, or null when it is not a stone.
    public Kind kindOf(ItemStack item) {
        // Every inventory click reaches this with an empty cursor, so bail before touching TLibs.
        if (item == null || item.getType().isAir()) return null;
        try {
            if (matches(item, LorestoneConfig.lorestonePath)) return Kind.LORE;
            if (matches(item, LorestoneConfig.namestonePath)) return Kind.NAME;
        } catch (Exception ex) {
            warn("Failed to check item against lorestones stone paths: " + ex.getMessage());
        }
        return null;
    }

    // Configured blacklist items reject stones. Stone-on-stone is guarded by the listener.
    public boolean isBlacklisted(ItemStack item) {
        if (item == null) return true;
        try {
            for (String path : LorestoneConfig.blacklist) {
                if (matches(item, path)) return true;
            }
        } catch (Exception ex) {
            // Fail closed: an unreadable item is treated as blacklisted.
            warn("Failed to check item against lorestones blacklist: " + ex.getMessage());
            return true;
        }
        return false;
    }

    public ItemStack template(Kind kind) {
        String path = kind == Kind.LORE ? LorestoneConfig.lorestonePath : LorestoneConfig.namestonePath;
        try {
            ItemStack stack = TLibs.getItemAPI().getCreator().getItemFromPath(path);
            if (stack == null) {
                warn("No item found for lorestones config path: " + path);
                return null;
            }
            return stack.clone();
        } catch (Exception ex) {
            warn("Failed to resolve lorestones item '" + path + "': " + ex.getMessage());
            return null;
        }
    }

    public void giveOrDrop(Player player, ItemStack item) {
        if (player.isDead()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
            return;
        }
        for (ItemStack leftover : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    public void msg(CommandSender sender, String template, String... placeholderPairs) {
        if (template == null || template.isEmpty()) return;
        String message = template;
        for (int i = 0; i + 1 < placeholderPairs.length; i += 2) {
            message = message.replace(placeholderPairs[i], placeholderPairs[i + 1]);
        }
        sender.sendMessage(TextUtil.color(message));
    }

    // Throws whatever TLibs throws; callers decide whether that fails open or closed.
    private boolean matches(ItemStack item, String path) {
        if (path == null || path.isEmpty()) return false;
        return TLibs.getItemAPI().getChecker().checkItemWithPath(item, path);
    }

    private void warn(String message) {
        TFMCCore instance = TFMCCore.getInstance();
        if (instance != null) {
            instance.getLogger().warning(message);
        }
    }
}
