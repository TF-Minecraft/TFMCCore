package net.tfminecraft.tfmccore.stones;

import java.util.ArrayList;
import java.util.List;

public final class LorestoneConfig {

    public static String lorestonePath = "m.consumable.lorestone";
    public static String namestonePath = "m.consumable.namestone";
    public static List<String> blacklist = new ArrayList<>(List.of("v.bedrock"));
    public static int maxLength = 100;
    public static int promptTimeoutSeconds = 60;
    public static int maxLoreLines = 10;

    public static String promptLoreMessage = "&aType the lore line in chat (&e%timeout%s&a), or &eclear&a to remove all lore. Type &ccancel&a to abort.";
    public static String promptNameMessage = "&aType the new item name in chat (&e%timeout%s&a). Type &ccancel&a to abort.";
    public static String appliedLoreMessage = "&aLore added.";
    public static String appliedNameMessage = "&aItem renamed.";
    public static String clearedLoreMessage = "&aLore cleared.";
    public static String noLoreMessage = "&cThis item has no lore to clear. Stone refunded.";
    public static String pickColourMessage = "&aClick a colour above, or type one in chat (&6gold&a, &#ff8800#ff8800&a). Type &ccancel&a to abort.";
    public static String invalidColourMessage = "&cUnknown colour. Click one above, or type a name like &6gold&c or a hex like &f#ff8800&c.";
    public static String cancelledMessage = "&7Cancelled, stone refunded.";
    public static String timeoutMessage = "&7Timed out, stone refunded.";
    public static String itemMovedMessage = "&cThe target item moved. Stone refunded.";
    public static String emptyMessage = "&cText cannot be empty. Stone refunded.";
    public static String tooLongMessage = "&cMax &e%max%&c characters. Stone refunded.";
    public static String tooManyLinesMessage = "&cThis item already has &e%max%&c lore lines. Stone refunded.";
    public static String cannotApplyMessage = "&cYou can't use a stone on that item.";
    public static String stackedMessage = "&cUnstack the item first \u2014 stones apply to a single item.";
    public static String expiredMessage = "&7That prompt already ended.";
    public static String gaveMessage = "&aGave &e%amount%x %stone%&a to &e%player%&a.";

    private LorestoneConfig() {}
}
