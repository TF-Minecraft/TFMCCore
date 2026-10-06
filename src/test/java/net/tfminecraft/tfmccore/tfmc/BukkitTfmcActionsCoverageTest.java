package net.tfminecraft.tfmccore.tfmc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ContextSet;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.messaging.MessagingService;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.track.DemotionResult;
import net.luckperms.api.track.Track;
import net.tfminecraft.tfmccore.TFMCCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class BukkitTfmcActionsCoverageTest {
  @Test
  void commandsBroadcastAndPacksGoThroughTheIntendedBukkitBoundary() {
    TFMCCore plugin = mock(TFMCCore.class);
    Player player = mock(Player.class);
    Server server = mock(Server.class);
    ConsoleCommandSender console = mock(ConsoleCommandSender.class);
    Component announcement = Component.text("Server update");
    BukkitTfmcActions actions = new BukkitTfmcActions(plugin);
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getConsoleSender).thenReturn(console);
      bukkit.when(Bukkit::getServer).thenReturn(server);

      actions.consoleCommand("say Ready");
      actions.playerCommand(player, "spawn");
      actions.sendResourcePack(player);
      actions.broadcast(announcement);

      bukkit.verify(() -> Bukkit.dispatchCommand(console, "say Ready"));
      verify(player).performCommand("spawn");
      verify(plugin).sendResourcePack(player);
      verify(server).sendMessage(announcement);
    }
  }

  @Test
  void itemRewardsDropOnlyInventoryOverflowAtThePlayersLocation() {
    Player player = mock(Player.class);
    PlayerInventory inventory = mock(PlayerInventory.class);
    World world = mock(World.class);
    Location location = new Location(world, 3, 65, 7);
    ItemStack leftover = mock(ItemStack.class);
    when(player.getInventory()).thenReturn(inventory);
    when(player.getWorld()).thenReturn(world);
    when(player.getLocation()).thenReturn(location);
    when(inventory.addItem(any(ItemStack.class)))
        .thenReturn(new HashMap<>(java.util.Map.of(0, leftover)), new HashMap<>());
    List<Material> materials = new ArrayList<>();
    try (var stacks =
        mockConstruction(
            ItemStack.class,
            (stack, context) -> materials.add((Material) context.arguments().getFirst()))) {
      new BukkitTfmcActions(null, () -> null)
          .giveItems(player, List.of(Material.DIAMOND, Material.EMERALD));
      assertEquals(List.of(Material.DIAMOND, Material.EMERALD), materials);
      assertEquals(2, stacks.constructed().size());
      verify(inventory).addItem(stacks.constructed().get(0));
      verify(inventory).addItem(stacks.constructed().get(1));
      verify(world).dropItem(location, leftover);
      verifyNoMoreInteractions(world);
    }
  }

  @ParameterizedTest
  @CsvSource({"-2,-1", "0.4,0.4", "2,1"})
  void flightCapturesTheOriginalStateAndClampsTheRequestedSpeed(float requested, float expected) {
    Player player = mock(Player.class);
    when(player.getAllowFlight()).thenReturn(true);
    when(player.isFlying()).thenReturn(true);
    when(player.getFlySpeed()).thenReturn(0.2f);

    var previous = new BukkitTfmcActions(null, () -> null).startFlight(player, requested);

    assertEquals(new TfmcActions.FlightState(true, true, 0.2f), previous);
    verify(player).setAllowFlight(true);
    verify(player).setFlySpeed(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "true,true,false,false",
    "true,false,true,false",
    "false,true,true,false",
    "false,true,false,true"
  })
  void flightRestorationOnlyAddsSlowFallingWhenThePlayerLosesFlightInMidair(
      boolean allow, boolean flying, boolean onGround, boolean slowFalling) {
    Player player = mock(Player.class);
    when(player.getAllowFlight()).thenReturn(allow);
    when(player.isOnGround()).thenReturn(onGround);

    new BukkitTfmcActions(null, () -> null)
        .endFlight(player, new TfmcActions.FlightState(allow, flying, 0.3f));

    verify(player).setFlySpeed(0.3f);
    verify(player).setAllowFlight(allow);
    verify(player).setFlying(allow && flying);
    if (slowFalling) {
      ArgumentCaptor<PotionEffect> effect = ArgumentCaptor.forClass(PotionEffect.class);
      verify(player).addPotionEffect(effect.capture());
      assertSame(PotionEffectType.SLOW_FALLING, effect.getValue().getType());
      assertEquals(100, effect.getValue().getDuration());
      assertEquals(0, effect.getValue().getAmplifier());
      assertFalse(effect.getValue().isAmbient());
      assertFalse(effect.getValue().hasParticles());
      assertTrue(effect.getValue().hasIcon());
    } else verify(player, never()).addPotionEffect(any(PotionEffect.class));
  }

  @Test
  void delayedActionsReturnCancellationForTheActualScheduledTask() {
    TFMCCore plugin = mock(TFMCCore.class);
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    BukkitTask scheduled = mock(BukkitTask.class);
    Runnable task = mock(Runnable.class);
    when(scheduler.runTaskLater(plugin, task, 42L)).thenReturn(scheduled);
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

      Runnable cancel = new BukkitTfmcActions(plugin).later(42L, task);
      verifyNoInteractions(task);
      cancel.run();

      verify(scheduler).runTaskLater(plugin, task, 42L);
      verify(scheduled).cancel();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"absent-plugin", "absent-provider", "available-provider"})
  void luckPermsLookupUsesTheRegisteredServiceAndReportsMissingDependencies(String scenario) {
    PluginManager plugins = mock(PluginManager.class);
    ServicesManager services = mock(ServicesManager.class);
    LuckPerms api = mock(LuckPerms.class, RETURNS_DEEP_STUBS);
    Player player = mock(Player.class);
    if (!scenario.equals("absent-plugin"))
      when(plugins.getPlugin("LuckPerms")).thenReturn(mock(Plugin.class));
    if (scenario.equals("available-provider")) {
      @SuppressWarnings("unchecked")
      RegisteredServiceProvider<LuckPerms> provider = mock(RegisteredServiceProvider.class);
      when(provider.getProvider()).thenReturn(api);
      when(services.getRegistration(LuckPerms.class)).thenReturn(provider);
      when(api.getTrackManager().getTrack("missing")).thenReturn(null);
    }
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
      bukkit.when(Bukkit::getServicesManager).thenReturn(services);
      BukkitTfmcActions actions = new BukkitTfmcActions(null);

      CompletionException failure =
          assertThrows(
              CompletionException.class, () -> actions.stepTrack(player, "missing", true).join());

      assertEquals(
          scenario.equals("available-provider")
              ? "No LuckPerms track missing"
              : "LuckPerms is not available",
          failure.getCause().getMessage());
      if (scenario.equals("absent-plugin")) verifyNoInteractions(services);
      else verify(services).getRegistration(LuckPerms.class);
    }
    CompletionException permissionFailure =
        assertThrows(
            CompletionException.class,
            () ->
                new BukkitTfmcActions(null, () -> null)
                    .setPermission(player, "tips.off", true)
                    .join());
    assertEquals("LuckPerms is not available", permissionFailure.getCause().getMessage());
  }

  @Test
  @SuppressWarnings("unchecked")
  void globalPermissionChangesRemoveOnlyTheMatchingPermissionAndPushTheSavedUpdate() {
    LuckPerms api = mock(LuckPerms.class, RETURNS_DEEP_STUBS);
    User user = mock(User.class, RETURNS_DEEP_STUBS);
    Player player = mock(Player.class);
    UUID id = UUID.randomUUID();
    when(player.getUniqueId()).thenReturn(id);
    when(api.getServerName()).thenReturn("GLOBAL");
    ImmutableContextSet global = mock(ImmutableContextSet.class);
    when(api.getContextManager().getContextSetFactory().immutableEmpty()).thenReturn(global);
    PermissionNode.Builder builder = mock(PermissionNode.Builder.class, RETURNS_SELF);
    PermissionNode replacement = mock(PermissionNode.class);
    when(builder.build()).thenReturn(replacement);
    when(api.getNodeBuilderRegistry().forPermission()).thenReturn(builder);
    when(api.getUserManager().loadUser(id)).thenReturn(CompletableFuture.completedFuture(user));
    when(api.getUserManager().saveUser(user)).thenReturn(CompletableFuture.completedFuture(null));
    MessagingService messaging = mock(MessagingService.class);
    when(api.getMessagingService()).thenReturn(Optional.of(messaging));

    new BukkitTfmcActions(null, () -> api).setPermission(player, "tips.off", false).join();

    ArgumentCaptor<Predicate<Node>> removed = ArgumentCaptor.forClass(Predicate.class);
    verify(user.data()).clear(removed.capture());
    assertTrue(removed.getValue().test(permission("TIPS.OFF", global)));
    assertFalse(removed.getValue().test(permission("other.node", global)));
    assertFalse(removed.getValue().test(permission("tips.off", mock(ImmutableContextSet.class))));
    Node group = mock(Node.class);
    when(group.getType()).thenReturn((NodeType) NodeType.INHERITANCE);
    assertFalse(removed.getValue().test(group));
    verify(builder).permission("tips.off");
    verify(builder).value(false);
    verify(builder).context(global);
    verify(user.data()).add(replacement);
    var order = inOrder(api.getUserManager(), messaging);
    order.verify(api.getUserManager()).saveUser(user);
    order.verify(messaging).pushUserUpdate(user);
  }

  @Test
  void aFailedUserLoadReleasesTheQueueAndAnUnsuccessfulDemotionDoesNotSave() {
    LuckPerms api = mock(LuckPerms.class, RETURNS_DEEP_STUBS);
    User user = mock(User.class);
    Player player = mock(Player.class);
    UUID id = UUID.randomUUID();
    when(player.getUniqueId()).thenReturn(id);
    when(player.getName()).thenReturn("Ada");
    Track track = mock(Track.class);
    when(api.getTrackManager().getTrack("helper")).thenReturn(track);
    DemotionResult unchanged = mock(DemotionResult.class);
    when(unchanged.getStatus()).thenReturn(DemotionResult.Status.NOT_ON_TRACK);
    when(track.demote(eq(user), any(ContextSet.class))).thenReturn(unchanged);
    CompletableFuture<User> loading = new CompletableFuture<>();
    when(api.getUserManager().loadUser(id))
        .thenReturn(loading, CompletableFuture.completedFuture(user));
    BukkitTfmcActions actions = new BukkitTfmcActions(null, () -> api);

    CompletableFuture<TfmcActions.TrackStep> first = actions.stepTrack(player, "helper", false);
    CompletableFuture<TfmcActions.TrackStep> second = actions.stepTrack(player, "helper", false);
    assertFalse(second.isDone());
    IllegalStateException failure = new IllegalStateException("database unavailable");
    loading.completeExceptionally(failure);

    assertSame(failure, assertThrows(CompletionException.class, first::join).getCause());
    assertFalse(second.join().changed());
    verify(api.getUserManager(), times(2)).loadUser(id);
    verify(api.getUserManager(), never()).saveUser(any());
  }

  private PermissionNode permission(String name, ImmutableContextSet context) {
    PermissionNode node = mock(PermissionNode.class);
    when(node.getType()).thenReturn(NodeType.PERMISSION);
    when(node.getPermission()).thenReturn(name);
    when(node.getContexts()).thenReturn(context);
    return node;
  }
}
