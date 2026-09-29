package net.tfminecraft.tfmccore.cache;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;

public class Cache {
    public static volatile boolean compactResourcePackOverlays = true;

    public static boolean allowBoneMeal;
    public static boolean allowBrewing;
    public static boolean allowEnchanting;
    public static boolean limitShields;
    public static boolean horseArchery;
    public static boolean preventGolemScrape;
    public static boolean dropsDebug;
    public static boolean xaeroFairPlay = true;

    public static int armourTime;

    public static List<Material> blockedCrafts = new ArrayList<>();
    public static List<Material> blockedConsume = new ArrayList<>();
}
