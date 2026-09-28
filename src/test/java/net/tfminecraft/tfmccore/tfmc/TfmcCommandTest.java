package net.tfminecraft.tfmccore.tfmc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.random.RandomGenerator;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

class TfmcCommandTest {

    // Monday 28 September 2026
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

    private final List<String> console = new ArrayList<>();
    private final List<String> playerCommands = new ArrayList<>();
    private final List<String> broadcasts = new ArrayList<>();
    private final List<String> permissionChanges = new ArrayList<>();
    private final List<String> trackSteps = new ArrayList<>();
    private final List<Material> given = new ArrayList<>();
    private final List<Runnable> mainThread = new ArrayList<>();
    private int packsSent;
    private long clock = NOW.toEpochMilli();

    private CommandDispatcher<CommandSourceStack> dispatcher;
    private TfmcConfig config;

    @BeforeEach
    void setUp() throws Exception {
        config = new TfmcConfig();
        config.loadFromString(Files.readString(Path.of("src/main/resources/tfmc.yml")));
        TfmcCooldowns cooldowns = new TfmcCooldowns(null, Set.of(), () -> clock);
        RandomGenerator firstMask = new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0L;
            }

            @Override
            public int nextInt(int bound) {
                return 0;
            }
        };
        TfmcCommand command = new TfmcCommand(config, cooldowns, new RecordingActions(),
                Clock.fixed(NOW, ZoneOffset.UTC), firstMask, mainThread::add);
        dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(command.build());
    }

    @Test
    void playersSeeOnlyPublicSubcommands() {
        assertEquals(List.of("tips", "pack", "booster", "drinks", "patreon", "statues", "patterns", "masks", "map", "date", "poster"),
                visible(player("Steve")));
    }

    @Test
    void helperTrackMembersSeeTheStepTheyCanTake() {
        TestPlayer offDuty = player("Helper", "helper.promote");
        assertTrue(visible(offDuty).contains("helper"));
        assertFalse(visible(offDuty).contains("helper+"));
        assertEquals(List.of("promote"), visible(offDuty, "helper"));

        TestPlayer onDuty = player("Helper", "helper.promote", "helper.demote", "tfmc.helper");
        assertEquals(List.of("promote", "demote"), visible(onDuty, "helper"));
        assertTrue(visible(onDuty).contains("ban"));

        assertEquals(List.of("demote"), visible(player("Senior", "helper+.demote"), "helper+"));
    }

    @Test
    void reloadAppliesPermissionChangesAndBlankPermissionsHideCommands() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/tfmc.yml"));
        config.loadFromString(yaml
                .replace("  permission: tfmc.helper", "  permission: tfmc.moderator")
                .replace("promote-permission: helper.promote", "promote-permission: \"\""));

        assertFalse(visible(player("Helper", "tfmc.helper")).contains("ban"));
        assertTrue(visible(player("Mod", "tfmc.moderator")).contains("ban"));
        assertEquals(List.of("demote"), visible(player("Helper", "helper.promote", "helper.demote"), "helper"));
        assertFalse(visible(player("Anyone")).contains("helper"));
    }

    @Test
    void luckPermsResultsAreReportedOnTheMainThread() throws Exception {
        TestPlayer helper = player("Helper", "helper.demote");
        helper.messages.clear();
        dispatcher.execute("tfmc helper demote", source(helper.player));

        assertTrue(helper.messages.isEmpty());
        runMainThread();
        assertEquals(List.of("You are now helper_player."), helper.messages);
    }

    @Test
    void trackStepsGoThroughLuckPerms() throws Exception {
        TestPlayer helper = player("Helper", "helper.demote");

        run(helper, "tfmc helper demote");

        assertEquals(List.of("Helper helper demote"), trackSteps);
        assertEquals(List.of("You are now helper_player."), helper.messages);
        assertThrows(CommandSyntaxException.class, () -> run(helper, "tfmc helper promote"));
    }

    @Test
    void tipsToggleTheLuckPermsNodeOnlyWhenItChanges() throws Exception {
        TestPlayer steve = player("Steve");
        run(steve, "tfmc tips disable");
        assertEquals(List.of("Steve tips.off true"), permissionChanges);
        assertEquals("Disabled Chat Tips!", steve.messages.get(0));

        TestPlayer quiet = player("Quiet", "tips.off");
        run(quiet, "tfmc tips disable");
        assertEquals(1, permissionChanges.size());
        assertEquals("Chat Tips are already disabled.", quiet.messages.get(0));

        run(quiet, "tfmc tips enable");
        assertEquals("Quiet tips.off false", permissionChanges.get(1));
    }

    @Test
    void packSendsTheResourcePackAndTogglesAutoApply() throws Exception {
        TestPlayer steve = player("Steve");
        run(steve, "tfmc pack");
        assertEquals(1, packsSent);
        assertEquals("Loading Resource Pack...", steve.messages.get(0));

        run(steve, "tfmc pack auto");
        assertEquals(List.of("Steve tfmcresourcepack.enable true"), permissionChanges);
    }

    @Test
    void boosterIsForDonorsAndHasAThreeDayCooldown() throws Exception {
        TestPlayer steve = player("Steve");
        run(steve, "tfmc booster");
        assertTrue(console.isEmpty());
        assertTrue(steve.messages.get(0).startsWith("This command only works for Ascended and Legacy Donators"));

        TestPlayer donor = player("Donor", "group.ascended");
        run(donor, "tfmc booster");
        assertEquals(List.of(
                "mmocore booster create crafter 1 3600 TFMC",
                "mmocore booster create forager 1 3600 TFMC",
                "mmocore booster create herborist 1 3600 TFMC"), console);
        assertEquals("Thank you, Donor, for supporting TFMC!", broadcasts.get(1));

        clock += 60_000L;
        run(donor, "tfmc booster");
        assertEquals(3, console.size());
        assertEquals("You need to wait 2d 23h 59m before boosting again.", donor.messages.get(0));

        clock += 259_200_000L;
        run(donor, "tfmc booster");
        assertEquals(6, console.size());
    }

    @Test
    void drinksOpenDrinkBuilderForDonors() throws Exception {
        run(player("Noble", "group.noble"), "tfmc drinks");
        assertEquals(List.of("drinkbuilder"), playerCommands);

        TestPlayer steve = player("Steve");
        run(steve, "tfmc drinks");
        assertEquals(1, playerCommands.size());
        assertTrue(steve.messages.get(0).startsWith("This command only works for Noble, Gilded, Ascended and Legacy Donators"));
    }

    @Test
    void itemCommandsGiveTheirItemsWithACooldown() throws Exception {
        TestPlayer steve = player("Steve");
        run(steve, "tfmc masks");
        assertEquals(List.of("mi give MASKS GHOST_MASK Steve 1 0 100 1 s"), console);

        run(steve, "tfmc masks");
        assertEquals(1, console.size());
        assertEquals("You need to wait 10s before using this again.", steve.messages.get(0));

        run(steve, "tfmc patterns");
        assertEquals(10, given.size());
        assertEquals(Material.FLOWER_BANNER_PATTERN, given.get(0));

        run(steve, "tfmc statues");
        assertEquals("loot give Steve loot armor_statues:book", console.get(1));
    }

    @Test
    void dateUsesTheRoleplayCalendar() throws Exception {
        TestPlayer steve = player("Steve");
        run(steve, "tfmc date");
        assertEquals(List.of(
                "The stars reveal this day as Morindas",
                "The turning of the seasons brings the month of Mit Set"), steve.messages);
    }

    @Test
    void helpersTempbanWithAnOptionalReason() throws Exception {
        TestPlayer helper = player("Helper", "tfmc.helper");
        run(helper, "tfmc ban Griefer broke the rules");
        run(helper, "tfmc ban Griefer");
        assertEquals(List.of("tempban Griefer 8h broke the rules", "tempban Griefer 8h"), console);
        assertEquals("Tempbanned Griefer for 8 hours.", helper.messages.get(0));

        assertThrows(CommandSyntaxException.class, () -> run(player("Steve"), "tfmc ban Griefer"));
    }

    @Test
    void postersRunTheImagesCommandAsThePlayer() throws Exception {
        run(player("Steve"), "tfmc poster zerratoris3");
        assertEquals(List.of("images create https://cdn.imgchest.com/files/739cxwpeqp7.jpg 42.74553571428"), playerCommands);
    }

    @Test
    void consoleIsToldTheCommandIsForPlayers() throws Exception {
        ConsoleCommandSender sender = mock(ConsoleCommandSender.class);
        List<String> messages = capture(sender);

        dispatcher.execute("tfmc date", source(sender));

        assertEquals(List.of("Only players can use this command."), messages);
    }

    private List<String> visible(TestPlayer player, String... path) {
        CommandNode<CommandSourceStack> node = dispatcher.getRoot().getChild("tfmc");
        for (String step : path) {
            node = node.getChild(step);
        }
        CommandSourceStack source = source(player.player);
        return node.getChildren().stream().filter(child -> child.canUse(source)).map(CommandNode::getName).toList();
    }

    private void run(TestPlayer player, String command) throws CommandSyntaxException {
        player.messages.clear();
        dispatcher.execute(command, source(player.player));
        runMainThread();
    }

    private void runMainThread() {
        while (!mainThread.isEmpty()) {
            mainThread.remove(0).run();
        }
    }

    private static CommandSourceStack source(CommandSender sender) {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        return source;
    }

    private static TestPlayer player(String name, String... permissions) {
        Player player = mock(Player.class);
        Set<String> granted = Set.of(permissions);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.hasPermission(anyString())).thenAnswer(call -> granted.contains(call.getArgument(0, String.class)));
        return new TestPlayer(player, capture(player));
    }

    private static List<String> capture(CommandSender sender) {
        List<String> messages = new ArrayList<>();
        doAnswer(call -> messages.add(plain(call.getArgument(0))))
                .when(sender).sendMessage(any(Component.class));
        return messages;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private record TestPlayer(Player player, List<String> messages) {}

    private final class RecordingActions implements TfmcActions {
        @Override
        public void consoleCommand(String command) {
            console.add(command);
        }

        @Override
        public void playerCommand(Player player, String command) {
            playerCommands.add(command);
        }

        @Override
        public void sendResourcePack(Player player) {
            packsSent++;
        }

        @Override
        public void broadcast(Component message) {
            broadcasts.add(plain(message));
        }

        @Override
        public void giveItems(Player player, List<Material> items) {
            given.addAll(items);
        }

        @Override
        public CompletableFuture<Void> setPermission(Player player, String permission, boolean granted) {
            permissionChanges.add(player.getName() + " " + permission + " " + granted);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Optional<String>> stepTrack(Player player, String track, boolean promote) {
            trackSteps.add(player.getName() + " " + track + " " + (promote ? "promote" : "demote"));
            return CompletableFuture.completedFuture(Optional.of(promote ? "helper" : "helper_player"));
        }
    }
}
