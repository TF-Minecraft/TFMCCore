package net.tfminecraft.tfmccore.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.ChatColor;

/**
 * Shared colour formatting helpers. Pure string manipulation - no running server required.
 */
public final class TextUtil {

    private static final char COLOR_CHAR = '\u00A7';

    // Matches &#RRGGBB hex colours; shorter or non-hex sequences are left untouched.
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([0-9a-fA-F]{6})");

    private TextUtil() {}

    /**
     * Expands {@code &#RRGGBB} hex codes into the Bukkit legacy form and then translates
     * ordinary {@code &a} style colour codes.
     */
    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public static String color(String message) {
        if (message == null || message.isEmpty()) return message;
        Matcher matcher = HEX_PATTERN.matcher(message);
        StringBuilder builder = new StringBuilder(message.length());
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder expanded = new StringBuilder(14).append(COLOR_CHAR).append('x');
            for (int i = 0; i < hex.length(); i++) {
                expanded.append(COLOR_CHAR).append(hex.charAt(i));
            }
            matcher.appendReplacement(builder, Matcher.quoteReplacement(expanded.toString()));
        }
        matcher.appendTail(builder);
        return ChatColor.translateAlternateColorCodes('&', builder.toString());
    }

    /**
     * Strips control/format characters (line breaks, tabs, zero-width, bidi overrides, private-use
     * characters, line/paragraph separators and the section sign) that chat input can carry, plus
     * surrounding whitespace.
     */
    public static String sanitize(String raw) {
        return raw == null ? "" : raw.replaceAll("[\\p{Cc}\\p{Cf}\\p{Co}\\p{Zl}\\p{Zp}\\u00A7]", "").trim();
    }
}
