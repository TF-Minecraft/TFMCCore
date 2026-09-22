package net.tfminecraft.tfmccore.focus;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import net.tfminecraft.rpcharacters.lifecycle.CharacterActivatedEvent;

public final class FocusListener implements Listener {

    private final FocusService service;

    public FocusListener(FocusService service) {
        this.service = service;
    }

    @EventHandler
    public void onCharacterActivated(CharacterActivatedEvent event) {
        Player owner = event.getOwner();
        if (owner == null || event.getCharacter() == null) {
            return;
        }
        if (event.getPrevious() != null) {
            service.savePrevious(owner, event.getPrevious());
        }
        service.activate(owner, event.getCharacter());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.deactivate(event.getPlayer());
    }
}
