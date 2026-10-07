package net.tfminecraft.tfmccore.reference;

import java.util.concurrent.ThreadLocalRandom;

public class DropEntry {
    private final String item;
    private final double chance;
    private final int min;
    private final int max;

    public DropEntry(String s) {
        if (s == null || s.isBlank()) {
            throw invalid(s, "must not be blank");
        }
        String[] full = s.trim().split("\\s+");
        if (full.length > 3) {
            throw invalid(s, "expected item(chance) [amount] or item(chance) [min max]");
        }
        String first = full[0];
        int open = first.indexOf('(');
        int close = first.indexOf(')');
        if (open < 0 ? close >= 0 : open == 0 || close != first.length() - 1
                || first.indexOf('(', open + 1) >= 0) {
            throw invalid(s, "expected a non-empty item path with an optional parenthesized chance");
        }
        item = open < 0 ? first : first.substring(0, open);
        try {
            chance = open < 0 ? 0.0 : Double.parseDouble(first.substring(open + 1, close));
            min = full.length > 1 ? Integer.parseInt(full[1]) : 1;
            max = full.length > 2 ? Integer.parseInt(full[2]) : min;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid number in drop entry '" + s + "'", error);
        }
        if (!Double.isFinite(chance) || chance < 0.0 || chance > 1.0) {
            throw invalid(s, "chance must be a finite number between 0 and 1");
        }
        if (min < 1 || max < min) {
            throw invalid(s, "quantities must satisfy 1 <= min <= max");
        }
    }

    private static IllegalArgumentException invalid(String entry, String reason) {
        return new IllegalArgumentException("Invalid drop entry '" + entry + "': " + reason);
    }

    public String getItem() {
        return item;
    }

    public double getChance() {
        return chance;
    }

    public int getMin() {
        return min;
    }

    public int getMax() {
        return max;
    }

    public int getAmount() {
        return (int) ThreadLocalRandom.current().nextLong(min, (long) max + 1);
    }
}
