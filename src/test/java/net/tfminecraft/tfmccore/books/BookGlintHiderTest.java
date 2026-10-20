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
import java.util.Set;
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
      // Inventory packets carry stacks the client hashes back on click; never touch them
      assertEquals(
          Set.of(PacketType.Play.Server.ENTITY_EQUIPMENT, PacketType.Play.Server.ENTITY_METADATA),
          hider.getSendingWhitelist().getTypes());
      assertTrue(hider.getReceivingWhitelist().getTypes().isEmpty());
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
  void disabledOptionLeavesSentPacketsAlone() {
    Cache.hideBookGlint = false;
    PacketEvent event = event(PacketType.Play.Server.ENTITY_EQUIPMENT);

    hider.onPacketSending(event);

    verify(event, never()).getPacket();
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
}
