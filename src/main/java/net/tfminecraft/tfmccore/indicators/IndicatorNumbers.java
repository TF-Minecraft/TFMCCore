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
 * <p>Colour and format codes are skipped as whole tokens first ({@code &4}, {@code §l},
 * {@code &#ff0000}, {@code #ff0000}, MiniMessage tags like {@code <shadow:#000000:0.5>}), so a
 * code right before the value ({@code &46.4}) still leaves {@code 6.4} as the number. A line holds
 * one number per indicator, or several when MythicLib's {@code split_holograms} is off and it
 * joins a hit's numbers into one line. With {@code custom_font} on, MythicLib draws the digits as
 * glyphs, so no number is found and the line is left as it is.
 */
final class IndicatorNumbers {

    private static final Pattern TOKEN = Pattern.compile(
            "(?<code>[&§]#[0-9A-Fa-f]{6}|[&§].|#[0-9A-Fa-f]{6}|<[^<>]*>)|(?<number>\\d+(?:[.,]\\d+)?)");

    private IndicatorNumbers() {
    }

    /** How many numbers {@code line} shows. */
    static int count(String line) {
        return numbers(line).size();
    }

    /** The numbers {@code line} shows, in order. */
    static List<Double> values(String line) {
        List<Double> values = new ArrayList<>();
        for (MatchResult number : numbers(line)) values.add(parse(number));
        return values;
    }

    /** {@code line} with every number replaced by {@code format(change(number))}. */
    static String rewrite(String line, DoubleUnaryOperator change, DoubleFunction<String> format) {
        StringBuilder out = new StringBuilder();
        int from = 0;
        for (MatchResult number : numbers(line)) {
            out.append(line, from, number.start()).append(format.apply(change.applyAsDouble(parse(number))));
            from = number.end();
        }
        return out.append(line, from, line.length()).toString();
    }

    private static double parse(MatchResult number) {
        return Double.parseDouble(number.group().replace(',', '.'));
    }

    private static List<MatchResult> numbers(String line) {
        List<MatchResult> found = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(line);
        while (matcher.find()) {
            if (matcher.group("number") != null) found.add(matcher.toMatchResult());
        }
        return found;
    }
}
