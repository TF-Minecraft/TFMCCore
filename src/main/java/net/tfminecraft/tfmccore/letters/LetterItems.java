package net.tfminecraft.tfmccore.letters;

import java.util.ArrayList;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tfmccore.TFMCCore;

public class LetterItems {

    private final NamespacedKey sealedLetterKey;

    public LetterItems() {
        sealedLetterKey = new NamespacedKey(TFMCCore.getInstance(), "sealed_letter");
    }

    public boolean isLetter(ItemStack item) {
        if (item == null) return false;
        if (item.getType() != Material.WRITABLE_BOOK) return false;
        try {
            return TLibs.getItemAPI().getChecker().checkItemWithPath(item, LetterConfig.letterPath);
        } catch (Exception ex) {
            warn("Failed to validate letter: " + ex.getMessage());
            return false;
        }
    }

    public boolean isSealedLetter(ItemStack item) {
        if (item == null) return false;
        if (item.getType() != Material.WRITTEN_BOOK) return false;
        if (!item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(sealedLetterKey, PersistentDataType.BYTE);
    }

    public ItemStack createSealedLetter(BookMeta source, Player signer) {
        try {
            ItemStack stack = template(LetterConfig.writtenLetterPath);
            if (stack == null) return null;
            BookMeta meta = (BookMeta) stack.getItemMeta();
            if (meta != null) {
                copyBookContent(source, meta);
                // Marker that identifies this item as a sealed letter when it is right-clicked.
                meta.getPersistentDataContainer().set(sealedLetterKey, PersistentDataType.BYTE, (byte) 1);
                if (LetterConfig.hideAuthor) {
                    meta.setAuthor(null);
                } else {
                    meta.setAuthor(signer.getName());
                }
                stack.setItemMeta(meta);
            }
            return stack;
        } catch (Exception ex) {
            warn("Failed to create sealed letter: " + ex.getMessage());
            return null;
        }
    }

    /**
     * Unsigned letter after an edit (not a sign). Fresh template so the skin
     * survives vanilla's plain book write, with the new pages and the previous
     * display name, lore, and PDC.
     */
    public ItemStack createEditedLetter(BookMeta source, ItemStack previous) {
        try {
            ItemStack stack = template(LetterConfig.letterPath);
            if (stack == null) return null;
            int amount = previous != null ? Math.max(1, previous.getAmount()) : 1;
            stack.setAmount(amount);
            BookMeta meta = (BookMeta) stack.getItemMeta();
            if (meta != null) {
                if (source != null) {
                    meta.setPages(source.getPages());
                }
                ItemMeta prevMeta = previous != null ? previous.getItemMeta() : null;
                if (prevMeta != null) {
                    if (prevMeta.hasDisplayName()) {
                        meta.setDisplayName(prevMeta.getDisplayName());
                    }
                    if (prevMeta.hasLore() && prevMeta.getLore() != null) {
                        meta.setLore(new ArrayList<>(prevMeta.getLore()));
                    }
                    copyPdc(prevMeta, meta);
                }
                stack.setItemMeta(meta);
            }
            return stack;
        } catch (Exception ex) {
            warn("Failed to restore edited letter: " + ex.getMessage());
            return null;
        }
    }

    public ItemStack createOpenedLetter(BookMeta source) {
        try {
            ItemStack stack = template(LetterConfig.writtenLetterOpenPath);
            if (stack == null) return null;
            BookMeta meta = (BookMeta) stack.getItemMeta();
            if (meta != null) {
                copyBookContent(source, meta);
                // No sealed marker here - an opened letter must not be openable again.
                if (LetterConfig.hideAuthor) {
                    meta.setAuthor(null);
                } else if (source.hasAuthor()) {
                    // Carry the signer's name across, or opening would reset it to the template's author.
                    meta.setAuthor(source.getAuthor());
                }
                stack.setItemMeta(meta);
            }
            return stack;
        } catch (Exception ex) {
            warn("Failed to create opened letter: " + ex.getMessage());
            return null;
        }
    }

    private ItemStack template(String path) {
        ItemStack stack = TLibs.getItemAPI().getCreator().getItemFromPath(path);
        if (stack == null) {
            warn("No item found for letters config path: " + path);
            return null;
        }
        return stack.clone();
    }

    private static void copyPdc(ItemMeta from, ItemMeta to) {
        try {
            from.getPersistentDataContainer().copyTo(to.getPersistentDataContainer(), true);
        } catch (NoSuchMethodError | UnsupportedOperationException ignored) {
            // Older API without copyTo. Name and lore are already copied.
        }
    }

    private void copyBookContent(BookMeta source, BookMeta target) {
        target.setPages(source.getPages());
        if (source.hasTitle()) {
            String title = source.getTitle();
            target.setTitle(title);
            if (LetterConfig.useTitleAsName) {
                target.setDisplayName(title);
            }
        }
    }

    private void warn(String message) {
        TFMCCore instance = TFMCCore.getInstance();
        if (instance != null) {
            instance.getLogger().warning(message);
        }
    }
}
