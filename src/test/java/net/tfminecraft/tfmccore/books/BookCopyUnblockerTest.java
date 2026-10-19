package net.tfminecraft.tfmccore.books;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.logging.Logger;
import net.Indyuce.mmoitems.listener.DisableInteractions;
import net.tfminecraft.tfmccore.cache.Cache;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ComplexRecipe;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BookCopyUnblockerTest {

  private final Plugin mmoItems = mock(Plugin.class);
  private final Plugin other = mock(Plugin.class);
  private final DisableInteractions disable = new DisableInteractions();
  private HandlerList handlers;
  private boolean previous;

  @BeforeEach
  void setUp() {
    previous = Cache.allowBookCopies;
    Cache.allowBookCopies = true;
    handlers = new HandlerList();
  }

  @AfterEach
  void restore() {
    Cache.allowBookCopies = previous;
    handlers.unregisterAll(mmoItems);
    handlers.unregisterAll(other);
    InventoryClickEvent.getHandlerList().unregisterAll(mmoItems);
  }

  @Test
  void undoesTheBookCopyCancelMadeByMmoItems() {
    mmoItemsCancels(handlers);
    assertEquals(1, BookCopyUnblocker.wrap(handlers));

    CraftItemEvent event = craft(bookCloning());
    dispatch(event);

    assertFalse(event.isCancelled());
  }

  @Test
  void keepsWhatTheWrappedRegistrationLooksLike() {
    mmoItemsCancels(handlers);
    BookCopyUnblocker.wrap(handlers);

    RegisteredListener wrapped = handlers.getRegisteredListeners()[0];
    assertEquals(1, handlers.getRegisteredListeners().length);
    assertSame(disable, wrapped.getListener());
    assertSame(mmoItems, wrapped.getPlugin());
    assertEquals(EventPriority.NORMAL, wrapped.getPriority());
    assertFalse(wrapped.isIgnoringCancelled());
  }

  @Test
  void keepsCancelsFromOtherPluginsAtEveryPriority() {
    for (EventPriority priority :
        new EventPriority[] {EventPriority.LOW, EventPriority.NORMAL, EventPriority.HIGHEST}) {
      HandlerList list = new HandlerList();
      // Another NORMAL plugin registered before MMOItems, and one registered after it.
      if (priority == EventPriority.NORMAL) otherCancels(list, priority);
      mmoItemsCancels(list);
      otherCancels(list, priority);
      BookCopyUnblocker.wrap(list);

      CraftItemEvent event = craft(bookCloning());
      for (RegisteredListener registered : list.getRegisteredListeners()) call(registered, event);

      assertTrue(event.isCancelled(), priority.name());
      list.unregisterAll(mmoItems);
      list.unregisterAll(other);
    }
  }

  @Test
  void keepsAnOtherPluginsCancelThatComesAfterMmoItemsInTheSameSlot() {
    mmoItemsCancels(handlers);
    BookCopyUnblocker.wrap(handlers);
    // Registered after wrapping, so it runs after the wrapped MMOItems handler.
    otherCancels(handlers, EventPriority.NORMAL);

    CraftItemEvent event = craft(bookCloning());
    dispatch(event);

    assertTrue(event.isCancelled());
  }

  @Test
  void leavesOtherRecipesAndClicksCancelled() {
    mmoItemsCancels(handlers);
    BookCopyUnblocker.wrap(handlers);
    ComplexRecipe map = mock(ComplexRecipe.class);
    when(map.getKey()).thenReturn(NamespacedKey.minecraft("map_cloning"));

    for (Recipe recipe : new Recipe[] {map, mock(Recipe.class)}) {
      CraftItemEvent event = craft(recipe);
      dispatch(event);
      assertTrue(event.isCancelled());
    }
    InventoryClickEvent click =
        new InventoryClickEvent(
            view(), InventoryType.SlotType.CONTAINER, 3, ClickType.LEFT, InventoryAction.PICKUP_ALL);
    dispatch(click);
    assertTrue(click.isCancelled());
  }

  @Test
  void leavesAllowedCraftsAndCanBeTurnedOff() {
    RegisteredListener allows =
        new RegisteredListener(disable, (l, e) -> {}, EventPriority.NORMAL, mmoItems, false);
    handlers.register(allows);
    BookCopyUnblocker.wrap(handlers);
    CraftItemEvent allowed = craft(bookCloning());
    dispatch(allowed);
    assertFalse(allowed.isCancelled());

    handlers.unregisterAll(mmoItems);
    mmoItemsCancels(handlers);
    BookCopyUnblocker.wrap(handlers);
    Cache.allowBookCopies = false;
    CraftItemEvent off = craft(bookCloning());
    dispatch(off);
    assertTrue(off.isCancelled());
  }

  @Test
  void onlyWrapsMmoItemsDisableInteractions() {
    handlers.register(
        new RegisteredListener(new Listener() {}, (l, e) -> {}, EventPriority.NORMAL, other, false));

    assertEquals(0, BookCopyUnblocker.wrap(handlers));
  }

  @Test
  void registersOneTickLaterWhenMmoItemsIsEnabled() {
    JavaPlugin plugin = mock(JavaPlugin.class);
    Server server = mock(Server.class);
    PluginManager plugins = mock(PluginManager.class);
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    Logger logger = mock(Logger.class);
    when(plugin.getServer()).thenReturn(server);
    when(plugin.getLogger()).thenReturn(logger);
    when(server.getPluginManager()).thenReturn(plugins);
    when(server.getScheduler()).thenReturn(scheduler);

    BookCopyUnblocker.register(plugin);
    ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler).runTask(eq(plugin), task.capture());

    task.getValue().run();
    verifyNoInteractions(logger);

    when(plugins.isPluginEnabled("MMOItems")).thenReturn(true);
    task.getValue().run();
    verify(logger).warning("MMOItems craft handler not found; book copies stay blocked");

    mmoItemsCancels(InventoryClickEvent.getHandlerList());
    task.getValue().run();
    verify(logger).info("Book copies: wrapped 1 MMOItems click handlers");
  }

  private void mmoItemsCancels(HandlerList list) {
    list.register(
        new RegisteredListener(
            disable,
            (l, e) -> ((Cancellable) e).setCancelled(true),
            EventPriority.NORMAL,
            mmoItems,
            false));
  }

  private void otherCancels(HandlerList list, EventPriority priority) {
    list.register(
        new RegisteredListener(
            new Listener() {}, (l, e) -> ((Cancellable) e).setCancelled(true), priority, other, false));
  }

  private void dispatch(InventoryClickEvent event) {
    for (RegisteredListener registered : handlers.getRegisteredListeners()) call(registered, event);
  }

  private static void call(RegisteredListener registered, InventoryClickEvent event) {
    try {
      registered.callEvent(event);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static ComplexRecipe bookCloning() {
    ComplexRecipe recipe = mock(ComplexRecipe.class);
    when(recipe.getKey()).thenReturn(BookCopyUnblocker.BOOK_CLONING);
    return recipe;
  }

  private static InventoryView view() {
    InventoryView view = mock(InventoryView.class);
    when(view.getTopInventory()).thenReturn(mock(CraftingInventory.class));
    return view;
  }

  private static CraftItemEvent craft(Recipe recipe) {
    return new CraftItemEvent(
        recipe, view(), InventoryType.SlotType.RESULT, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
  }
}
