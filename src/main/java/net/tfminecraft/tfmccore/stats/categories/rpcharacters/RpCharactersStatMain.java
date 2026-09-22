package net.tfminecraft.tfmccore.stats.categories.rpcharacters;

import java.util.Locale;
import java.util.UUID;

import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.chat.CharacterChatEvent;
import net.tfminecraft.rpcharacters.lifecycle.CharacterClassChangeEvent;
import net.tfminecraft.rpcharacters.lifecycle.CharacterCreatedEvent;
import net.tfminecraft.rpcharacters.lifecycle.CharacterRaceChangeEvent;
import net.tfminecraft.rpcharacters.permadeath.CharacterPermakillEvent;
import net.tfminecraft.rpcharacters.permadeath.PermakillCause;
import net.tfminecraft.tfmccore.stats.StatManager;

public final class RpCharactersStatMain {
    private static final String CATEGORY_ID = "rpcharacters";

    public void handle(CharacterCreatedEvent event) {
        if (!StatManager.isInitialized()) {
            return;
        }

        UUID ownerUuid = event.getOwnerUuid();
        if (ownerUuid == null) {
            return;
        }

        StatManager manager = StatManager.getInstance();
        manager.increment(ownerUuid, CATEGORY_ID, "characters_created", 1L);

        if (event.getCharacter() == null) {
            return;
        }

        String classId = event.getCharacter().getMMOClass();
        String raceId = raceIdFrom(event.getCharacter().getRace());
        incrementSpread(manager, ownerUuid, classId, raceId);
    }

    public void handle(CharacterClassChangeEvent event) {
        if (!StatManager.isInitialized()) {
            return;
        }

        UUID ownerUuid = event.getOwnerUuid();
        if (ownerUuid == null) {
            return;
        }

        StatManager manager = StatManager.getInstance();
        adjustClassSpread(manager, ownerUuid, event.getOldClassId(), event.getNewClassId());
    }

    public void handle(CharacterRaceChangeEvent event) {
        if (!StatManager.isInitialized()) {
            return;
        }

        UUID ownerUuid = event.getOwnerUuid();
        if (ownerUuid == null) {
            return;
        }

        StatManager manager = StatManager.getInstance();
        adjustRaceSpread(manager, ownerUuid, event.getOldRaceId(), event.getNewRaceId());
    }

    public void handle(CharacterPermakillEvent event) {
        if (!StatManager.isInitialized()) {
            return;
        }

        StatManager manager = StatManager.getInstance();

        if (event.getCharacter() != null && event.getPlayer() != null) {
            String classId = event.getCharacter().getMMOClass();
            String raceId = raceIdFrom(event.getCharacter().getRace());
            decrementSpread(manager, event.getPlayer().getUniqueId(), classId, raceId);
        }

        if (event.getCause() == PermakillCause.CHARACTER_MENU) {
            return;
        }

        if (event.getKiller() == null) {
            return;
        }

        manager.increment(
                event.getKiller().getUniqueId(), CATEGORY_ID, "characters_killed", 1L);
    }

    public void handle(CharacterChatEvent event) {
        if (!StatManager.isInitialized()) {
            return;
        }

        String channel = event.getChannel();
        if (channel == null || channel.isBlank()) {
            return;
        }

        if (event.getSender() == null) {
            return;
        }

        String statKey = "messages_" + channel.toLowerCase(Locale.ROOT);
        StatManager.getInstance().increment(event.getSender().getUniqueId(), CATEGORY_ID, statKey, 1L);
    }

    private static void incrementSpread(StatManager manager, UUID ownerUuid, String classId, String raceId) {
        String classKey = classStatKey(classId);
        if (classKey != null) {
            manager.increment(ownerUuid, CATEGORY_ID, classKey, 1L);
        }
        String raceKey = raceStatKey(raceId);
        if (raceKey != null) {
            manager.increment(ownerUuid, CATEGORY_ID, raceKey, 1L);
        }
    }

    private static void decrementSpread(StatManager manager, UUID ownerUuid, String classId, String raceId) {
        String classKey = classStatKey(classId);
        if (classKey != null) {
            manager.decrement(ownerUuid, CATEGORY_ID, classKey, 1L);
        }
        String raceKey = raceStatKey(raceId);
        if (raceKey != null) {
            manager.decrement(ownerUuid, CATEGORY_ID, raceKey, 1L);
        }
    }

    private static void adjustClassSpread(StatManager manager, UUID ownerUuid, String oldClassId, String newClassId) {
        String oldKey = classStatKey(oldClassId);
        if (oldKey != null) {
            manager.decrement(ownerUuid, CATEGORY_ID, oldKey, 1L);
        }
        String newKey = classStatKey(newClassId);
        if (newKey != null) {
            manager.increment(ownerUuid, CATEGORY_ID, newKey, 1L);
        }
    }

    private static void adjustRaceSpread(StatManager manager, UUID ownerUuid, String oldRaceId, String newRaceId) {
        String oldKey = raceStatKey(oldRaceId);
        if (oldKey != null) {
            manager.decrement(ownerUuid, CATEGORY_ID, oldKey, 1L);
        }
        String newKey = raceStatKey(newRaceId);
        if (newKey != null) {
            manager.increment(ownerUuid, CATEGORY_ID, newKey, 1L);
        }
    }

    static String classStatKey(String classId) {
        if (classId == null || classId.isBlank()) {
            return null;
        }
        return "class_" + classId.toLowerCase(Locale.ROOT);
    }

    static String raceStatKey(String raceId) {
        if (raceId == null || raceId.isBlank()) {
            return null;
        }
        return "race_" + raceId.toLowerCase(Locale.ROOT);
    }

    private static String raceIdFrom(Race race) {
        return race == null ? null : race.getId();
    }
}
