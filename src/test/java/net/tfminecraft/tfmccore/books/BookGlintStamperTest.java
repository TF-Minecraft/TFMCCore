package net.tfminecraft.tfmccore.books;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.tfminecraft.tfmccore.cache.Cache;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.block.Barrel;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Lectern;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class BookGlintStamperTest {
  private static final NamespacedKey KEY = BookGlintStamper.KEY;

  private final BookGlintStamper stamper = new BookGlintStamper();
  private MockedStatic<Bukkit> bukkit;
  private boolean previous;
  private int tick;

  @BeforeEach
  void setUp() {
    previous = Cache.hideBookGlint;
    Cache.hideBookGlint = true;
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::getCurrentTick).thenAnswer(call -> tick);
  }

  @AfterEach
  void restore() {
    Cache.hideBookGlint = previous;
    bukkit.close();
  }

  @Test
  void registersTheListenerAndSweepsEverySecond() {
    JavaPlugin plugin = mock(JavaPlugin.class);
    Server server = mock(Server.class);
    PluginManager plugins = mock(PluginManager.class);
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    when(plugin.getServer()).thenReturn(server);
    when(server.getPluginManager()).thenReturn(plugins);
    when(server.getScheduler()).thenReturn(scheduler);

    BookGlintStamper registered = BookGlintStamper.register(plugin);

    verify(plugins).registerEvents(registered, plugin);
    ArgumentCaptor<Runnable> sweep = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler).runTaskTimer(eq(plugin), sweep.capture(), eq(20L), eq(20L));
    Player player = player();
    online(player);
    sweep.getValue().run();
    verify(player).getInventory();
  }

  @Test
  void hidesTheGlintOfSignedBooksAndTagsThem() {
    Book book = new Book(false, null);

    ItemStack updated = stamper.updated(book.item);

    assertSame(book.copy, updated);
    verify(book.meta).setEnchantmentGlintOverride(false);
    verify(book.data).set(KEY, PersistentDataType.BYTE, (byte) 1);
    verify(book.copy).setItemMeta(book.meta);
    verify(book.item, never()).setItemMeta(any());
  }

  @Test
  void leavesOtherItemsAndBooksWithTheirOwnGlintAlone() {
    assertNull(stamper.updated(null));
    ItemStack quill = mock(ItemStack.class);
    when(quill.getType()).thenReturn(Material.WRITABLE_BOOK);
    assertNull(stamper.updated(quill));
    ItemStack bare = mock(ItemStack.class);
    when(bare.getType()).thenReturn(Material.WRITTEN_BOOK);
    assertNull(stamper.updated(bare));
    assertNull(stamper.updated(new Book(false, true).item));
    assertNull(stamper.updated(new Book(false, false).item));
    assertNull(stamper.updated(new Book(true, false).item));
  }

  @Test
  void turningTheOptionOffRestoresOnlyTaggedBooks() {
    Cache.hideBookGlint = false;
    assertNull(stamper.updated(new Book(false, null).item));
    assertNull(stamper.updated(new Book(false, false).item));

    Book hidden = new Book(true, false);
    assertSame(hidden.copy, stamper.updated(hidden.item));
    verify(hidden.data).remove(KEY);
    verify(hidden.meta).setEnchantmentGlintOverride(null);
    verify(hidden.copy).setItemMeta(hidden.meta);

    // Someone turned the glint back on since; keep their choice and drop only the tag
    Book shown = new Book(true, true);
    assertSame(shown.copy, stamper.updated(shown.item));
    verify(shown.data).remove(KEY);
    verify(shown.meta, never()).setEnchantmentGlintOverride(any());

    Book untouched = new Book(true, null);
    assertSame(untouched.copy, stamper.updated(untouched.item));
    verify(untouched.meta, never()).setEnchantmentGlintOverride(any());
  }

  @Test
  void inventoriesGetOnlyTheChangedSlotsBack() {
    Inventory inventory = mock(Inventory.class);
    Book book = new Book(false, null);
    ItemStack stone = mock(ItemStack.class);
    when(stone.getType()).thenReturn(Material.STONE);
    when(inventory.getContents()).thenReturn(new ItemStack[] {stone, null, book.item});

    stamper.update(inventory);

    verify(inventory).setItem(2, book.copy);
    verify(inventory, times(1)).setItem(anyInt(), any());
  }

  @Test
  void sweepsSkipPlayersWhoseBookIsBeingEdited() {
    Player editing = player();
    Player reading = player();
    Player idle = player();
    online(editing, reading, idle);
    stamper.onEdit(edit(editing));
    PlayerInteractEvent open = mock(PlayerInteractEvent.class);
    when(open.getPlayer()).thenReturn(reading);
    ItemStack book = new Book(true, false).item;
    when(open.getItem()).thenReturn(book);
    stamper.onInteract(open);

    tick = BookGlintStamper.QUIET_TICKS - 1;
    stamper.sweep();

    verify(editing, never()).getInventory();
    verify(reading, never()).getInventory();
    verify(idle).getInventory();

    tick = BookGlintStamper.QUIET_TICKS;
    stamper.sweep();

    verify(editing).getInventory();
    verify(reading).getInventory();
  }

  @Test
  void onlySignedBooksInHandQuietTheSweep() {
    Player player = player();
    online(player);
    PlayerInteractEvent empty = mock(PlayerInteractEvent.class);
    stamper.onInteract(empty);
    PlayerInteractEvent stone = mock(PlayerInteractEvent.class);
    ItemStack item = mock(ItemStack.class);
    when(item.getType()).thenReturn(Material.STONE);
    when(stone.getItem()).thenReturn(item);
    stamper.onInteract(stone);

    stamper.sweep();

    verify(player).getInventory();
    verify(empty, never()).getPlayer();
    verify(stone, never()).getPlayer();
  }

  @Test
  void quittingForgetsTheQuietWindow() {
    Player player = player();
    online(player);
    stamper.onEdit(edit(player));

    PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
    when(quit.getPlayer()).thenReturn(player);
    stamper.onQuit(quit);
    stamper.sweep();

    verify(player).getInventory();
  }

  @Test
  void openingAWorldContainerUpdatesItsBooks() {
    Inventory barrel = storage(mock(Barrel.class));
    Book book = new Book(false, null);
    when(barrel.getContents()).thenReturn(new ItemStack[] {book.item});

    stamper.onOpen(open(barrel));

    verify(barrel).setItem(0, book.copy);

    Inventory menu = storage(mock(InventoryHolder.class));
    stamper.onOpen(open(menu));
    verify(menu, never()).getContents();
  }

  @Test
  void storageMeansContainersInTheWorldButNotLecterns() {
    assertTrue(BookGlintStamper.isStorage(storage(mock(Barrel.class))));
    assertTrue(BookGlintStamper.isStorage(storage(mock(DoubleChest.class))));
    assertTrue(BookGlintStamper.isStorage(storage(mock(StorageMinecart.class))));
    Player player = mock(Player.class);
    Inventory enderChest = storage(player);
    when(player.getEnderChest()).thenReturn(enderChest);
    assertTrue(BookGlintStamper.isStorage(enderChest));
    // A plugin menu opened with the player as holder
    assertFalse(BookGlintStamper.isStorage(storage(player)));
    assertFalse(BookGlintStamper.isStorage(storage(mock(InventoryHolder.class))));
    assertFalse(BookGlintStamper.isStorage(storage(null)));
    assertFalse(
        BookGlintStamper.isStorage(storage(mock(Lectern.class))));
  }

  private static PlayerEditBookEvent edit(Player player) {
    PlayerEditBookEvent event = mock(PlayerEditBookEvent.class);
    when(event.getPlayer()).thenReturn(player);
    return event;
  }

  private static InventoryOpenEvent open(Inventory top) {
    InventoryOpenEvent event = mock(InventoryOpenEvent.class);
    InventoryView view = mock(InventoryView.class);
    when(event.getView()).thenReturn(view);
    when(view.getTopInventory()).thenReturn(top);
    return event;
  }

  private static Inventory storage(InventoryHolder holder) {
    Inventory inventory = mock(Inventory.class);
    when(inventory.getHolder(false)).thenReturn(holder);
    return inventory;
  }

  private static Player player() {
    Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    PlayerInventory inventory = mock(PlayerInventory.class);
    when(inventory.getContents()).thenReturn(new ItemStack[0]);
    when(player.getInventory()).thenReturn(inventory);
    return player;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void online(Player... players) {
    bukkit.when(Bukkit::getOnlinePlayers).thenReturn((Collection) List.of(players));
  }

  /** A signed book, optionally tagged by the stamper, with the given stored glint (null: none). */
  private static final class Book {
    final ItemStack item = mock(ItemStack.class);
    final ItemStack copy = mock(ItemStack.class);
    final ItemMeta meta = mock(ItemMeta.class);
    final PersistentDataContainer data = mock(PersistentDataContainer.class);

    Book(boolean tagged, Boolean glint) {
      when(item.getType()).thenReturn(Material.WRITTEN_BOOK);
      when(item.getItemMeta()).thenReturn(meta);
      when(item.clone()).thenReturn(copy);
      when(meta.getPersistentDataContainer()).thenReturn(data);
      when(data.has(KEY, PersistentDataType.BYTE)).thenReturn(tagged);
      when(meta.hasEnchantmentGlintOverride()).thenReturn(glint != null);
      if (glint != null) when(meta.getEnchantmentGlintOverride()).thenReturn(glint);
    }
  }
}
