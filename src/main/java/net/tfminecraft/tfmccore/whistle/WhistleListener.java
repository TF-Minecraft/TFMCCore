package net.tfminecraft.tfmccore.whistle;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tfmccore.TFMCCore;

public class WhistleListener implements Listener {

    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private Sound cachedSound;
    private boolean soundResolved;

    public void invalidateSound() {
        cachedSound = null;
        soundResolved = false;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = event.getItem();
        if (item == null) return;
        if (!isAnimalWhistle(item)) return;
        event.setCancelled(true);

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = cooldowns.getOrDefault(uuid, 0L);
        long remaining = lastUse + WhistleConfig.cooldownSeconds * 1000L - now;
        if (remaining > 0) {
            sendMessage(player, WhistleConfig.cooldownMessage.replace("%seconds%", String.valueOf((remaining + 999) / 1000)));
            return;
        }
        cooldowns.put(uuid, now);

        Sound sound = resolveSound();
        if (sound != null) {
            player.getWorld().playSound(player.getLocation(), sound, SoundCategory.AMBIENT, WhistleConfig.soundVolume, WhistleConfig.soundPitch);
        }

        double radius = WhistleConfig.detectionRadius;
        int count = 0;
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!WhistleConfig.whitelistedAnimals.contains(living.getType())) continue;
            living.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, WhistleConfig.glowDuration * 20, 0, false, false));
            count++;
        }
        if (count > 0) {
            sendMessage(player, WhistleConfig.highlightedMessage.replace("%count%", String.valueOf(count)));
        } else {
            sendMessage(player, WhistleConfig.noAnimalsMessage);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cooldowns.remove(event.getPlayer().getUniqueId());
    }

    private boolean isAnimalWhistle(ItemStack item) {
        try {
            return TLibs.getItemAPI().getChecker().checkItemWithPath(item, WhistleConfig.itemPath);
        } catch (Exception ex) {
            warn("Failed to validate animal whistle: " + ex.getMessage());
            return false;
        }
    }

    private Sound resolveSound() {
        if (soundResolved) return cachedSound;
        soundResolved = true;
        String raw = WhistleConfig.soundName;
        if (raw == null || raw.isEmpty()) return null;
        String normalized = raw.toLowerCase().replace('_', '.');
        NamespacedKey key = NamespacedKey.fromString(normalized.contains(":") ? normalized : "minecraft:" + normalized);
        cachedSound = key == null ? null : Registry.SOUNDS.get(key);
        if (cachedSound == null) {
            // The naive underscore replace misses keys such as "item.goat_horn.sound.6",
            // so fall back to matching registry entries with all separators stripped.
            String flattened = raw.toLowerCase().replace("_", "");
            for (Sound candidate : Registry.SOUNDS) {
                String candidateKey = Registry.SOUNDS.getKey(candidate).getKey().replace(".", "").replace("_", "");
                if (candidateKey.equalsIgnoreCase(flattened)) {
                    cachedSound = candidate;
                    break;
                }
            }
        }
        if (cachedSound == null) {
            warn("Invalid sound type in animal whistle config: " + raw);
        }
        return cachedSound;
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private void sendMessage(Player player, String message) {
        if (message == null || message.isEmpty()) return;
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    private void warn(String message) {
        TFMCCore instance = TFMCCore.getInstance();
        if (instance != null) {
            instance.getLogger().warning(message);
        }
    }
}
