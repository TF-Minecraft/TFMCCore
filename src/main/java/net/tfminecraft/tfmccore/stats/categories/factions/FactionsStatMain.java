package net.tfminecraft.tfmccore.stats.categories.factions;

import java.util.UUID;

import net.tfminecraft.simplefactions.war.battle.events.BattleEndedEvent;
import net.tfminecraft.tfmccore.stats.StatManager;

public final class FactionsStatMain {
    private static final String CATEGORY_ID = "factions";
    private static final String BATTLES_JOINED_KEY = "battles_joined";

    public void handle(BattleEndedEvent event) {
        if (!StatManager.isInitialized()) {
            return;
        }

        if (!event.hasWinner()) {
            return;
        }

        StatManager manager = StatManager.getInstance();
        for (UUID participantId : event.getParticipantIds()) {
            if (participantId == null) {
                continue;
            }
            manager.increment(participantId, CATEGORY_ID, BATTLES_JOINED_KEY, 1L);
        }
    }
}
