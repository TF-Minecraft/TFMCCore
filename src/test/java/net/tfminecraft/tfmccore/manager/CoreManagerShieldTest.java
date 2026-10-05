package net.tfminecraft.tfmccore.manager;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.tfmccore.cache.Cache;

class CoreManagerShieldTest {
    private final CoreManager manager = new CoreManager();

    @BeforeEach void enableShieldLimit() {
        Cache.limitShields = true;
    }

    @AfterEach void resetShieldLimit() {
        Cache.limitShields = false;
    }

    @Test void blockedHitIsDealtThroughTheShieldWithTheAttackerKept() {
        Player victim = mock(Player.class);
        when(victim.isBlocking()).thenReturn(true);
        DamageSource hit = mock(DamageSource.class), pierce = mock(DamageSource.class);
        EntityDamageByEntityEvent event = hitOn(victim, hit);
        try (var core = mockStatic(CoreManager.class, CALLS_REAL_METHODS)) {
            core.when(() -> CoreManager.shieldPiercingSource(hit)).thenReturn(pierce);
            manager.blockShield(event);
        }
        verify(event).setCancelled(true);
        verify(victim).damage(6.0, pierce);
    }

    @Test void redealtHitIsNotRedealtAgain() {
        Player victim = mock(Player.class);
        when(victim.isBlocking()).thenReturn(true);
        DamageSource hit = mock(DamageSource.class), pierce = mock(DamageSource.class);
        EntityDamageByEntityEvent event = hitOn(victim, hit), redealt = hitOn(victim, pierce);
        doAnswer(call -> {
            manager.blockShield(redealt);
            return null;
        }).when(victim).damage(6.0, pierce);
        try (var core = mockStatic(CoreManager.class, CALLS_REAL_METHODS)) {
            core.when(() -> CoreManager.shieldPiercingSource(hit)).thenReturn(pierce);
            manager.blockShield(event);
            manager.blockShield(event);
        }
        verify(redealt, never()).setCancelled(true);
        verify(event, org.mockito.Mockito.times(2)).setCancelled(true);
    }

    @Test void unblockedHitsAndDisabledLimitAreLeftAlone() {
        Player victim = mock(Player.class);
        EntityDamageByEntityEvent event = hitOn(victim, mock(DamageSource.class));
        manager.blockShield(event);
        when(victim.isBlocking()).thenReturn(true);
        Cache.limitShields = false;
        manager.blockShield(event);
        verify(event, never()).setCancelled(true);
        verify(victim, never()).damage(anyDouble(), any(DamageSource.class));
    }

    @Test void meleeUsesTheAttackerAsDirectAndCausingEntity() {
        Player attacker = mock(Player.class);
        DamageSource hit = mock(DamageSource.class), built = mock(DamageSource.class);
        when(hit.getCausingEntity()).thenReturn(attacker);
        DamageSource.Builder builder = builder(built);
        assertSame(built, CoreManager.withHitEntities(builder, hit));
        verify(builder).withDirectEntity(attacker);
        verify(builder).withCausingEntity(attacker);
    }

    @Test void projectileKeepsArrowAndShooter() {
        Player shooter = mock(Player.class);
        Arrow arrow = mock(Arrow.class);
        DamageSource hit = mock(DamageSource.class);
        when(hit.getCausingEntity()).thenReturn(shooter);
        when(hit.getDirectEntity()).thenReturn(arrow);
        DamageSource.Builder builder = builder(mock(DamageSource.class));
        CoreManager.withHitEntities(builder, hit);
        verify(builder).withDirectEntity(arrow);
        verify(builder).withCausingEntity(shooter);
    }

    @Test void hitWithoutEntitiesStaysSourceless() {
        DamageSource.Builder builder = builder(mock(DamageSource.class));
        CoreManager.withHitEntities(builder, mock(DamageSource.class));
        verify(builder, never()).withDirectEntity(any());
        verify(builder, never()).withCausingEntity(any());
    }

    private EntityDamageByEntityEvent hitOn(Player victim, DamageSource source) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.getDamage()).thenReturn(6.0);
        when(event.getDamageSource()).thenReturn(source);
        return event;
    }

    private DamageSource.Builder builder(DamageSource built) {
        DamageSource.Builder builder = mock(DamageSource.Builder.class);
        when(builder.withDirectEntity(any())).thenReturn(builder);
        when(builder.withCausingEntity(any())).thenReturn(builder);
        when(builder.build()).thenReturn(built);
        return builder;
    }
}
