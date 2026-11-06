package net.tfminecraft.tfmccore.indicators;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;
import java.util.function.DoubleUnaryOperator;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the damage numbers in one MythicLib indicator line, such as {@code &c🗡&4✘ &f6.4}.
 *
 * <p>A number is any run of digits that is not part of a colour code ({@code &4}, {@code §4},
 * {@code &#ff0000}). The icons have no digits. A line holds one number per indicator, or several
 * when MythicLib's {@code split_holograms} is off and it joins a hit's numbers into one line. With
 * {@code custom_font} on, MythicLib draws the digits as glyphs, so no number is found and the line
 * is left as it is.
 */
final class IndicatorNumbers {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");

    private IndicatorNumbers() {
    }

    /** How many numbers {@code line} shows. */
    static int count(String line) {
        return numbers(line).size();
    }

    /** {@code line} with every number replaced by {@code format(change(number))}. */
    static String rewrite(String line, DoubleUnaryOperator change, DoubleFunction<String> format) {
        StringBuilder out = new StringBuilder();
        int from = 0;
        for (MatchResult number : numbers(line)) {
            double value = Double.parseDouble(number.group().replace(',', '.'));
            out.append(line, from, number.start()).append(format.apply(change.applyAsDouble(value)));
            from = number.end();
        }
        return out.append(line, from, line.length()).toString();
    }

    private static List<MatchResult> numbers(String line) {
        List<MatchResult> found = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(line);
        while (matcher.find()) {
            if (!colourCode(line, matcher.start())) found.add(matcher.toMatchResult());
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
