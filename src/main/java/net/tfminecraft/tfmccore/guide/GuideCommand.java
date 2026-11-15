package net.tfminecraft.tfmccore.guide;

import java.util.Locale;

import org.bukkit.command.CommandSender;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * /guide lists the Gameplay Guide topics as clickable links, and /guide &lt;topic&gt; links one
 * page or section. It only posts links: the guide itself lives on the website.
 */
public final class GuideCommand {

    public static final String NAME = "guide";
    public static final String PERMISSION = "tfmccore.guide";
    public static final String DESCRIPTION = "Links to the Gameplay Guide on the website";

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final GuideConfig config;

    public GuideCommand(GuideConfig config) {
        this.config = config;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal(NAME)
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(context -> {
                    list(context.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("topic", StringArgumentType.word())
                        // Read on every request, so topics added by /tcore reload guide are offered at once
                        .suggests(topics())
                        .executes(context -> show(context.getSource().getSender(),
                                StringArgumentType.getString(context, "topic"))))
                .build();
    }

    /** The header, then one line per topic: the title opens the page, the command fills in /guide &lt;id&gt;. */
    void list(CommandSender sender) {
        if (config.topics().isEmpty()) {
            send(sender, "empty", TagResolver.empty());
            return;
        }
        send(sender, "header", TagResolver.empty());
        for (GuideConfig.Topic topic : config.topics()) {
            sender.sendMessage(line(topic, "topic", "topic-hover"));
        }
    }

    /** One line linking the topic; an unknown topic gets a short error and the list. */
    int show(CommandSender sender, String typed) {
        var topic = config.topic(typed);
        if (topic.isEmpty()) {
            send(sender, "unknown", Placeholder.unparsed("topic", typed));
            list(sender);
            return 0;
        }
        sender.sendMessage(line(topic.get(), "link", "link-hover"));
        return Command.SINGLE_SUCCESS;
    }

    private Component line(GuideConfig.Topic topic, String format, String hover) {
        TagResolver fields = TagResolver.resolver(
                Placeholder.unparsed("id", topic.id()),
                Placeholder.unparsed("title", topic.title()),
                Placeholder.unparsed("description", topic.description()),
                Placeholder.unparsed("url", topic.url()));
        TagResolver clicks = TagResolver.resolver(
                TagResolver.resolver("open", Tag.styling(
                        ClickEvent.openUrl(topic.url()),
                        HoverEvent.showText(MINI.deserialize(config.line(hover), fields)))),
                TagResolver.resolver("suggest", Tag.styling(
                        ClickEvent.suggestCommand("/" + NAME + " " + topic.id()),
                        HoverEvent.showText(MINI.deserialize(config.line("suggest-hover"), fields)))));
        return MINI.deserialize(config.line(format), fields, clicks);
    }

    private void send(CommandSender sender, String key, TagResolver placeholders) {
        for (String line : config.lines(key)) {
            sender.sendMessage(MINI.deserialize(line, placeholders));
        }
    }

    private SuggestionProvider<CommandSourceStack> topics() {
        return (context, builder) -> {
            String typed = builder.getRemainingLowerCase();
            for (GuideConfig.Topic topic : config.topics()) {
                if (topic.id().startsWith(typed)) {
                    builder.suggest(topic.id());
                }
            }
            return builder.buildFuture();
        };
    }
}
