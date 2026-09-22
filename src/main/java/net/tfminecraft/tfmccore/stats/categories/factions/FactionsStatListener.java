package net.tfminecraft.tfmccore.stats.categories.factions;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import net.tfminecraft.simplefactions.war.battle.events.BattleEndedEvent;

public final class FactionsStatListener implements Listener {
    private final FactionsStatMain main;

    public FactionsStatListener(FactionsStatMain main) {
        this.main = main;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBattleEnded(BattleEndedEvent event) {
        main.handle(event);
    }
}
