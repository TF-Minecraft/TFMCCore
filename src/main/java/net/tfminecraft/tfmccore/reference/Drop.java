package net.tfminecraft.tfmccore.reference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.TLibs;

public class Drop {
    private String id;
    private boolean vanillaDrops;

    private Map<Material, Double> blocks = new HashMap<>();
    private Map<String, Double> mults = new HashMap<>();
    private Map<String, Double> tools = new HashMap<>();
    private List<String> requiredPermissions = new ArrayList<>();
    private List<DropEntry> drops = new ArrayList<>();

    public Drop(String key, ConfigurationSection config) {
        id = key;
        vanillaDrops = config.getBoolean("vanilla_drops", config.getBoolean("vanilla-drops", true));
        requiredPermissions.addAll(config.getStringList("required_permissions"));
        if (requiredPermissions.isEmpty()) {
            requiredPermissions.addAll(config.getStringList("required-permissions"));
        }

        List<String> materialList = config.getStringList("materials");
        if (materialList.isEmpty()) {
            materialList = config.getStringList("material");
        }
        for(String s : materialList) {
            String[] args = s.split("\\(");
            Material m = Material.AIR;
            try {
                m = Material.valueOf(args[0].toUpperCase());
            } catch (Exception e) {
                Bukkit.getLogger().info("[TFMCCore] could not convert "+args[0]+" to a material");
            }
            if(m.equals(Material.AIR)) continue;
            if(args.length == 1) blocks.put(m, 1.0);
            else {
                String mult = args[1].replace(")", "");
                try {
                    double d = Double.parseDouble(mult);
                    blocks.put(m, d);
                } catch (Exception e) {
                    Bukkit.getLogger().info("[TFMCCore] could not parse "+mult+" to a Double");
                }
            }
        }
        if(config.contains("mults")) {
            for(String s : config.getStringList("mults")) {
                String[] args = s.split("\\(");
                if(args.length <= 1) continue;
                else {
                    String mult = args[1].replace(")", "");
                    try {
                        double d = Double.parseDouble(mult);
                        mults.put(args[0], d);
                    } catch (Exception e) {
                        Bukkit.getLogger().info("[TFMCCore] could not parse "+mult+" to a Double");
                    }
                }
            }
        }
        if(config.contains("tools")) {
            for(String s : config.getStringList("tools")) {
                parseTool(s);
            }
        }
        if(config.contains("tool")) parseTool(config.getString("tool"));

        for(String s : config.getStringList("drops")) {
            drops.add(new DropEntry(s));
        }
    }

    private void parseTool(String s) {
        if (s == null || s.isBlank() || s.equalsIgnoreCase("none")) return;
        String[] args = s.split("\\(");
        String path = args[0].trim();
        if (path.isEmpty()) return;
        if (!path.contains(".")) {
            path = "m.tools." + path;
        } else if (!path.startsWith("m.") && !path.startsWith("v.") && !path.startsWith("ia.")) {
            path = "m." + path;
        }
        if(args.length <= 1) tools.put(path, 1.0);
        else {
            String mult = args[1].replace(")", "");
            try {
                double d = Double.parseDouble(mult);
                tools.put(path, d);
            } catch (Exception e) {
                Bukkit.getLogger().info("[TFMCCore] could not parse "+mult+" to a Double");
            }
        }
    }

    public String getId() {
        return id;
    }

    public boolean appliesTo(Block block) {
        return block != null && blocks.containsKey(block.getType());
    }

    public boolean appliesToMaterial(Material material) {
        return material != null && blocks.containsKey(material);
    }

    public boolean appliesTo(Player player, Block block, ItemStack tool) {
        return skipReason(player, block, tool) == null;
    }

    public String skipReason(Player player, Block block, ItemStack tool) {
        if (!appliesTo(block)) {
            return "material " + (block == null ? "null" : block.getType()) + " not in table";
        }
        return skipReasonForBroken(player, tool, block.getType());
    }

    public String skipReasonForBroken(Player player, ItemStack tool, Material broken) {
        if (!appliesToMaterial(broken)) {
            return "material " + broken + " not in table";
        }
        for (String perm : requiredPermissions) {
            if (perm != null && !perm.isBlank() && !player.hasPermission(perm)) {
                return "missing permission " + perm;
            }
        }
        if (!tools.isEmpty()) {
            boolean matched = false;
            for (String t : tools.keySet()) {
                if (TLibs.getItemAPI().getChecker().checkItemWithPath(tool, t)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return "tool did not match " + tools.keySet();
            }
        }
        return null;
    }

    public boolean keepsVanillaDrops() {
        return vanillaDrops;
    }

    public boolean hasVanillaDrops(Player player, Block block, ItemStack tool) {
        if (!appliesTo(player, block, tool)) return true;
        return vanillaDrops;
    }

    public void trigger(Player p, Block block, ItemStack tool) {
        trigger(p, block, tool, block.getType());
    }

    public void trigger(Player p, Block block, ItemStack tool, Material broken) {
        String skip = skipReasonForBroken(p, tool, broken);
        if (skip != null) {
            DropDebug.log("table " + id + " skip trigger: " + skip);
            return;
        }
        DropDebug.log("table " + id + " roll entries=" + drops.size() + " broken=" + broken);
        for (DropEntry drop : drops) {
            double chance = getFinalChance(drop.getChance(), p, tool, broken);
            double seed = Math.random();
            boolean hit = seed <= chance;
            DropDebug.log("table " + id + " entry " + drop.getItem()
                    + " chance=" + String.format("%.4f", chance)
                    + " seed=" + String.format("%.4f", seed)
                    + " " + (hit ? "HIT" : "MISS"));
            if (hit) {
                drop(drop, block);
            }
        }
    }

    private void drop(DropEntry drop, Block block) {
        ItemStack item = TLibs.getItemAPI().getCreator().getItemFromPath(drop.getItem());
        if (item == null) {
            DropDebug.log("table " + id + " could not create item " + drop.getItem());
            Bukkit.getLogger().info("[TFMCCore] could not create drop item " + drop.getItem());
            return;
        }
        int amount = drop.getAmount();
        item.setAmount(amount);
        block.getWorld().dropItem(
            block.getLocation().clone().add(0.5, 0.2, 0.5),
            item
        );
        DropDebug.log("table " + id + " spawned " + drop.getItem() + " x" + amount
                + " at " + block.getWorld().getName()
                + " " + block.getX() + "," + block.getY() + "," + block.getZ());
    }

    private double getFinalChance(Double chance, Player p, ItemStack tool, Material m) {
        chance *= blocks.get(m);
        for(String t : tools.keySet()) {
            if(TLibs.getItemAPI().getChecker().checkItemWithPath(tool, t)) {
                chance *= tools.get(t);
            }
        }
        for(Map.Entry<String, Double> mult : mults.entrySet()) {
            if(p.hasPermission(mult.getKey())) {
                chance *= mult.getValue();
            }
        }
        if(tool != null && tool.containsEnchantment(Enchantment.FORTUNE)) {
            int fortuneLevel = tool.getEnchantmentLevel(Enchantment.FORTUNE);
            chance = 1 - Math.pow(1 - chance, fortuneLevel + 1);
        }
        return chance;
    }
}
