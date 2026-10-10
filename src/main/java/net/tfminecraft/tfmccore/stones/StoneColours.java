package net.tfminecraft.tfmccore.stones;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

// ====================================
// The namestone colour step: the clickable palette and parsing of the
// colour a player picks (clicked, or typed as a name or hex).
// ====================================
public final class StoneColours {

    private static final Pattern HEX = Pattern.compile("#?([0-9a-fA-F]{6})");

    // Legacy order: a colour's index is its &0-&f code.
    private static final List<String> NAMES = List.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white");

    private StoneColours() {}

    // '&' colour code for a colour name ("gold", "dark blue") or hex ("#ff8800"),
    // "" for none, or null when the input is not a colour.
    public static String code(String input) {
        String key = input.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (key.equals("none") || key.equals("default")) return "";
        var hex = HEX.matcher(key);
        if (hex.matches()) return "&#" + hex.group(1);
        int index = NAMES.indexOf(key);
        return index < 0 ? null : "&" + Integer.toHexString(index);
    }

    // One line of [Colour] buttons, each previewed in its own colour, plus [None].
    public static Component palette() {
        TextComponent.Builder row = Component.text();
        for (String key : NAMES) {
            row.append(button(label(key), NamedTextColor.NAMES.value(key), key)).append(Component.space());
        }
        return row.append(button("None", NamedTextColor.WHITE, "none")).build();
    }

    private static Component button(String label, NamedTextColor colour, String key) {
        return Component.text("[" + label + "]", colour)
                .clickEvent(ClickEvent.runCommand("/tcore stones colour " + key))
                .hoverEvent(HoverEvent.showText(Component.text("Use " + label, colour)));
    }

    // dark_blue -> Dark Blue
    private static String label(String key) {
        StringBuilder out = new StringBuilder();
        for (String word : key.split("_")) {
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }
}
