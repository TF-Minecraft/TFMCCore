package net.tfminecraft.tfmccore.cache;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;

public class Cache {
    public static volatile boolean compactResourcePackOverlays = true;

    public static boolean allowBoneMeal = true;
    public static boolean allowBrewing = true;
    public static boolean allowEnchanting = true;
    public static boolean limitShields;
    public static boolean horseArchery = true;
    public static boolean preventGolemScrape = true;
    public static boolean dropsDebug = true;
    public static boolean xaeroFairPlay = true;
    // Read from packet threads
    public static volatile boolean hideBookGlint = true;

    public static int armourTime = 7;

    public static List<Material> blockedCrafts = new ArrayList<>();
    public static List<Material> blockedConsume = new ArrayList<>();
}
