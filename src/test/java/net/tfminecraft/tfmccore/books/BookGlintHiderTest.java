package net.tfminecraft.tfmccore.books;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.Pair;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import java.util.List;
import java.util.logging.Logger;
import net.tfminecraft.tfmccore.cache.Cache;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

class BookGlintHiderTest {
  private final JavaPlugin plugin = mock(JavaPlugin.class);
  private final Server server = mock(Server.class);
  private final PluginManager plugins = mock(PluginManager.class);
  private final Logger logger = mock(Logger.class);
  private boolean previous;
  private BookGlintHider hider;
  private MockedStatic<Bukkit> bukkit;
  private MockedStatic<MinecraftReflection> reflection;
  private MockedConstruction<ItemStack> nativeStacks;

  /** ProtocolLib needs Bukkit material classes during static packet initialization. */
  @BeforeEach
  void setUp() {
    Server running = mock(Server.class);
    when(running.getVersion()).thenReturn("Paper (MC: 1.21.10)");
    when(running.getBukkitVersion()).thenReturn("1.21.10-R0.1-SNAPSHOT");
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::getServer).thenReturn(running);
    bukkit.when(Bukkit::getVersion).thenReturn("Paper (MC: 1.21.10)");
    bukkit.when(Bukkit::getBukkitVersion).thenReturn("1.21.10-R0.1-SNAPSHOT");
    bukkit.when(Bukkit::getLogger).thenReturn(Logger.getAnonymousLogger());
    reflection = mockStatic(MinecraftReflection.class);
    reflection.when(MinecraftReflection::getItemStackClass).thenReturn(ItemStack.class);
    reflection.when(MinecraftReflection::getBlockClass).thenReturn(Block.class);
    nativeStacks = mockConstruction(ItemStack.class);
    previous = Cache.hideBookGlint;
    Cache.hideBookGlint = true;
    when(plugin.getServer()).thenReturn(server);
    when(plugin.getLogger()).thenReturn(logger);
    when(server.getPluginManager()).thenReturn(plugins);
    hider = new BookGlintHider(plugin);
  }

  @AfterEach
  void restore() {
    Cache.hideBookGlint = previous;
    nativeStacks.close();
    reflection.close();
    bukkit.close();
  }

  @Test
  void registersOnlyWhenProtocolLibIsEnabled() {
    ProtocolManager manager = mock(ProtocolManager.class);
    try (var library = mockStatic(ProtocolLibrary.class)) {
      library.when(ProtocolLibrary::getProtocolManager).thenReturn(manager);

      BookGlintHider.register(plugin);

      verify(logger).warning("ProtocolLib missing; signed books keep their glint");
      verifyNoInteractions(manager);

      when(plugins.isPluginEnabled("ProtocolLib")).thenReturn(true);
      BookGlintHider.register(plugin);

      verify(manager).addPacketListener(any(BookGlintHider.class));
      assertTrue(hider.getSendingWhitelist().getTypes().contains(PacketType.Play.Server.WINDOW_ITEMS));
      assertTrue(
          hider
              .getReceivingWhitelist()
              .getTypes()
              .contains(PacketType.Play.Client.SET_CREATIVE_SLOT));
    }
  }

  @Test
  void hideCopiesOnlySignedBooksThatStillGlint() {
    assertNull(BookGlintHider.hide(null));
    assertNull(BookGlintHider.hide(stack(Material.WRITABLE_BOOK, mock(ItemMeta.class))));
    assertNull(BookGlintHider.hide(stack(Material.WRITTEN_BOOK, null)));
    ItemMeta own = mock(ItemMeta.class);
    when(own.hasEnchantmentGlintOverride()).thenReturn(true);
    assertNull(BookGlintHider.hide(stack(Material.WRITTEN_BOOK, own)));

    ItemMeta meta = mock(ItemMeta.class);
    ItemStack book = stack(Material.WRITTEN_BOOK, meta);
    ItemStack copy = book.clone();

    assertSame(copy, BookGlintHider.hide(book));

    verify(meta).setEnchantmentGlintOverride(false);
    verify(copy).setItemMeta(meta);
    verify(book, never()).setItemMeta(any());
  }

  @Test
  void restoreDropsOnlyAHiddenGlintFromSignedBooks() {
    assertNull(BookGlintHider.restore(null));
    assertNull(BookGlintHider.restore(stack(Material.WRITABLE_BOOK, mock(ItemMeta.class))));
    assertNull(BookGlintHider.restore(stack(Material.WRITTEN_BOOK, null)));
    assertNull(BookGlintHider.restore(stack(Material.WRITTEN_BOOK, mock(ItemMeta.class))));
    assertNull(BookGlintHider.restore(stack(Material.WRITTEN_BOOK, glint(true))));

    ItemMeta meta = glint(false);
    ItemStack book = stack(Material.WRITTEN_BOOK, meta);
    ItemStack copy = book.clone();

    assertSame(copy, BookGlintHider.restore(book));

    verify(meta).setEnchantmentGlintOverride(null);
    verify(copy).setItemMeta(meta);
    verify(book, never()).setItemMeta(any());
  }

  @Test
  void disabledOptionLeavesSentPacketsAlone() {
    Cache.hideBookGlint = false;
    PacketEvent event = event(PacketType.Play.Server.SET_SLOT);

    hider.onPacketSending(event);

    verify(event, never()).getPacket();
  }

  @Test
  void singleItemPacketsSendTheHiddenCopy() {
    PacketEvent event = event(PacketType.Play.Server.SET_SLOT);
    StructureModifier<ItemStack> items = items(event.getPacket(), book());

    hider.onPacketSending(event);

    verify(items).write(eq(0), argThat(this::hidden));

    PacketEvent other = event(PacketType.Play.Server.SET_CURSOR_ITEM);
    StructureModifier<ItemStack> unchanged = items(other.getPacket(), plain());

    hider.onPacketSending(other);

    verify(unchanged, never()).write(anyInt(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void windowItemsHideBooksInTheListAndOnTheCursor() {
    PacketEvent event = event(PacketType.Play.Server.WINDOW_ITEMS);
    PacketContainer packet = event.getPacket();
    ItemStack other = plain();
    StructureModifier<List<ItemStack>> lists = mock(StructureModifier.class);
    when(packet.getItemListModifier()).thenReturn(lists);
    ItemStack book = book();
    when(lists.read(0)).thenReturn(List.of(book, other));
    StructureModifier<ItemStack> cursor = items(packet, book());

    hider.onPacketSending(event);

    verify(lists).write(eq(0), argThat(list -> hidden(list.get(0)) && list.get(1) == other));
    verify(cursor).write(eq(0), argThat(this::hidden));

    when(lists.read(0)).thenReturn(List.of(other));
    when(cursor.read(0)).thenReturn(other);
    clearInvocations(lists, cursor);

    hider.onPacketSending(event);

    verify(lists, never()).write(anyInt(), any());
    verify(cursor, never()).write(anyInt(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void equipmentHidesBooksHeldOrWornByEntities() {
    PacketEvent event = event(PacketType.Play.Server.ENTITY_EQUIPMENT);
    StructureModifier<List<Pair<EnumWrappers.ItemSlot, ItemStack>>> slots =
        mock(StructureModifier.class);
    when(event.getPacket().getSlotStackPairLists()).thenReturn(slots);
    ItemStack other = plain();
    List<Pair<EnumWrappers.ItemSlot, ItemStack>> pairs =
        List.of(new Pair<>(EnumWrappers.ItemSlot.MAINHAND, book()), new Pair<>(EnumWrappers.ItemSlot.OFFHAND, other));
    when(slots.read(0)).thenReturn(pairs);

    hider.onPacketSending(event);

    assertTrue(hidden(pairs.get(0).getSecond()));
    assertSame(other, pairs.get(1).getSecond());
    verify(slots).write(0, pairs);

    when(slots.read(0)).thenReturn(List.of(new Pair<>(EnumWrappers.ItemSlot.HEAD, other)));
    clearInvocations(slots);

    hider.onPacketSending(event);

    verify(slots, never()).write(anyInt(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void metadataReplacesOnlyBookValues() {
    PacketEvent event = event(PacketType.Play.Server.ENTITY_METADATA);
    StructureModifier<List<WrappedDataValue>> data = mock(StructureModifier.class);
    when(event.getPacket().getDataValueCollectionModifier()).thenReturn(data);
    WrappedDataWatcher.Serializer serializer = mock(WrappedDataWatcher.Serializer.class);
    Object nativeBook = new Object();
    Object nativeStone = new Object();
    ItemStack book = book();
    ItemStack stone = plain();
    reflection.when(() -> MinecraftReflection.isItemStack(nativeBook)).thenReturn(true);
    reflection.when(() -> MinecraftReflection.isItemStack(nativeStone)).thenReturn(true);
    reflection.when(() -> MinecraftReflection.getBukkitItemStack(nativeBook)).thenReturn(book);
    reflection.when(() -> MinecraftReflection.getBukkitItemStack(nativeStone)).thenReturn(stone);
    WrappedDataValue item = value(8, serializer, nativeBook);
    WrappedDataValue flags = value(0, serializer, (byte) 0);
    WrappedDataValue replaced = mock(WrappedDataValue.class);
    when(data.read(0)).thenReturn(List.of(item, flags));
    try (var values = mockStatic(WrappedDataValue.class)) {
      values
          .when(() -> WrappedDataValue.fromWrappedValue(eq(8), eq(serializer), argThat(this::hidden)))
          .thenReturn(replaced);

      hider.onPacketSending(event);

      verify(data).write(0, List.of(replaced, flags));
      verify(flags, never()).getValue();

      WrappedDataValue other = value(8, serializer, nativeStone);
      when(data.read(0)).thenReturn(List.of(flags, other));
      clearInvocations(data);

      hider.onPacketSending(event);

      verify(data, never()).write(anyInt(), any());
    }
  }

  @Test
  void creativeSlotsSaveBooksWithoutTheHiddenGlint() {
    Cache.hideBookGlint = false;
    PacketEvent event = event(PacketType.Play.Client.SET_CREATIVE_SLOT);
    ItemMeta meta = glint(false);
    ItemStack book = stack(Material.WRITTEN_BOOK, meta);
    StructureModifier<ItemStack> items = items(event.getPacket(), book);

    hider.onPacketReceiving(event);

    verify(items).write(0, book.clone());
    verify(meta).setEnchantmentGlintOverride(null);

    ItemStack stone = plain();
    when(items.read(0)).thenReturn(stone);
    clearInvocations(items);

    hider.onPacketReceiving(event);

    verify(items, never()).write(anyInt(), any());
  }

  private boolean hidden(ItemStack item) {
    return item != null && item.getItemMeta() == HIDDEN;
  }

  private static final ItemMeta HIDDEN = mock(ItemMeta.class);

  /** A signed book whose copy reports {@link #HIDDEN} meta once the glint was set. */
  private static ItemStack book() {
    ItemMeta meta = mock(ItemMeta.class);
    ItemStack copy = mock(ItemStack.class);
    when(copy.getItemMeta()).thenReturn(HIDDEN);
    ItemStack book = mock(ItemStack.class);
    when(book.getType()).thenReturn(Material.WRITTEN_BOOK);
    when(book.getItemMeta()).thenReturn(meta);
    when(book.clone()).thenReturn(copy);
    return book;
  }

  private static ItemStack plain() {
    return stack(Material.STONE, mock(ItemMeta.class));
  }

  private static ItemStack stack(Material type, ItemMeta meta) {
    ItemStack item = mock(ItemStack.class);
    ItemStack copy = mock(ItemStack.class);
    when(item.getType()).thenReturn(type);
    when(item.getItemMeta()).thenReturn(meta);
    when(item.clone()).thenReturn(copy);
    return item;
  }

  private static ItemMeta glint(boolean value) {
    ItemMeta meta = mock(ItemMeta.class);
    when(meta.hasEnchantmentGlintOverride()).thenReturn(true);
    when(meta.getEnchantmentGlintOverride()).thenReturn(value);
    return meta;
  }

  private static WrappedDataValue value(
      int index, WrappedDataWatcher.Serializer serializer, Object value) {
    WrappedDataValue wrapped = mock(WrappedDataValue.class);
    when(wrapped.getIndex()).thenReturn(index);
    when(wrapped.getSerializer()).thenReturn(serializer);
    when(wrapped.getRawValue()).thenReturn(value);
    return wrapped;
  }

  private static PacketEvent event(PacketType type) {
    PacketEvent event = mock(PacketEvent.class);
    PacketContainer packet = mock(PacketContainer.class);
    when(event.getPacket()).thenReturn(packet);
    when(event.getPacketType()).thenReturn(type);
    return event;
  }

  @SuppressWarnings("unchecked")
  private static StructureModifier<ItemStack> items(PacketContainer packet, ItemStack item) {
    StructureModifier<ItemStack> items = mock(StructureModifier.class);
    when(packet.getItemModifier()).thenReturn(items);
    when(items.read(0)).thenReturn(item);
    return items;
  }
}
