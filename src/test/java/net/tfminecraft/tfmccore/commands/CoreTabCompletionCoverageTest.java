package net.tfminecraft.tfmccore.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import net.tfminecraft.tfmccore.stats.StatCategoryRegistry;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class CoreTabCompletionCoverageTest {
  @Test
  void completionsRespectPermissionsPrefixesCommandAndArgumentPosition() {
    CoreTabCompletion tab = new CoreTabCompletion();
    Command command = mock(Command.class);
    when(command.getName()).thenReturn("tcore");
    CommandSender sender = mock(CommandSender.class);
    try (var registry = mockStatic(StatCategoryRegistry.class);
        var bukkit = mockStatic(Bukkit.class)) {
      registry.when(StatCategoryRegistry::getCategoryIds).thenReturn(List.of("vehicles", "skills"));
      Player alice = mock(Player.class);
      Player bob = mock(Player.class);
      when(alice.getName()).thenReturn("Alice");
      when(bob.getName()).thenReturn("Bob");
      bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(alice, bob));
      assertEquals(
          List.of("stats", "pack"), tab.onTabComplete(sender, command, "tcore", new String[] {""}));
      assertEquals(
          List.of("stats"), tab.onTabComplete(sender, command, "tcore", new String[] {"st"}));
      assertEquals(
          List.of(), tab.onTabComplete(sender, command, "tcore", new String[] {"reload", ""}));
      assertEquals(
          List.of("vehicles"),
          tab.onTabComplete(sender, command, "tcore", new String[] {"STATS", "v"}));
      assertEquals(
          List.of(),
          tab.onTabComplete(sender, command, "tcore", new String[] {"stats", "vehicles", "A"}));
      when(sender.hasPermission("tfmccore.reload")).thenReturn(true);
      assertEquals(
          List.of("stats", "pack", "reload"),
          tab.onTabComplete(sender, command, "tcore", new String[] {""}));
      assertEquals(
          List.of("stations", "stats"),
          tab.onTabComplete(sender, command, "tcore", new String[] {"reload", "sta"}).stream()
              .sorted()
              .toList());
      when(sender.hasPermission("tfmccore.reload")).thenReturn(false);
      when(sender.hasPermission("tfmccore.admin")).thenReturn(true);
      assertEquals(
          List.of("stats", "pack", "reload", "stones"),
          tab.onTabComplete(sender, command, "tcore", new String[] {""}));
      assertEquals(
          List.of("Alice"),
          tab.onTabComplete(sender, command, "tcore", new String[] {"stats", "vehicles", "A"}));
      assertEquals(
          List.of("give"),
          tab.onTabComplete(sender, command, "tcore", new String[] {"stones", ""}));
      assertEquals(
          List.of("lorestone", "namestone"),
          tab.onTabComplete(sender, command, "tcore", new String[] {"stones", "give", ""}));
      assertEquals(
          List.of("Bob"),
          tab.onTabComplete(
              sender, command, "tcore", new String[] {"stones", "give", "lorestone", "B"}));
      assertEquals(
          List.of("1", "16", "64"),
          tab.onTabComplete(
              sender, command, "tcore", new String[] {"stones", "give", "namestone", "Alice", ""}));
      assertEquals(
          List.of(),
          tab.onTabComplete(sender, command, "tcore", new String[] {"stones", "unknown", ""}));
      assertEquals(
          List.of(), tab.onTabComplete(sender, command, "tcore", new String[] {"other", ""}));
      when(command.getName()).thenReturn("different");
      assertEquals(List.of(), tab.onTabComplete(sender, command, "tcore", new String[] {""}));
    }
  }
}
