package net.tfminecraft.tfmccore.books;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.lumine.mythic.lib.api.item.NBTItem;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.tfminecraft.tfmccore.cache.Cache;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ComplexRecipe;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BookCopyListenerTest {

  private final List<ItemStack> tested = new ArrayList<>();
  private final Predicate<ItemStack> disabled =
      item -> {
        tested.add(item);
        return item.getType() == Material.WRITABLE_BOOK;
      };
  private final BookCopyListener listener = new BookCopyListener(disabled);
  private boolean previous;

  @BeforeEach
  void enable() {
    previous = Cache.allowBookCopies;
    Cache.allowBookCopies = true;
  }

  @AfterEach
  void restore() {
    Cache.allowBookCopies = previous;
  }

  @Test
  void undoesOnlyTheCancelMadeBetweenItsTwoHandlers() {
    CraftItemEvent event = copy(item(Material.WRITTEN_BOOK), item(Material.WRITABLE_BOOK));

    listener.beforeMmoItems(event);
    event.setCancelled(true);
    listener.afterMmoItems(event);

    assertFalse(event.isCancelled());
  }

  @Test
  void keepsCancelsMadeBeforeMmoItems() {
    CraftItemEvent event = copy(item(Material.WRITABLE_BOOK));
    event.setCancelled(true);

    listener.beforeMmoItems(event);
    listener.afterMmoItems(event);

    assertTrue(event.isCancelled());
    assertTrue(tested.isEmpty());
  }

  @Test
  void leavesAllowedCraftsAlone() {
    CraftItemEvent event = copy(item(Material.WRITABLE_BOOK));

    listener.beforeMmoItems(event);
    listener.afterMmoItems(event);

    assertFalse(event.isCancelled());
    assertTrue(tested.isEmpty());
  }

  @Test
  void keepsCancelsWithoutAnMmoItemsDisabledIngredient() {
    ItemStack plain = item(Material.WRITTEN_BOOK);
    CraftItemEvent event = copy(null, item(Material.AIR), plain);

    listener.beforeMmoItems(event);
    event.setCancelled(true);
    listener.afterMmoItems(event);

    assertTrue(event.isCancelled());
    assertEquals(List.of(plain), tested);
  }

  @Test
  void ignoresOtherRecipes() {
    ComplexRecipe other = mock(ComplexRecipe.class);
    when(other.getKey()).thenReturn(NamespacedKey.minecraft("map_cloning"));
    for (Recipe recipe : List.of(other, mock(Recipe.class))) {
      CraftItemEvent event = craft(recipe, item(Material.WRITABLE_BOOK));

      listener.beforeMmoItems(event);
      event.setCancelled(true);
      listener.afterMmoItems(event);

      assertTrue(event.isCancelled());
    }
    assertTrue(tested.isEmpty());
  }

  @Test
  void canBeTurnedOff() {
    Cache.allowBookCopies = false;
    CraftItemEvent event = copy(item(Material.WRITABLE_BOOK));

    listener.beforeMmoItems(event);
    event.setCancelled(true);
    listener.afterMmoItems(event);

    assertTrue(event.isCancelled());
  }

  @Test
  void onlyChecksTheEventItSawAndOnlyOnce() {
    CraftItemEvent first = copy(item(Material.WRITABLE_BOOK));
    CraftItemEvent second = copy(item(Material.WRITABLE_BOOK));

    listener.beforeMmoItems(first);
    second.setCancelled(true);
    listener.afterMmoItems(second);
    assertTrue(second.isCancelled());

    first.setCancelled(true);
    listener.afterMmoItems(first);
    assertFalse(first.isCancelled());

    first.setCancelled(true);
    listener.afterMmoItems(first);
    assertTrue(first.isCancelled());
  }

  @Test
  void readsMmoItemsDisableCraftingTag() {
    ItemStack item = item(Material.WRITABLE_BOOK);
    NBTItem tags = mock(NBTItem.class);
    try (var nbt = mockStatic(NBTItem.class)) {
      nbt.when(() -> NBTItem.get(item)).thenReturn(tags);

      assertFalse(BookCopyListener.mmoItemsDisablesCrafting(item));
      when(tags.getBoolean("MMOITEMS_DISABLE_CRAFTING")).thenReturn(true);
      assertTrue(BookCopyListener.mmoItemsDisablesCrafting(item));
    }
  }

  @Test
  void registersOnlyWithMythicLib() {
    JavaPlugin plugin = mock(JavaPlugin.class);
    Server server = mock(Server.class);
    PluginManager plugins = mock(PluginManager.class);
    when(plugin.getServer()).thenReturn(server);
    when(server.getPluginManager()).thenReturn(plugins);

    BookCopyListener.register(plugin);
    verify(plugins, never()).registerEvents(any(), any());

    when(plugins.isPluginEnabled("MythicLib")).thenReturn(true);
    BookCopyListener.register(plugin);
    verify(plugins).registerEvents(any(BookCopyListener.class), eq(plugin));
  }

  private static ItemStack item(Material type) {
    ItemStack item = mock(ItemStack.class);
    when(item.getType()).thenReturn(type);
    when(item.isEmpty()).thenReturn(type == Material.AIR);
    return item;
  }

  private static CraftItemEvent copy(ItemStack... matrix) {
    ComplexRecipe recipe = mock(ComplexRecipe.class);
    when(recipe.getKey()).thenReturn(NamespacedKey.minecraft("book_cloning"));
    return craft(recipe, matrix);
  }

  private static CraftItemEvent craft(Recipe recipe, ItemStack... matrix) {
    CraftingInventory inventory = mock(CraftingInventory.class);
    when(inventory.getMatrix()).thenReturn(matrix);
    InventoryView view = mock(InventoryView.class);
    when(view.getTopInventory()).thenReturn(inventory);
    return new CraftItemEvent(
        recipe,
        view,
        InventoryType.SlotType.RESULT,
        0,
        ClickType.LEFT,
        InventoryAction.PICKUP_ALL);
  }
}
