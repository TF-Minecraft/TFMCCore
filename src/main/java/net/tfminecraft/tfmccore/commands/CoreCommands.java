package net.tfminecraft.tfmccore.commands;

import java.util.Arrays;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tfmccore.TFMCCore;
import net.tfminecraft.tfmccore.stones.LorestoneConfig;
import net.tfminecraft.tfmccore.stones.StoneItems;

public class CoreCommands implements CommandExecutor {
    public String cmd1 = "tcore";

    private static final String RELOAD_PERMISSION = "tfmccore.reload";
    private static final String ADMIN_PERMISSION = "tfmccore.admin";

    private final StatsCommand statsCommand = new StatsCommand();

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase(cmd1)) {
            return false;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("stats")) {
            return statsCommand.handle(sender, Arrays.copyOfRange(args, 1, args.length));
        }

        if (args[0].equalsIgnoreCase("stones")) {
            return handleStones(sender, args);
        }

        if (args[0].equalsIgnoreCase("reload")) {
            return handleReload(sender, args);
        }

        sendUsage(sender);
        return true;
    }

    private boolean handleReload(CommandSender sender, String[] args) {
        if (!canReload(sender)) {
            sender.sendMessage("You do not have permission to reload TFMCCore.");
            return true;
        }

        String target = args.length >= 2 ? args[1].toLowerCase() : "all";
        boolean ok;
        String label;
        switch (target) {
            case "all" -> {
                ok = TFMCCore.getInstance().reloadAll();
                label = "all configs";
            }
            case "config" -> {
                ok = TFMCCore.getInstance().reloadConfigFile();
                label = "config";
            }
            case "drops" -> {
                ok = TFMCCore.getInstance().reloadDrops();
                label = "drops";
            }
            case "stations" -> {
                ok = TFMCCore.getInstance().reloadStations();
                label = "stations";
            }
            case "stats" -> {
                ok = TFMCCore.getInstance().reloadStatsConfigs();
                label = "stats";
            }
            case "whistle" -> {
                ok = TFMCCore.getInstance().reloadWhistleConfig();
                label = "animal whistle config";
            }
            case "lorestones" -> {
                ok = TFMCCore.getInstance().reloadStonesConfig();
                label = "lorestones config";
            }
            default -> {
                sender.sendMessage("Usage: /tcore reload [all|config|drops|stations|stats|whistle|lorestones]");
                return true;
            }
        }

        if (ok) {
            sender.sendMessage("§a[TFMCCore] Reloaded " + label + ".");
        } else {
            sender.sendMessage("§c[TFMCCore] Reload failed for " + label + ". Check console.");
        }
        return true;
    }

    private boolean handleStones(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage("You do not have permission to use this command.");
            return true;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("give")) {
            sender.sendMessage("Usage: /tcore stones give <lorestone|namestone> [player] [amount]");
            return true;
        }
        return handleStonesGive(sender, args);
    }

    // /tcore stones give <lorestone|namestone> [player] [amount]
    private boolean handleStonesGive(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("Usage: /tcore stones give <lorestone|namestone> [player] [amount]");
            return true;
        }

        StoneItems.Kind kind;
        if (args[2].equalsIgnoreCase("lorestone")) {
            kind = StoneItems.Kind.LORE;
        } else if (args[2].equalsIgnoreCase("namestone")) {
            kind = StoneItems.Kind.NAME;
        } else {
            sender.sendMessage("Unknown stone: " + args[2]);
            return true;
        }

        Player target;
        if (args.length >= 4) {
            target = Bukkit.getPlayerExact(args[3]);
            if (target == null) {
                sender.sendMessage("Player not found: " + args[3]);
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage("Console must name a player: /tcore stones give <stone> <player> [amount]");
            return true;
        }

        int amount = 1;
        if (args.length >= 5) {
            try {
                amount = Integer.parseInt(args[4]);
            } catch (NumberFormatException ex) {
                sender.sendMessage("Invalid amount: " + args[4]);
                return true;
            }
            amount = Math.max(1, Math.min(64, amount));
        }

        StoneItems items = TFMCCore.getStoneItems();
        if (items == null) {
            sender.sendMessage("Lorestones are not initialised.");
            return true;
        }

        ItemStack stone = items.template(kind);
        if (stone == null) {
            sender.sendMessage("That stone is not configured or could not be resolved.");
            return true;
        }
        stone.setAmount(amount);
        items.giveOrDrop(target, stone);

        items.msg(sender, LorestoneConfig.gaveMessage,
                "%amount%", String.valueOf(amount),
                "%stone%", args[2].toLowerCase(),
                "%player%", target.getName());
        return true;
    }

    private static boolean canReload(CommandSender sender) {
        return sender.hasPermission(RELOAD_PERMISSION) || sender.hasPermission(ADMIN_PERMISSION);
    }

    private static void sendUsage(CommandSender sender) {
        sender.sendMessage("§e/tcore stats <category> [player]");
        if (canReload(sender)) {
            sender.sendMessage("§e/tcore reload [all|config|drops|stations|stats|whistle|lorestones]");
        }
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage("§e/tcore stones give <lorestone|namestone> [player] [amount]");
        }
    }
}
