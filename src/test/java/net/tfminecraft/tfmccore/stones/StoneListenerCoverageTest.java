package net.tfminecraft.tfmccore.stones;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.tfminecraft.tfmccore.TFMCCore;
import net.tfminecraft.tfmccore.stones.StoneItems.Kind;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.tlibs.objects.api.subapi.ItemChecker;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import net.kyori.adventure.text.Component;

@SuppressWarnings("deprecation")
class StoneListenerCoverageTest {
  private record Scheduled(Runnable callback, BukkitTask task, long ticks) {}

  private static final class StackData {
    final String identity;
    final Kind kind;
    int amount;
    String name;
    List<String> lore;
    ItemMeta meta;

    StackData(String identity, Kind kind, int amount, String name, List<String> lore) {
      this.identity = identity;
      this.kind = kind;
      this.amount = amount;
      this.name = name;
      this.lore = lore == null ? null : new ArrayList<>(lore);
    }
  }

  @TempDir Path directory;
  private final Map<Field, Object> originalConfig = new LinkedHashMap<>();
  private final Map<ItemStack, StackData> stacks = new IdentityHashMap<>();
  private final Set<ItemStack> blacklisted = Collections.newSetFromMap(new IdentityHashMap<>());
  private final ItemStack[] inventoryContents = new ItemStack[41];
  private final AtomicReference<ItemStack> cursor = new AtomicReference<>();
  private final List<ItemStack> refunds = new ArrayList<>();
  private final List<Scheduled> timeouts = new ArrayList<>();
  private final List<Runnable> callbacks = new ArrayList<>();
  private final UUID uuid = UUID.fromString("a1c5b0d5-e46c-4c1e-9ad7-2a22bb028d8c");
  private TFMCCore plugin;
  private Server server;
  private Player player;
  private PlayerInventory inventory;
  private InventoryView view;
  private BukkitScheduler scheduler;
  private Logger logger;
  private StoneListener listener;
  private MockedStatic<TFMCCore> core;
  private MockedStatic<TLibs> tlibs;

