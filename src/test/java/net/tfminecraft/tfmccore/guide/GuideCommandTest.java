package net.tfminecraft.tfmccore.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

class GuideCommandTest {

    private static final String WIKI = "https://www.tfminecraft.net/wiki";

    private final List<Component> messages = new ArrayList<>();
    private GuideConfig config;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private Player player;
    private boolean allowed = true;

    @BeforeEach
    void setUp() throws Exception {
        config = GuideConfigTest.bundled();
        dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new GuideCommand(config).build());
        player = mock(Player.class);
        when(player.hasPermission(anyString())).thenAnswer(call -> allowed && call.getArgument(0).equals("tfmccore.guide"));
        capture(player);
    }

    @Test
    void guideListsEveryTopicAsALinkWithACommandToTypeIt() throws Exception {
        assertEquals(1, run("guide"));

        assertEquals(1 + config.topics().size(), messages.size());
        assertEquals("Gameplay Guide | Click a topic to open its page on the website.", plain(messages.get(0)));
        Component factions = messages.get(1 + ids().indexOf("factions"));
        assertEquals(" » Factions [/guide factions]", plain(factions));
        assertEquals(List.of(ClickEvent.openUrl(WIKI + "/factions"), ClickEvent.suggestCommand("/guide factions")),
                clicks(factions));
        assertEquals(List.of("Join or found a faction, claim provinces, earn prestige and titles, and run its government.",
                "Click to type /guide factions"), hovers(factions));
        Component government = messages.get(1 + ids().indexOf("government"));
        assertEquals(ClickEvent.openUrl(WIKI + "/factions#government"), clicks(government).get(0));
    }

    @Test
    void guideTopicLinksThatPageWithItsDescription() throws Exception {
        assertEquals(1, run("guide Vehicles"));

        assertEquals(1, messages.size());
        assertEquals("Gameplay Guide: Vehicles & Construction Build, crew, fuel and repair vehicles, with 3D previews and complete blueprints.",
                plain(messages.get(0)));
        assertEquals(List.of(ClickEvent.openUrl(WIKI + "/vehicles")), clicks(messages.get(0)));
        assertEquals(List.of("Open " + WIKI + "/vehicles"), hovers(messages.get(0)));
    }

    @Test
    void anUnknownTopicGetsAShortErrorAndTheList() throws Exception {
        assertEquals(0, run("guide dragons"));

        assertEquals("There is no guide topic called dragons.", plain(messages.get(0)));
        assertEquals(2 + config.topics().size(), messages.size());
        assertEquals(" » Overview [/guide overview]", plain(messages.get(2)));
    }

    @Test
    void topicsAreTabCompletedFromTheLiveConfig() throws Exception {
        assertEquals(List.of("factions", "farming", "fishing"), suggestions("guide f"));
        assertEquals(List.of("factions", "farming", "fishing"), suggestions("guide F"));
        assertEquals(ids().stream().sorted().toList(), suggestions("guide "), "Brigadier sorts suggestions");

        config.loadFromString("topics:\n  forge:\n    path: /advanced-crafting\n");
        assertEquals(List.of("forge"), suggestions("guide f"), "A reload changes the suggestions without a tree resend");
    }

    @Test
    void theCommandNeedsTheGuidePermission() {
        allowed = false;

        assertFalse(dispatcher.getRoot().getChild("guide").canUse(source(player)));
        assertThrows(CommandSyntaxException.class, () -> run("guide"));
        assertTrue(messages.isEmpty());
    }

    @Test
    void withNoTopicsTheListSaysSoAndTheConsoleCanUseIt() throws Exception {
        config.loadFromString("topics: {}\n");
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission("tfmccore.guide")).thenReturn(true);
        capture(console);

        dispatcher.execute("guide", source(console));
        dispatcher.execute("guide cooking", source(console));

        assertEquals(List.of("No guide topics are set up yet.", "There is no guide topic called cooking.",
                "No guide topics are set up yet."), messages.stream().map(GuideCommandTest::plain).toList());
    }

    private int run(String command) throws CommandSyntaxException {
        messages.clear();
        return dispatcher.execute(command, source(player));
    }

    private List<String> suggestions(String input) {
        return dispatcher.getCompletionSuggestions(dispatcher.parse(input, source(player)))
                .join().getList().stream().map(Suggestion::getText).toList();
    }

    private List<String> ids() {
        return config.topics().stream().map(GuideConfig.Topic::id).toList();
    }

    private void capture(CommandSender sender) {
        doAnswer(call -> messages.add(call.getArgument(0))).when(sender).sendMessage(any(Component.class));
    }

    private static CommandSourceStack source(CommandSender sender) {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        return source;
    }

    /** Every distinct click event in the line, in order. */
    private static List<ClickEvent> clicks(Component component) {
        List<ClickEvent> found = new ArrayList<>();
        walk(component, part -> {
            ClickEvent click = part.clickEvent();
            if (click != null && !found.contains(click)) {
                found.add(click);
            }
        });
        return found;
    }

    /** The plain text of every distinct hover in the line, in order. */
    private static List<String> hovers(Component component) {
        List<String> found = new ArrayList<>();
        walk(component, part -> {
            HoverEvent<?> hover = part.hoverEvent();
            if (hover != null && hover.value() instanceof Component text && !found.contains(plain(text))) {
                found.add(plain(text));
            }
        });
        return found;
    }

    private static void walk(Component component, java.util.function.Consumer<Component> visitor) {
        visitor.accept(component);
        component.children().forEach(child -> walk(child, visitor));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
