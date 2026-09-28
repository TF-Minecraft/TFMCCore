package net.tfminecraft.tfmccore.tfmc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ContextManager;
import net.luckperms.api.context.ContextSet;
import net.luckperms.api.context.ContextSetFactory;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.NodeBuilderRegistry;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.track.DemotionResult;
import net.luckperms.api.track.PromotionResult;
import net.luckperms.api.track.Track;
import net.luckperms.api.track.TrackManager;

class BukkitTfmcActionsTest {

    private static final UUID ID = UUID.fromString("1e431793-3f27-4eb7-8e69-012f6a67bbfb");

    private final LuckPerms api = mock(LuckPerms.class);
    private final UserManager users = mock(UserManager.class);
    private final Track track = mock(Track.class);
    private final User user = mock(User.class);
    private final Player player = mock(Player.class);
    private BukkitTfmcActions actions;

    @BeforeEach
    void setUp() {
        TrackManager tracks = mock(TrackManager.class);
        when(api.getUserManager()).thenReturn(users);
        when(api.getTrackManager()).thenReturn(tracks);
        when(api.getMessagingService()).thenReturn(Optional.empty());
        when(api.getServerName()).thenReturn("main");
        ContextManager contextManager = mock(ContextManager.class);
        ContextSetFactory contexts = mock(ContextSetFactory.class);
        when(api.getContextManager()).thenReturn(contextManager);
        when(contextManager.getContextSetFactory()).thenReturn(contexts);
        when(contexts.immutableEmpty()).thenReturn(mock(ImmutableContextSet.class));
        when(contexts.immutableOf("server", "main")).thenReturn(mock(ImmutableContextSet.class));
        NodeBuilderRegistry nodes = mock(NodeBuilderRegistry.class);
        when(api.getNodeBuilderRegistry()).thenReturn(nodes);
        when(nodes.forPermission()).thenReturn(mock(PermissionNode.Builder.class, RETURNS_SELF));
        when(tracks.getTrack("helper+")).thenReturn(track);
        when(users.getUser(ID)).thenReturn(user);
        when(users.saveUser(user)).thenReturn(CompletableFuture.completedFuture(null));
        when(user.data()).thenReturn(mock(NodeMap.class));
        when(player.getUniqueId()).thenReturn(ID);
        when(player.getName()).thenReturn("RiverBonnie");
        actions = new BukkitTfmcActions(null, () -> api);
    }

    @Test
    void trackStepsReloadTheUserFromStorageFirst() {
        when(users.loadUser(ID)).thenReturn(CompletableFuture.completedFuture(user));
        promoteTo("helper+");

        assertEquals(Optional.of("helper+"), actions.stepTrack(player, "helper+", true).join());

        InOrder order = inOrder(users, track);
        order.verify(users).loadUser(ID);
        order.verify(track).promote(any(User.class), any(ContextSet.class));
        order.verify(users).saveUser(user);
        verify(users, never()).getUser(ID);
    }

    @Test
    void permissionChangesReloadTheUserFromStorageFirst() {
        when(users.loadUser(ID)).thenReturn(CompletableFuture.completedFuture(user));

        actions.setPermission(player, "tips.off", true).join();

        verify(users).loadUser(ID);
        verify(users, never()).getUser(ID);
        verify(users).saveUser(user);
    }

    @Test
    void aSecondChangeWaitsUntilTheFirstIsSaved() {
        CompletableFuture<User> firstLoad = new CompletableFuture<>();
        when(users.loadUser(ID)).thenReturn(firstLoad, CompletableFuture.completedFuture(user));
        promoteTo("helper+");
        DemotionResult demoted = mock(DemotionResult.class);
        when(demoted.wasSuccessful()).thenReturn(true);
        when(demoted.getGroupTo()).thenReturn(Optional.of("helper+_inactive"));
        when(demoted.getStatus()).thenReturn(DemotionResult.Status.SUCCESS);
        when(track.demote(any(User.class), any(ContextSet.class))).thenReturn(demoted);

        CompletableFuture<Optional<String>> promote = actions.stepTrack(player, "helper+", true);
        CompletableFuture<Optional<String>> demote = actions.stepTrack(player, "helper+", false);

        verify(users, times(1)).loadUser(ID);
        assertFalse(demote.isDone());

        firstLoad.complete(user);

        assertEquals(Optional.of("helper+"), promote.join());
        assertEquals(Optional.of("helper+_inactive"), demote.join());
        InOrder order = inOrder(track, users);
        order.verify(track).promote(any(User.class), any(ContextSet.class));
        order.verify(users).saveUser(user);
        order.verify(users).loadUser(ID);
        order.verify(track).demote(any(User.class), any(ContextSet.class));
    }

    @Test
    void anAmbiguousStepIsNotSaved() {
        when(users.loadUser(ID)).thenReturn(CompletableFuture.completedFuture(user));
        PromotionResult ambiguous = mock(PromotionResult.class);
        when(ambiguous.wasSuccessful()).thenReturn(false);
        when(ambiguous.getStatus()).thenReturn(PromotionResult.Status.AMBIGUOUS_CALL);
        when(track.promote(any(User.class), any(ContextSet.class))).thenReturn(ambiguous);

        assertTrue(actions.stepTrack(player, "helper+", true).join().isEmpty());
        verify(users, never()).saveUser(user);
    }

    private void promoteTo(String group) {
        PromotionResult promoted = mock(PromotionResult.class);
        when(promoted.wasSuccessful()).thenReturn(true);
        when(promoted.getGroupTo()).thenReturn(Optional.of(group));
        when(promoted.getStatus()).thenReturn(PromotionResult.Status.SUCCESS);
        when(track.promote(any(User.class), any(ContextSet.class))).thenReturn(promoted);
    }
}
