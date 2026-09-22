package net.tfminecraft.tfmccore.stats.categories.rpcharacters;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import net.tfminecraft.rpcharacters.chat.CharacterChatEvent;
import net.tfminecraft.rpcharacters.lifecycle.CharacterClassChangeEvent;
import net.tfminecraft.rpcharacters.lifecycle.CharacterCreatedEvent;
import net.tfminecraft.rpcharacters.lifecycle.CharacterRaceChangeEvent;
import net.tfminecraft.rpcharacters.permadeath.CharacterPermakillEvent;

public final class RpCharactersStatListener implements Listener {
    private final RpCharactersStatMain main;

    public RpCharactersStatListener(RpCharactersStatMain main) {
        this.main = main;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCharacterCreated(CharacterCreatedEvent event) {
        main.handle(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCharacterClassChange(CharacterClassChangeEvent event) {
        main.handle(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCharacterRaceChange(CharacterRaceChangeEvent event) {
        main.handle(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCharacterPermakill(CharacterPermakillEvent event) {
        main.handle(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCharacterChat(CharacterChatEvent event) {
        main.handle(event);
    }
}
