package net.tfminecraft.tfmccore.indicators;

import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the damage number in one MythicLib indicator line, such as {@code &c🗡&4✘ &f6.4}.
 *
 * <p>The number is the last one in the line that is not part of a colour code ({@code &4},
 * {@code §4}, {@code &#ff0000}). MythicLib's {@code format} puts {@code {value}} after the icons,
 * and the icons have no digits. With {@code custom_font} on, MythicLib draws the digits as glyphs,
 * so no number is found and the line is left as it is.
 */
final class IndicatorNumbers {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");

    private IndicatorNumbers() {
    }

    /** The number shown in {@code line}, or null when it has none. */
    static Double value(String line) {
        MatchResult number = last(line);
        return number == null ? null : Double.parseDouble(number.group().replace(',', '.'));
    }

    /** {@code line} with its number replaced by {@code replacement}, or null when it has none. */
    static String replace(String line, String replacement) {
        MatchResult number = last(line);
        if (number == null) return null;
        return line.substring(0, number.start()) + replacement + line.substring(number.end());
    }

    private static MatchResult last(String line) {
        Matcher matcher = NUMBER.matcher(line);
        MatchResult found = null;
        while (matcher.find()) {
            if (!colourCode(line, matcher.start())) found = matcher.toMatchResult();
        }
        return found;
    }

    /** True for a digit right after {@code &} or {@code §}, or inside a {@code #rrggbb} colour. */
    private static boolean colourCode(String line, int start) {
        for (int i = start - 1; i >= 0 && i >= start - 6; i--) {
            char c = line.charAt(i);
            if (c == '#') return true;
            if (Character.digit(c, 16) < 0) return i == start - 1 && (c == '&' || c == '§');
        }
        return false;
    }
}
