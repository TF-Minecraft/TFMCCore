package net.tfminecraft.tfmccore.tfmc;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.tfminecraft.tfmccore.util.TextUtil;

/**
 * The /tfmc player command. Each subcommand is only offered to players who hold its permission,
 * and the client is sent the same tree, so suggestions and execution always agree.
 */
public final class TfmcCommand {

    public static final String NAME = "tfmc";
    public static final String BOOSTER_COOLDOWN = "booster";

    private record ParrotFlight(TfmcActions.FlightState previous, Runnable cancelExpiry) {}

    private static final Logger LOGGER = Logger.getLogger("TFMCCore");
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TfmcConfig config;
    private final TfmcCooldowns cooldowns;
    private final TfmcActions actions;
    private final Clock clock;
    private final RandomGenerator random;
    private final Executor mainThread;
    private final Map<UUID, ParrotFlight> parrots = new ConcurrentHashMap<>();

    /** {@code mainThread} runs LuckPerms completions, which arrive on LuckPerms worker threads. */
    public TfmcCommand(TfmcConfig config, TfmcCooldowns cooldowns, TfmcActions actions, Clock clock,
            RandomGenerator random, Executor mainThread) {
        this.mainThread = mainThread;
        this.config = config;
        this.cooldowns = cooldowns;
        this.actions = actions;
        this.clock = clock;
        this.random = random;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(NAME);

        root.then(Commands.literal("tips")
                .then(Commands.literal("enable").executes(player(p -> tips(p, false))))
                .then(Commands.literal("disable").executes(player(p -> tips(p, true)))));

        root.then(Commands.literal("pack").executes(player(this::pack))
                .then(Commands.literal("auto").executes(player(p -> autoPack(p, true))))
                .then(Commands.literal("manual").executes(player(p -> autoPack(p, false)))));

        root.then(Commands.literal("booster").executes(player(this::booster)));
        root.then(Commands.literal("drinks").executes(player(this::drinks)));
        root.then(Commands.literal("patreon").executes(player(p -> send(p, "patreon.message"))));
        root.then(Commands.literal("statues").executes(player(p -> runConsole(p, config.lines("statues.commands")))));
        root.then(Commands.literal("patterns").executes(player(this::patterns)));
        root.then(Commands.literal("masks").executes(player(this::masks)));
        root.then(Commands.literal("map").executes(player(this::map)));
        root.then(Commands.literal("date").executes(player(this::date)));

        root.then(Commands.literal("ban")
                .requires(permission(() -> config.string("ban.permission", "tfmc.helper")))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(onlinePlayers())
                        .executes(context -> ban(context, ""))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(context -> ban(context, StringArgumentType.getString(context, "reason"))))));

        // The track names shape the tree, so they are fixed until restart; their permissions
        // are read on every check so /tcore reload tfmc applies them
        for (String name : config.tracks().keySet()) {
            Predicate<CommandSourceStack> promote = permission(() -> trackPermission(name, true));
            Predicate<CommandSourceStack> demote = permission(() -> trackPermission(name, false));
            root.then(Commands.literal(name)
                    .requires(promote.or(demote))
                    .then(Commands.literal("promote").requires(promote)
                            .executes(player(p -> step(p, name, true))))
                    .then(Commands.literal("demote").requires(demote)
                            .executes(player(p -> step(p, name, false)))));
        }

        // Content still being built on dev; each section stays hidden until tfmc.yml enables it
        root.then(Commands.literal("tutorial").requires(enabled("tutorials"))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            String typed = builder.getRemainingLowerCase();
                            config.keys("tutorials.list").stream()
                                    .filter(tutorial -> tutorial.toLowerCase(Locale.ROOT).startsWith(typed))
                                    .forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .then(Commands.literal("clear").executes(context -> {
                            String name = StringArgumentType.getString(context, "name");
                            return player(p -> clearTutorial(p, name)).run(context);
                        }))));

        root.then(Commands.literal("parrot")
                .requires(enabled("parrot").and(anyPermission("parrot.permissions")))
                .executes(player(this::parrot)));
        root.then(Commands.literal("unparrot").requires(enabled("parrot"))
                .executes(player(p -> {
                    if (!endParrot(p, true)) {
                        send(p, "parrot.not-a-parrot");
                    }
                })));

        root.then(Commands.literal("worldboss").requires(enabled("worldboss"))
                .then(Commands.literal("info").executes(player(p -> send(p, "worldboss.info")))));

        root.then(Commands.literal("poster")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            String typed = builder.getRemainingLowerCase();
                            config.keys("posters").stream()
                                    .filter(poster -> poster.toLowerCase(Locale.ROOT).startsWith(typed))
                                    .forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            String name = StringArgumentType.getString(context, "name");
                            return player(p -> poster(p, name)).run(context);
                        })));

        return root.build();
    }

    private void tips(Player player, boolean disable) {
        String node = config.string("tips.permission");
        boolean disabled = player.hasPermission(node);
        if (disabled == disable) {
            send(player, disable ? "tips.already-disabled" : "tips.already-enabled");
            return;
        }
        update(player, actions.setPermission(player, node, disable), disable ? "tips.disabled" : "tips.enabled");
    }

    private void pack(Player player) {
        actions.sendResourcePack(player);
        send(player, "pack.loading");
    }

    private void autoPack(Player player, boolean enable) {
        String node = config.string("pack.auto-permission");
        if (player.hasPermission(node) == enable) {
            send(player, enable ? "pack.auto-already-enabled" : "pack.auto-already-disabled");
            return;
        }
        update(player, actions.setPermission(player, node, enable), enable ? "pack.auto-enabled" : "pack.auto-disabled");
    }

    private void booster(Player player) {
        Optional<String> tier = config.keys("booster.tiers").stream()
                .filter(key -> player.hasPermission(config.string("booster.tiers." + key + ".permission")))
                .findFirst();
        if (tier.isEmpty()) {
            send(player, "booster.denied");
            return;
        }
        long left = cooldowns.remaining(BOOSTER_COOLDOWN, player.getUniqueId(), config.seconds("booster.cooldown-seconds") * 1000L);
        if (left > 0) {
            send(player, "booster.cooldown", Placeholder.unparsed("time", TfmcCooldowns.format(left)));
            return;
        }
        cooldowns.markUsed(BOOSTER_COOLDOWN, player.getUniqueId());
        runConsole(player, config.lines("booster.commands"));
        for (String line : config.lines("booster.tiers." + tier.get() + ".broadcast")) {
            actions.broadcast(MINI.deserialize(line, Placeholder.unparsed("player", player.getName())));
        }
    }

    private void drinks(Player player) {
        boolean donor = config.lines("drinks.permissions").stream().anyMatch(player::hasPermission);
        if (!donor) {
            send(player, "drinks.denied");
            return;
        }
        actions.playerCommand(player, config.string("drinks.player-command"));
    }

    private void patterns(Player player) {
        if (onCooldown(player, "patterns")) {
            return;
        }
        List<Material> items = new ArrayList<>();
        for (String name : config.lines("patterns.items")) {
            Material item = Material.matchMaterial(name);
            if (item == null) {
                LOGGER.warning("tfmc.yml patterns: unknown item " + name);
            } else {
                items.add(item);
            }
        }
        actions.giveItems(player, items);
    }

    private void masks(Player player) {
        List<String> masks = config.lines("masks.masks");
        if (masks.isEmpty() || onCooldown(player, "masks")) {
            return;
        }
        String mask = masks.get(random.nextInt(masks.size()));
        actions.consoleCommand(config.string("masks.command").replace("<mask>", mask).replace("<player>", player.getName()));
    }

    private void map(Player player) {
        if (!onCooldown(player, "map")) {
            send(player, "map.message");
        }
    }

    private void date(Player player) {
        LocalDate today = LocalDate.now(clock);
        send(player, "date.weekday", Placeholder.unparsed("name", config.string("date.weekdays." + today.getDayOfWeek().name())));
        send(player, "date.month", Placeholder.unparsed("name", config.string("date.months." + today.getMonth().name())));
    }

    private int ban(CommandContext<CommandSourceStack> context, String rawReason) {
        CommandSender sender = context.getSource().getSender();
        String target = StringArgumentType.getString(context, "player");
        if (!PLAYER_NAME.matcher(target).matches()) {
            sender.sendMessage(MINI.deserialize("<red>That is not a valid player name."));
            return 0;
        }
        String reason = TextUtil.sanitize(rawReason);
        for (String command : config.lines("ban.commands")) {
            actions.consoleCommand(command.replace("<target>", target).replace("<reason>", reason)
                    .replace("<player>", sender.getName()).trim());
        }
        for (String line : config.lines("ban.message")) {
            sender.sendMessage(MINI.deserialize(line, Placeholder.unparsed("target", target)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private void step(Player player, String track, boolean promote) {
        actions.stepTrack(player, track, promote).whenCompleteAsync((group, error) -> {
            if (error != null) {
                LOGGER.warning("Failed to " + (promote ? "promote " : "demote ") + player.getName() + " on " + track + ": " + error.getMessage());
            }
            if (error != null || group.isEmpty()) {
                send(player, "track-messages.failed");
                return;
            }
            send(player, promote ? "track-messages.promoted" : "track-messages.demoted", Placeholder.unparsed("group", group.get()));
        }, mainThread);
    }

    private void clearTutorial(Player player, String name) {
        Optional<String> tutorial = config.keys("tutorials.list").stream()
                .filter(key -> key.equalsIgnoreCase(name))
                .findFirst();
        if (tutorial.isEmpty()) {
            send(player, "tutorials.unknown", Placeholder.unparsed("name", name));
            return;
        }
        String base = "tutorials.list." + tutorial.get();
        if (onCooldown(player, "tutorial." + tutorial.get(), config.seconds("tutorials.cooldown-seconds"), "messages.cooldown")) {
            return;
        }
        runConsole(player, config.lines(base + ".clear"));
        send(player, "tutorials.cleared", Placeholder.unparsed("name", config.string(base + ".name", tutorial.get())));
    }

    private void parrot(Player player) {
        if (parrots.containsKey(player.getUniqueId())) {
            send(player, "parrot.already");
            return;
        }
        if (onCooldown(player, "parrot", config.seconds("parrot.cooldown-seconds"), "parrot.cooldown")) {
            return;
        }
        long seconds = Math.max(1L, config.seconds("parrot.duration-seconds"));
        actions.consoleCommand(config.string("parrot.disguise")
                .replace("<player>", player.getName()).replace("<seconds>", Long.toString(seconds)));
        TfmcActions.FlightState previous = actions.startFlight(player, (float) config.decimal("parrot.fly-speed", 0.01));
        Runnable cancel = actions.later(seconds * 20L, () -> endParrot(player, false));
        parrots.put(player.getUniqueId(), new ParrotFlight(previous, cancel));
        send(player, "parrot.started");
    }

    /** Ends a parrot flight early or on expiry; false when the player is not a parrot. */
    private boolean endParrot(Player player, boolean undisguise) {
        ParrotFlight flight = parrots.remove(player.getUniqueId());
        if (flight == null) {
            return false;
        }
        flight.cancelExpiry().run();
        if (undisguise) {
            actions.consoleCommand(config.string("parrot.undisguise").replace("<player>", player.getName()));
        }
        actions.endFlight(player, flight.previous());
        send(player, "parrot.ended");
        return true;
    }

    /** Puts a leaving player's flight settings back so the temporary flight is not saved with them. */
    public void onQuit(Player player) {
        endParrot(player, true);
    }

    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            endParrot(player, true);
        }
    }

    private void poster(Player player, String name) {
        config.entries("posters").entrySet().stream()
                .filter(poster -> poster.getKey().equalsIgnoreCase(name))
                .findFirst()
                .ifPresentOrElse(
                        poster -> actions.playerCommand(player, poster.getValue()),
                        () -> player.sendMessage(MINI.deserialize("<red>There is no poster called <name>.", Placeholder.unparsed("name", name))));
    }

    private void update(Player player, CompletableFuture<Void> change, String successPath) {
        change.whenCompleteAsync((done, error) -> {
            if (error != null) {
                LOGGER.warning("Failed to update permissions for " + player.getName() + ": " + error.getMessage());
                send(player, "messages.failed");
            } else {
                send(player, successPath);
            }
        }, mainThread);
    }

    private void runConsole(Player player, List<String> commands) {
        for (String command : commands) {
            actions.consoleCommand(command.replace("<player>", player.getName()));
        }
    }

    private boolean onCooldown(Player player, String subcommand) {
        return onCooldown(player, subcommand, config.seconds(subcommand + ".cooldown-seconds"), "messages.cooldown");
    }

    private boolean onCooldown(Player player, String key, long seconds, String messagePath) {
        long left = cooldowns.remaining(key, player.getUniqueId(), seconds * 1000L);
        if (left > 0) {
            send(player, messagePath, Placeholder.unparsed("time", TfmcCooldowns.format(left)));
            return true;
        }
        cooldowns.markUsed(key, player.getUniqueId());
        return false;
    }

    private void send(CommandSender sender, String path, TagResolver... placeholders) {
        for (String line : config.lines(path)) {
            sender.sendMessage(MINI.deserialize(line, placeholders));
        }
    }

    private Command<CommandSourceStack> player(Consumer<Player> handler) {
        return context -> {
            if (!(context.getSource().getSender() instanceof Player player)) {
                send(context.getSource().getSender(), "messages.players-only");
                return 0;
            }
            handler.accept(player);
            return Command.SINGLE_SUCCESS;
        };
    }

    private String trackPermission(String track, boolean promote) {
        TfmcConfig.TrackSteps steps = config.tracks().get(track);
        if (steps == null) {
            return null;
        }
        return promote ? steps.promotePermission() : steps.demotePermission();
    }

    private Predicate<CommandSourceStack> enabled(String section) {
        return source -> config.enabled(section);
    }

    /** Any of the listed permissions; an empty list means everyone. */
    private Predicate<CommandSourceStack> anyPermission(String path) {
        return source -> {
            List<String> nodes = config.lines(path);
            return nodes.isEmpty() || nodes.stream().anyMatch(source.getSender()::hasPermission);
        };
    }

    // Restricted nodes fail closed: a missing or blank permission hides the command from everyone
    private static Predicate<CommandSourceStack> permission(Supplier<String> node) {
        return source -> {
            String permission = node.get();
            return permission != null && !permission.isBlank() && source.getSender().hasPermission(permission);
        };
    }

    private static SuggestionProvider<CommandSourceStack> onlinePlayers() {
        return (context, builder) -> {
            String typed = builder.getRemainingLowerCase();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(typed)) {
                    builder.suggest(online.getName());
                }
            }
            return builder.buildFuture();
        };
    }
}
