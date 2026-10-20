package net.tfminecraft.tfmccore.books;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.Pair;
import com.comphenix.protocol.wrappers.WrappedDataValue;

import net.tfminecraft.tfmccore.cache.Cache;

/**
 * Hides the enchantment glint on signed books shown on entities (dropped items, item frames,
 * displays and held or worn books) that {@link BookGlintStamper} has not reached. Only entity
 * packets change: clients hash the stacks they click in inventories, so an inventory item
 * changed in packets would never match the server's copy.
 */
public final class BookGlintHider extends PacketAdapter {

    BookGlintHider(JavaPlugin plugin) {
        // HIGHEST: run after plugins that rewrite items so their copy is the one changed
        super(plugin, ListenerPriority.HIGHEST,
                PacketType.Play.Server.ENTITY_EQUIPMENT,
                PacketType.Play.Server.ENTITY_METADATA);
    }

    public static void register(JavaPlugin plugin) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            plugin.getLogger().warning("ProtocolLib missing; signed books keep their glint");
            return;
        }
        ProtocolLibrary.getProtocolManager().addPacketListener(new BookGlintHider(plugin));
    }

    @Override
    public void onPacketSending(PacketEvent event) {
        if (!Cache.hideBookGlint) return;
        PacketContainer packet = event.getPacket();
        PacketType type = event.getPacketType();
        if (type == PacketType.Play.Server.ENTITY_EQUIPMENT) {
            StructureModifier<List<Pair<EnumWrappers.ItemSlot, ItemStack>>> slots = packet.getSlotStackPairLists();
            List<Pair<EnumWrappers.ItemSlot, ItemStack>> pairs = slots.read(0);
            boolean changed = false;
            for (Pair<EnumWrappers.ItemSlot, ItemStack> pair : pairs) {
                ItemStack hidden = hide(pair.getSecond());
                if (hidden != null) {
                    pair.setSecond(hidden);
                    changed = true;
                }
            }
            if (changed) slots.write(0, pairs);
        } else {
            // ENTITY_METADATA: dropped items, item frames and item displays
            StructureModifier<List<WrappedDataValue>> data = packet.getDataValueCollectionModifier();
            List<WrappedDataValue> values = new ArrayList<>(data.read(0));
            boolean changed = false;
            for (int i = 0; i < values.size(); i++) {
                WrappedDataValue value = values.get(i);
                // Check the raw value first; wrapping every value would convert each one
                Object raw = value.getRawValue();
                ItemStack hidden = MinecraftReflection.isItemStack(raw) ? hide(MinecraftReflection.getBukkitItemStack(raw)) : null;
                if (hidden != null) {
                    values.set(i, WrappedDataValue.fromWrappedValue(value.getIndex(), value.getSerializer(), hidden));
                    changed = true;
                }
            }
            if (changed) data.write(0, values);
        }
    }

    /** A copy of a signed book with its glint turned off, or null when nothing changes. */
    static ItemStack hide(ItemStack item) {
        if (item == null || item.getType() != Material.WRITTEN_BOOK) return null;
        ItemMeta meta = item.getItemMeta();
        // Glint true is the written book default and is never stored, so only an item
        // that already turned it off has an override
        if (meta == null || meta.hasEnchantmentGlintOverride()) return null;
        meta.setEnchantmentGlintOverride(false);
        // Packet stacks can share data with the server's copy; never edit them in place
        ItemStack copy = item.clone();
        copy.setItemMeta(meta);
        return copy;
    }
}
