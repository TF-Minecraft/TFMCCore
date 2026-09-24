package net.tfminecraft.tfmccore.focus;

import org.bukkit.entity.Player;

/** Read/spend compatibility API for released Magic and Research plugins. */
@Deprecated
public final class FocusService {
    private final net.tfminecraft.rpcharacters.focus.FocusService delegate;

    public FocusService(net.tfminecraft.rpcharacters.focus.FocusService delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate);
    }

    public int getPoints(Player player) { return delegate.getPoints(player); }
    public boolean trySpend(Player player, int amount) { return delegate.trySpend(player, amount); }
    public void grant(Player player, int amount) { delegate.grant(player, amount); }
    public int getMax() { return delegate.getMax(); }
    public boolean restore(Player player) { return delegate.restore(player); }
}