  @BeforeEach
  void setup() throws Exception {
    for (Field field : LorestoneConfig.class.getFields())
      originalConfig.put(field, field.get(null));
    Path configuration =
        Files.writeString(
            directory.resolve("stones.yml"),
            """
            items:
              lorestone: custom:lore
              namestone: custom:name
            settings:
              blacklist: [v.blacklisted]
              max-length: 12
              prompt-timeout-seconds: 3
              max-lore-lines: 3
            messages:
              prompt-lore: 'lore prompt %timeout%'
              prompt-name: 'name prompt %timeout%'
              applied-lore: 'lore applied'
              applied-name: 'name applied'
              cancelled: 'cancelled'
              timeout: 'timeout'
              item-moved: 'item moved'
              empty: 'empty text'
              too-long: 'length limit %max%'
              too-many-lines: 'line limit %max%'
              cannot-apply: 'blacklisted'
              stacked: 'stacked target'
              expired: 'expired prompt'
              cleared-lore: 'lore cleared'
              no-lore: 'no lore'
              pick-colour: 'pick colour'
              invalid-colour: 'bad colour'
            """);
    assertTrue(LorestoneConfigLoader.load(configuration.toFile()));
    plugin = mock(TFMCCore.class);
    when(plugin.isEnabled()).thenReturn(true);
    server = mock(Server.class);
    when(plugin.getServer()).thenReturn(server);
    logger = mock(Logger.class);
    when(plugin.getLogger()).thenReturn(logger);
    core = mockStatic(TFMCCore.class);
    core.when(TFMCCore::getInstance).thenReturn(plugin);
    player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(uuid);
    when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
    when(player.hasPermission("tfmccore.stones.use")).thenReturn(true);
    when(server.getPlayer(uuid)).thenReturn(player);
    inventory = mock(PlayerInventory.class);
    when(player.getInventory()).thenReturn(inventory);
    when(inventory.getItem(anyInt())).thenAnswer(call -> inventoryContents[call.getArgument(0)]);
    doAnswer(
            call -> {
              inventoryContents[call.getArgument(0)] = call.getArgument(1);
              return null;
            })
        .when(inventory)
        .setItem(anyInt(), any());
    when(inventory.addItem(any(ItemStack.class)))
        .thenAnswer(
            call -> {
              refunds.add(call.getArgument(0));
              return new HashMap<Integer, ItemStack>();
            });
    view = mock(InventoryView.class);
    doAnswer(
            call -> {
              cursor.set(call.getArgument(0));
              return null;
            })
        .when(view)
        .setCursor(any());
    scheduler = mock(BukkitScheduler.class);
    when(server.getScheduler()).thenReturn(scheduler);
    when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong()))
        .thenAnswer(
            call -> {
              BukkitTask task = mock(BukkitTask.class);
              timeouts.add(new Scheduled(call.getArgument(1), task, call.getArgument(2)));
              return task;
            });
    when(scheduler.runTask(eq(plugin), any(Runnable.class)))
        .thenAnswer(
            call -> {
              callbacks.add(call.getArgument(1));
              return mock(BukkitTask.class);
            });
    ItemAPI api = mock(ItemAPI.class);
    ItemChecker checker = mock(ItemChecker.class);
    when(api.getChecker()).thenReturn(checker);
    when(checker.checkItemWithPath(any(), anyString()))
        .thenAnswer(
            call -> {
              ItemStack item = call.getArgument(0);
              String path = call.getArgument(1);
              StackData data = stacks.get(item);
              return ("v.blacklisted".equals(path) && blacklisted.contains(item))
                  || (data != null
                      && (("custom:lore".equals(path) && data.kind == Kind.LORE)
                          || ("custom:name".equals(path) && data.kind == Kind.NAME)));
            });
    tlibs = mockStatic(TLibs.class);
    tlibs.when(TLibs::getItemAPI).thenReturn(api);
    listener = new StoneListener(new StoneItems());
  }

  @AfterEach
  void cleanup() throws Exception {
    tlibs.close();
    core.close();
    for (Map.Entry<Field, Object> entry : originalConfig.entrySet())
      entry.getKey().set(null, entry.getValue());
  }

  @Test
  void chatFromAnExpiredPromptCannotConsumeOrEditANewPrompt() {
    ItemStack first = target("first", 1, null);
    begin(Kind.LORE, 3, 7, first);
    ItemStack remaining = cursor.get();
    assertTrue(chat("old text").isCancelled());
    timeouts.getFirst().callback().run();
    assertEquals(1, refunds.size());
    ItemStack second = target("second", 1, null);
    begin(Kind.NAME, 1, 9, second);

    callbacks.getFirst().run();

    assertNull(stacks.get(second).name, "Old chat must not edit the new prompt's item");
    verify(timeouts.get(1).task(), never()).cancel();
    assertTrue(chat("Fresh name").isCancelled(), "The new prompt must still be pending");
    callbacks.get(1).run();
    listener.chooseColour(player, "none");
    assertEquals("§rFresh name", stacks.get(second).name);
    assertEquals(2, remaining.getAmount());
    assertEquals(1, refunds.size());
    assertEquals(1, refunds.getFirst().getAmount());
    verify(player).sendMessage("expired prompt");
    verify(timeouts.get(1).task()).cancel();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void unavailablePluginLeavesInventoryInteractionUntouched(boolean absent) {
    if (absent) core.when(TFMCCore::getInstance).thenReturn(null);
    else when(plugin.isEnabled()).thenReturn(false);
    InventoryClickEvent event = begin(Kind.LORE, 2, 7, target("unchanged", 1, null));
    assertFalse(event.isCancelled());
    assertEquals(2, cursor.get().getAmount());
    assertTrue(timeouts.isEmpty());
    assertTrue(refunds.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "non-player",
        "creative",
        "empty-cursor",
        "ordinary-cursor",
        "outside",
        "other-inventory",
        "empty-slot",
        "air-slot",
        "stone-target",
        "no-permission"
      })
  void unsupportedOrDeniedClicksPreserveVanillaBehavior(String scenario) {
    cursor.set(stack(new StackData("stone", Kind.LORE, 2, null, null)));
    ItemStack initialCursor = cursor.get();
    ItemStack target = target("target", 1, null);
    inventoryContents[7] = target;
    InventoryClickEvent event = click(7);
    switch (scenario) {
      case "non-player" -> when(event.getWhoClicked()).thenReturn(mock(HumanEntity.class));
      case "creative" -> when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
      case "empty-cursor" -> cursor.set(null);
      case "ordinary-cursor" -> cursor.set(target("ordinary", 2, null));
      case "outside" -> when(event.getClickedInventory()).thenReturn(null);
      case "other-inventory" -> when(event.getClickedInventory()).thenReturn(mock(Inventory.class));
      case "empty-slot" -> inventoryContents[7] = null;
      case "air-slot" -> when(target.getType().isAir()).thenReturn(true);
      case "stone-target" ->
          inventoryContents[7] = stack(new StackData("another stone", Kind.NAME, 1, null, null));
      case "no-permission" -> when(player.hasPermission("tfmccore.stones.use")).thenReturn(false);
      default -> fail("Unknown scenario");
    }
    ItemStack expectedCursor = cursor.get();
    listener.onClick(event);
    assertFalse(event.isCancelled());
    assertSame(expectedCursor, cursor.get());
    assertEquals(2, initialCursor.getAmount());
    verify(inventory, never()).setItem(anyInt(), any());
    assertTrue(timeouts.isEmpty());
    assertTrue(refunds.isEmpty());
    verify(player, never()).sendMessage(anyString());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void blacklistedOrStackedTargetsAreCancelledWithoutConsumingAStone(boolean blocked) {
    ItemStack target = target("target", blocked ? 1 : 2, null);
    if (blocked) blacklisted.add(target);
    InventoryClickEvent event = begin(Kind.LORE, 3, 7, target);
    assertTrue(event.isCancelled());
    assertEquals(3, cursor.get().getAmount());
    assertTrue(timeouts.isEmpty());
    assertTrue(refunds.isEmpty());
    assertSame(target, inventoryContents[7]);
    verify(player).sendMessage(blocked ? "blacklisted" : "stacked target");
  }

  @ParameterizedTest
  @CsvSource({"LORE, 1", "NAME, 4"})
  void beginningAPromptConsumesExactlyOneStoneAndUsesTheConfiguredTimeout(Kind kind, int count) {
    ItemStack target = target("target", 1, null);
    InventoryClickEvent event = begin(kind, count, 7, target);
    assertTrue(event.isCancelled());
    if (count == 1) assertNull(cursor.get());
    else assertEquals(count - 1, cursor.get().getAmount());
    assertEquals(1, timeouts.size());
    assertEquals(60L, timeouts.getFirst().ticks());
    verify(player).sendMessage(kind == Kind.LORE ? "lore prompt 3" : "name prompt 3");
    verify(target).clone();
    verify(inventory, never()).setItem(anyInt(), any());
    assertTrue(chat("cancel").isCancelled());
    callbacks.getFirst().run();
    assertRefunded(kind, "cancelled");
    assertEquals(
        count,
        refunds.getFirst().getAmount() + (cursor.get() == null ? 0 : cursor.get().getAmount()));
  }

  @Test
  void anExistingPromptPreventsASecondConsumption() {
    ItemStack target = target("target", 1, null);
    begin(Kind.LORE, 3, 7, target);
    ItemStack remaining = cursor.get();
    InventoryClickEvent second = click(7);
    listener.onClick(second);
    assertFalse(second.isCancelled());
    assertSame(remaining, cursor.get());
    assertEquals(2, remaining.getAmount());
    assertEquals(1, timeouts.size());
    assertTrue(chat("cancel").isCancelled());
    callbacks.getFirst().run();
    assertRefunded(Kind.LORE, "cancelled");
  }

  @ParameterizedTest
  @ValueSource(ints = {7, 40})
  void loreIsAppliedOnTheMainThreadToTheClickedInventorySlot(int slot) {
    ItemStack target = target("target", 1, null);
    begin(Kind.LORE, 1, slot, target);
    assertTrue(chat("&aBright").isCancelled());
    assertNull(stacks.get(target).lore, "Async chat must not touch item metadata");
    verify(inventory, never()).setItem(anyInt(), any());
    callbacks.getFirst().run();
    assertEquals(List.of("§r§aBright"), stacks.get(target).lore);
    assertSame(target, inventoryContents[slot]);
    verify(target).setItemMeta(stacks.get(target).meta);
    verify(inventory).setItem(slot, target);
    verify(player).sendMessage("lore applied");
    verify(timeouts.getFirst().task()).cancel();
    assertTrue(refunds.isEmpty());
    assertFalse(chat("ordinary chat").isCancelled());
  }

  @Test
  void appendingLoreKeepsExistingTextAndAddsAnExplicitResetToEveryLine() {
    ItemStack target = target("target", 1, List.of("§rAlready reset", "Existing text"));
    begin(Kind.LORE, 1, 7, target);
    chat("New line");
    callbacks.getFirst().run();
    assertEquals(
        List.of("§rAlready reset", "§rExisting text", "§rNew line"), stacks.get(target).lore);
    assertTrue(refunds.isEmpty());
    verify(player).sendMessage("lore applied");
  }

  @Test
  void renamingSanitizesChatAndPreservesExistingLore() {
    ItemStack target = target("target", 1, List.of("untouched lore"));
    stacks.get(target).name = "Old name";
    begin(Kind.NAME, 1, 7, target);
    chat(" &aNew\tName ");
    callbacks.getFirst().run();
    assertEquals("§r§aNewName", stacks.get(target).name);
    assertEquals(List.of("untouched lore"), stacks.get(target).lore);
    verify(stacks.get(target).meta, never()).setLore(anyList());
    verify(inventory).setItem(7, target);
    verify(player).sendMessage("name applied");
    assertTrue(refunds.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"cancel", "  CaNcEl  "})
  void cancellingRefundsExactlyOneStoneWithoutChangingTheItem(String message) {
    ItemStack target = target("target", 1, List.of("unchanged"));
    begin(Kind.NAME, 1, 7, target);
    chat(message);
    callbacks.getFirst().run();
    assertRefunded(Kind.NAME, "cancelled");
    assertEquals(List.of("unchanged"), stacks.get(target).lore);
    assertNull(stacks.get(target).name);
    verify(inventory, never()).setItem(anyInt(), any());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " \n\t\u200b "})
  void emptySanitizedInputRefundsTheStone(String message) {
    begin(Kind.LORE, 1, 7, target("target", 1, null));
    chat(message);
    callbacks.getFirst().run();
    assertRefunded(Kind.LORE, "empty text");
    verify(inventory, never()).setItem(anyInt(), any());
  }

  @Test
  void excessiveTextReportsTheConfiguredLimitAndRefunds() {
    begin(Kind.NAME, 1, 7, target("target", 1, null));
    chat("1234567890123");
    callbacks.getFirst().run();
    assertRefunded(Kind.NAME, "length limit 12");
    verify(inventory, never()).setItem(anyInt(), any());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"missing", "replaced", "stacked", "changed-name", "changed-lore", "no-meta"})
  void movedOrChangedTargetsAreNeverOverwritten(String change) {
    ItemStack target = target("original", 1, List.of("original lore"));
    begin(Kind.NAME, 1, 7, target);
    switch (change) {
      case "missing" -> inventoryContents[7] = null;
      case "replaced" -> inventoryContents[7] = target("replacement", 1, null);
      case "stacked" -> target.setAmount(2);
      case "changed-name" -> stacks.get(target).name = "Changed by someone else";
      case "changed-lore" -> stacks.get(target).lore = List.of("Changed by someone else");
      case "no-meta" -> when(target.getItemMeta()).thenReturn(null);
      default -> fail("Unknown scenario");
    }
    ItemStack expected = inventoryContents[7];
    chat("New name");
    callbacks.getFirst().run();
    listener.chooseColour(player, "gold");
    assertRefunded(Kind.NAME, "item moved");
    assertSame(expected, inventoryContents[7]);
    verify(inventory, never()).setItem(anyInt(), any());
    verify(stacks.get(target).meta, never()).setDisplayName(anyString());
  }

  @Test
  void fullLoreDoesNotRewriteExistingLinesAndRefunds() {
    List<String> original = List.of("one", "two", "three");
    ItemStack target = target("target", 1, original);
    begin(Kind.LORE, 1, 7, target);
    chat("four");
    callbacks.getFirst().run();
    assertRefunded(Kind.LORE, "line limit 3");
    assertEquals(original, stacks.get(target).lore);
    verify(stacks.get(target).meta, never()).setLore(anyList());
    verify(inventory, never()).setItem(anyInt(), any());
  }

  @ParameterizedTest
  @CsvSource({
    "click, gold, §r§6Sword",
    "chat, Dark Blue, §r§1Sword",
    "chat, #FF8800, §r§x§f§f§8§8§0§0Sword",
    "click, none, §rSword"
  })
  void plainNamesOfferThePaletteAndApplyThePickedColour(String via, String colour, String name) {
    ItemStack target = target("target", 1, null);
    begin(Kind.NAME, 1, 7, target);
    chat("Sword");
    callbacks.getFirst().run();
    assertNull(stacks.get(target).name, "The name waits for its colour");
    verify(player).sendMessage(any(Component.class));
    verify(player).sendMessage("pick colour");
    verify(timeouts.getFirst().task(), never()).cancel();
    if (via.equals("click")) {
      listener.chooseColour(player, colour);
    } else {
      assertTrue(chat(colour).isCancelled());
      callbacks.get(1).run();
    }
    assertEquals(name, stacks.get(target).name);
    verify(inventory).setItem(7, target);
    verify(player).sendMessage("name applied");
    verify(timeouts.getFirst().task()).cancel();
    assertTrue(refunds.isEmpty());
    assertFalse(chat("ordinary chat").isCancelled());
  }

  @Test
  void chatArrivingWhileTheNameIsProcessedIsStillCaptured() {
    ItemStack target = target("target", 1, null);
    begin(Kind.NAME, 1, 7, target);
    chat("Sword");
    // A second message lands on the async thread before the first one is processed.
    assertTrue(chat("gold").isCancelled());
    callbacks.getFirst().run();
    callbacks.get(1).run();
    assertEquals("§r§6Sword", stacks.get(target).name);
    assertTrue(refunds.isEmpty());
  }

  @Test
  void aQueuedColourAnswerLosesToAnEarlierClickAndEditsOnce() {
    ItemStack target = target("target", 1, null);
    begin(Kind.NAME, 1, 7, target);
    chat("Sword");
    callbacks.getFirst().run();
    assertTrue(chat("gold").isCancelled());
    listener.chooseColour(player, "red");
    callbacks.get(1).run();
    assertEquals("§r§cSword", stacks.get(target).name);
    verify(inventory, times(1)).setItem(7, target);
    verify(player).sendMessage("expired prompt");
    assertTrue(refunds.isEmpty());
  }

  @Test
  void aQueuedColourAnswerAfterATimeoutRefundsOnceAndKeepsANewPrompt() {
    ItemStack target = target("target", 1, null);
    begin(Kind.NAME, 1, 7, target);
    chat("Sword");
    callbacks.getFirst().run();
    assertTrue(chat("gold").isCancelled());
    timeouts.getFirst().callback().run();
    ItemStack next = target("next", 1, null);
    begin(Kind.NAME, 1, 9, next);
    callbacks.get(1).run();
    assertNull(stacks.get(target).name);
    assertEquals(1, refunds.size());
    verify(player).sendMessage("expired prompt");
    assertTrue(chat("Shield").isCancelled(), "The new prompt must survive the stale answer");
  }

  @Test
  void anUnknownColourKeepsThePromptOpenUntilAValidPick() {
    ItemStack target = target("target", 1, null);
    begin(Kind.NAME, 1, 7, target);
    chat("Sword");
    callbacks.getFirst().run();
    chat("sparkly");
    callbacks.get(1).run();
    verify(player).sendMessage("bad colour");
    assertNull(stacks.get(target).name);
    assertTrue(refunds.isEmpty());
    listener.chooseColour(player, "red");
    assertEquals("§r§cSword", stacks.get(target).name);
  }

  @Test
  void cancellingAtTheColourStepRefundsWithoutRenaming() {
    ItemStack target = target("target", 1, null);
    begin(Kind.NAME, 1, 7, target);
    chat("Sword");
    callbacks.getFirst().run();
    listener.chooseColour(player, "cancel");
    assertRefunded(Kind.NAME, "cancelled");
    assertNull(stacks.get(target).name);
  }

  @Test
  void timingOutAtTheColourStepRefundsAndLaterClicksExpire() {
    ItemStack target = target("target", 1, null);
    begin(Kind.NAME, 1, 7, target);
    chat("Sword");
    callbacks.getFirst().run();
    timeouts.getFirst().callback().run();
    assertRefunded(Kind.NAME, "timeout");
    listener.chooseColour(player, "gold");
    verify(player).sendMessage("expired prompt");
    assertNull(stacks.get(target).name);
    assertEquals(1, refunds.size());
  }

  @Test
  void colourClicksBeforeANameIsTypedAreRejected() {
    ItemStack target = target("target", 1, null);
    begin(Kind.NAME, 1, 7, target);
    listener.chooseColour(player, "gold");
    verify(player).sendMessage("expired prompt");
    assertTrue(chat("Sword").isCancelled(), "The prompt is still waiting for the name");
  }

  @ParameterizedTest
  @ValueSource(strings = {"clear", " CLEAR "})
  void clearRemovesAllLoreAndConsumesTheStone(String message) {
    ItemStack target = target("target", 1, List.of("one", "two"));
    begin(Kind.LORE, 1, 7, target);
    chat(message);
    callbacks.getFirst().run();
    assertNull(stacks.get(target).lore);
    verify(inventory).setItem(7, target);
    verify(player).sendMessage("lore cleared");
    verify(timeouts.getFirst().task()).cancel();
    assertTrue(refunds.isEmpty());
  }

  @Test
  void clearIgnoresATinyLengthLimitThatStillAppliesToLoreText() {
    LorestoneConfig.maxLength = 3;
    ItemStack target = target("target", 1, List.of("one"));
    begin(Kind.LORE, 1, 7, target);
    chat("clear");
    callbacks.getFirst().run();
    assertNull(stacks.get(target).lore);
    verify(player).sendMessage("lore cleared");
    begin(Kind.LORE, 1, 7, target);
    chat("long");
    callbacks.get(1).run();
    verify(player).sendMessage("length limit 3");
  }

  @Test
  void clearingAnItemWithoutLoreRefunds() {
    ItemStack target = target("target", 1, null);
    begin(Kind.LORE, 1, 7, target);
    chat("clear");
    callbacks.getFirst().run();
    assertRefunded(Kind.LORE, "no lore");
    verify(inventory, never()).setItem(anyInt(), any());
  }

  @Test
  void timeoutRefundsOnceAndLaterQuitCannotRefundAgain() {
    begin(Kind.LORE, 1, 7, target("target", 1, null));
    timeouts.getFirst().callback().run();
    assertRefunded(Kind.LORE, "timeout");
    listener.onQuit(quitEvent());
    listener.refundAll();
    assertEquals(1, refunds.size());
    verify(timeouts.getFirst().task(), times(1)).cancel();
    assertFalse(chat("public message").isCancelled());
  }

  @Test
  void quittingRefundsSilentlyAndPreventsQueuedChatFromEditingTheItem() {
    ItemStack target = target("target", 1, null);
    begin(Kind.NAME, 1, 7, target);
    chat("Old message");
    clearInvocations(player);
    listener.onQuit(quitEvent());
    assertEquals(1, refunds.size());
    verify(player, never()).sendMessage(anyString());
    callbacks.getFirst().run();
    assertNull(stacks.get(target).name);
    assertEquals(1, refunds.size());
    verify(player).sendMessage("expired prompt");
    listener.onQuit(quitEvent());
    assertEquals(1, refunds.size());
  }

  @Test
  void multipleQueuedMessagesApplyOnlyTheFirstAnswer() {
    ItemStack target = target("target", 1, null);
    begin(Kind.LORE, 1, 7, target);
    assertTrue(chat("first").isCancelled());
    assertTrue(chat("second").isCancelled());
    assertEquals(2, callbacks.size());
    callbacks.forEach(Runnable::run);
    assertEquals(List.of("§rfirst"), stacks.get(target).lore);
    verify(inventory, times(1)).setItem(7, target);
    verify(timeouts.getFirst().task(), times(1)).cancel();
    verify(player).sendMessage("expired prompt");
    assertTrue(refunds.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void pluginShutdownDoesNotScheduleNewChatWorkAndRefundsTheHeldStone(boolean absent) {
    begin(Kind.NAME, 1, 7, target("target", 1, null));
    if (absent) core.when(TFMCCore::getInstance).thenReturn(null);
    else when(plugin.isEnabled()).thenReturn(false);
    assertTrue(chat("answer").isCancelled());
    assertTrue(callbacks.isEmpty());
    core.when(TFMCCore::getInstance).thenReturn(plugin);
    listener.refundAll();
    assertEquals(1, refunds.size());
    assertEquals(Kind.NAME, stacks.get(refunds.getFirst()).kind);
    assertFalse(chat("ordinary chat").isCancelled());
  }

  @Test
  void refundAllReturnsOnlinePlayersStonesOnceWithoutMessages() {
    begin(Kind.NAME, 1, 7, target("target", 1, null));
    clearInvocations(player);
    listener.refundAll();
    listener.refundAll();
    assertEquals(1, refunds.size());
    assertEquals(1, refunds.getFirst().getAmount());
    verify(timeouts.getFirst().task()).cancel();
    verify(player, never()).sendMessage(anyString());
    assertFalse(chat("public message").isCancelled());
  }

  @Test
  void refundAllReportsAnOfflineOwnerAndClosesTheirPrompt() {
    begin(Kind.LORE, 1, 7, target("target", 1, null));
    when(server.getPlayer(uuid)).thenReturn(null);
    listener.refundAll();
    listener.refundAll();
    verify(logger)
        .warning(
            argThat(
                (String message) ->
                    message.contains("Could not refund LORE stone")
                        && message.contains(uuid.toString())));
    assertTrue(refunds.isEmpty());
    assertFalse(chat("public message").isCancelled());
  }

  @Test
  void refundAllWithoutAPluginAndChatWithoutAPromptAreNoOps() {
    core.when(TFMCCore::getInstance).thenReturn(null);
    listener.refundAll();
    assertFalse(chat("public message").isCancelled());
    assertTrue(refunds.isEmpty());
    assertTrue(callbacks.isEmpty());
    verifyNoInteractions(logger);
  }

  private PlayerQuitEvent quitEvent() {
    PlayerQuitEvent event = mock(PlayerQuitEvent.class);
    when(event.getPlayer()).thenReturn(player);
    return event;
  }

  private void assertRefunded(Kind kind, String message) {
    assertEquals(1, refunds.size());
    assertEquals(1, refunds.getFirst().getAmount());
    assertEquals(kind, stacks.get(refunds.getFirst()).kind);
    verify(player).sendMessage(message);
    verify(timeouts.getFirst().task()).cancel();
    assertFalse(chat("normal chat").isCancelled());
  }

  private InventoryClickEvent begin(Kind kind, int stones, int slot, ItemStack target) {
    cursor.set(stack(new StackData("stone-" + kind, kind, stones, null, null)));
    inventoryContents[slot] = target;
    InventoryClickEvent event = click(slot);
    listener.onClick(event);
    return event;
  }

  private InventoryClickEvent click(int slot) {
    InventoryClickEvent event = mock(InventoryClickEvent.class);
    AtomicBoolean cancelled = new AtomicBoolean();
    when(event.getWhoClicked()).thenReturn(player);
    when(event.getClickedInventory()).thenReturn(inventory);
    when(event.getView()).thenReturn(view);
    when(event.getCursor()).thenAnswer(call -> cursor.get());
    when(event.getCurrentItem()).thenAnswer(call -> inventoryContents[slot]);
    when(event.getSlot()).thenReturn(slot);
    when(event.getRawSlot()).thenReturn(slot + 54);
    when(event.isCancelled()).thenAnswer(call -> cancelled.get());
    doAnswer(
            call -> {
              cancelled.set(call.getArgument(0));
              return null;
            })
        .when(event)
        .setCancelled(anyBoolean());
    return event;
  }

  private AsyncPlayerChatEvent chat(String message) {
    AsyncPlayerChatEvent event = mock(AsyncPlayerChatEvent.class);
    AtomicBoolean cancelled = new AtomicBoolean();
    when(event.getPlayer()).thenReturn(player);
    when(event.getMessage()).thenReturn(message);
    when(event.isCancelled()).thenAnswer(call -> cancelled.get());
    doAnswer(
            call -> {
              cancelled.set(call.getArgument(0));
              return null;
            })
        .when(event)
        .setCancelled(anyBoolean());
    listener.onChat(event);
    return event;
  }

  private ItemStack target(String identity, int amount, List<String> lore) {
    return stack(new StackData(identity, null, amount, null, lore));
  }

  private ItemStack stack(StackData data) {
    ItemStack item = mock(ItemStack.class);
    Material material = mock(Material.class);
    when(material.isAir()).thenReturn(false);
    when(item.getType()).thenReturn(material);
    when(item.getAmount()).thenAnswer(call -> data.amount);
    doAnswer(
            call -> {
              data.amount = call.getArgument(0);
              return null;
            })
        .when(item)
        .setAmount(anyInt());
    ItemMeta meta = mock(ItemMeta.class);
    data.meta = meta;
    when(item.getItemMeta()).thenReturn(meta);
    when(item.setItemMeta(meta)).thenReturn(true);
    when(meta.hasLore()).thenAnswer(call -> data.lore != null && !data.lore.isEmpty());
    when(meta.getLore()).thenAnswer(call -> data.lore == null ? null : new ArrayList<>(data.lore));
    doAnswer(
            call -> {
              List<String> lore = call.getArgument(0);
              data.lore = lore == null ? null : new ArrayList<>(lore);
              return null;
            })
        .when(meta)
        .setLore(any());
    doAnswer(
            call -> {
              data.name = call.getArgument(0);
              return null;
            })
        .when(meta)
        .setDisplayName(anyString());
    when(item.clone())
        .thenAnswer(
            call ->
                stack(new StackData(data.identity, data.kind, data.amount, data.name, data.lore)));
    when(item.isSimilar(any()))
        .thenAnswer(
            call -> {
              StackData other = stacks.get(call.getArgument(0));
              return other != null
                  && data.identity.equals(other.identity)
                  && Objects.equals(data.name, other.name)
                  && Objects.equals(data.lore, other.lore);
            });
    stacks.put(item, data);
    return item;
  }
}
