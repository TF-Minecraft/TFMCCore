package net.tfminecraft.tfmccore.focus;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.tfmccore.TFMCCore;

public final class FocusService {

    private final FocusStore store;
    private final Map<UUID, FocusData> loaded = new ConcurrentHashMap<>();
    private BukkitTask regenTask;

    public FocusService(FocusStore store) {
        this.store = store;
    }

    public void restartRegen() {
        stopRegen();
        long interval = Math.max(1L, FocusConfig.regenIntervalTicks);
        regenTask = new BukkitRunnable() {
            @Override
            public void run() {
                tickRegen();
            }
        }.runTaskTimer(TFMCCore.getInstance(), interval, interval);
    }

    public void start() {
        restartRegen();
        if (RpCharactersBridge.isAvailable()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                RPCharacter character = RpCharactersBridge.getActiveCharacter(player);
                if (character != null) {
                    activate(player, character);
                }
            }
        }
    }

    public void shutdown() {
        stopRegen();
        saveAllOnline();
        loaded.clear();
    }

    public int getPoints(Player player) {
        FocusData data = dataFor(player);
        return data != null ? data.getPoints() : 0;
    }

    public boolean trySpend(Player player, int amount) {
        FocusData data = dataFor(player);
        return data != null && data.trySpend(amount);
    }

    public void grant(Player player, int amount) {
        FocusData data = dataFor(player);
        if (data != null) {
            data.grant(amount);
        }
    }

    public int getMax() {
        return FocusConfig.max;
    }

    public boolean restore(Player player) {
        FocusData data = dataFor(player);
        if (data == null) {
            return false;
        }
        data.setPoints(FocusConfig.max);
        store.save(data);
        return true;
    }

    public void activate(Player player, RPCharacter character) {
        if (player == null || character == null || character.getId() == null || character.getId().isBlank()) {
            return;
        }
        String characterId = character.getId();
        String owner = player.getUniqueId().toString();
        FocusData data = store.load(characterId);
        if (data == null) {
            data = FocusStore.tryMigrateFromResearch(characterId, owner);
            if (data == null) {
                data = FocusData.createNew(characterId, owner);
            }
            store.save(data);
        } else {
            data.setOwnerUuid(owner);
        }
        applyOfflineRegen(player, data);
        loaded.put(player.getUniqueId(), data);
    }

    public void savePrevious(Player player, RPCharacter previous) {
        if (player == null || previous == null) {
            return;
        }
        FocusData data = loaded.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        data.setCharacterId(previous.getId());
        store.save(data);
    }

    public void deactivate(Player player) {
        if (player == null) {
            return;
        }
        FocusData data = loaded.remove(player.getUniqueId());
        if (data != null) {
            store.save(data);
        }
    }

    public void saveAllOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            FocusData data = loaded.get(player.getUniqueId());
            if (data != null) {
                store.save(data);
            }
        }
    }

    private FocusData dataFor(Player player) {
        return player == null ? null : loaded.get(player.getUniqueId());
    }

    private void applyOfflineRegen(Player player, FocusData data) {
        long now = System.currentTimeMillis();
        long intervalMs = Math.max(1L, FocusConfig.regenIntervalTicks) * 50L;
        if (FocusConfig.offlineRegen) {
            data.applyRegenForElapsed(hourlyRate(player), intervalMs, now);
        } else {
            data.setLastRegenMs(now);
        }
    }

    private void tickRegen() {
        long now = System.currentTimeMillis();
        long intervalMs = Math.max(1L, FocusConfig.regenIntervalTicks) * 50L;
        for (Player player : Bukkit.getOnlinePlayers()) {
            FocusData data = loaded.get(player.getUniqueId());
            if (data == null) {
                continue;
            }
            data.applyRegenForElapsed(hourlyRate(player), intervalMs, now);
        }
    }

    private static double hourlyRate(Player player) {
        double hourly = FocusConfig.basePerHour;
        for (FocusConfig.RegenBonus bonus : FocusConfig.regenBonuses) {
            hourly += FocusAttributes.getTotal(player, bonus.mmocoreId) * bonus.extraPerHourPerPoint;
        }
        return hourly;
    }

    private void stopRegen() {
        if (regenTask != null) {
            regenTask.cancel();
            regenTask = null;
        }
    }
}
