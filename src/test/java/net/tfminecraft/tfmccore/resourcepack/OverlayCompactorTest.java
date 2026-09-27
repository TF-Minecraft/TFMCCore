package net.tfminecraft.tfmccore.resourcepack;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.lone.itemsadder.api.Events.ItemsAdderPackCompressedEvent;
import dev.lone.itemsadder.api.Events.ItemsAdderPackCompressedEvent$PackEntry;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;
import net.tfminecraft.tfmccore.cache.Cache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OverlayCompactorTest {
    private static final String METADATA = """
        {"pack":{"pack_format":69,"min_format":32,"max_format":9999,"supported_formats":[32,64]},
         "sodium":{"ignored_shaders":["test.vsh"]},
         "overlays":{"entries":[
          {"directory":"ia_overlay_1_20_5_plus","formats":[32,64],"min_format":32,"max_format":87},
          {"directory":"ia_overlay_1_21_6_to_11","formats":[63,64],"min_format":63,"max_format":75},
          {"directory":"ia_overlay_modern_atlas","formats":[75,87],"min_format":75,"max_format":87}]}}
        """;

    @AfterEach void resetConfig() { Cache.compactResourcePackOverlays = true; }

    private Map<String, String> files() {
        return new LinkedHashMap<>(Map.of(
                "pack.mcmeta", METADATA,
                "assets/test/models/shared.json", "base",
                "assets/test/sounds.json", "sounds stay unchanged",
                "ia_overlay_1_20_5_plus/assets/test/models/shared.json", "early",
                "ia_overlay_1_20_5_plus/assets/test/textures/early.png", "png bytes",
                "ia_overlay_1_21_6_to_11/assets/test/models/shared.json", "later wins",
                "ia_overlay_1_21_6_to_11/assets/test/textures/early.png.mcmeta", "animation metadata",
                "ia_overlay_modern_atlas/assets/test/models/shared.json", "newest wins"));
    }

    @Test void preservesEveryEffectiveResourceAcrossLegacyAndModernBoundaries() {
        var before = files();
        var metadata = JsonParser.parseString(METADATA).getAsJsonObject();
        var plan = OverlayCompactor.plan(metadata, before.keySet()).orElseThrow();
        var after = apply(before, plan);
        // Check priority, additions, metadata and fallback through every nearby format,
        // including 65 where Minecraft switched to the min/max format schema.
        for (int version = 0; version <= 100; version++) {
            assertEquals(effective(before, version), effective(after, version), "format " + version);
            if (version < 75) assertTrue(activeCount(plan.metadata(), version) <= 1);
        }
        assertEquals("later wins", effective(after, 69).get("assets/test/models/shared.json"));
        assertEquals("newest wins", effective(after, 75).get("assets/test/models/shared.json"));
        assertEquals(metadata.get("pack"), plan.metadata().get("pack"));
        assertEquals(metadata.get("sodium"), plan.metadata().get("sodium"));
        assertEquals(JsonParser.parseString(METADATA), metadata, "planning must not mutate its input");
        assertEquals("base", after.get("assets/test/models/shared.json"));
        assertTrue(OverlayCompactor.plan(plan.metadata(), after.keySet()).isEmpty(), "reapplying is a no-op");
    }

    @Test void leavesModernAtlasDirectoriesAvailableForItemsAdderFinalization() {
        var before = files();
        var metadata = JsonParser.parseString(METADATA).getAsJsonObject();
        JsonObject pending = new JsonObject();
        pending.addProperty("directory", "ia_overlay_26_1_plus");
        pending.addProperty("min_format", 84);
        pending.addProperty("max_format", 87);
        pending.add("formats", JsonParser.parseString("[84,87]"));
        metadata.getAsJsonObject("overlays").getAsJsonArray("entries").add(pending);
        before.put("pack.mcmeta", metadata.toString());
        // This directory has no entries yet: ItemsAdder writes it after the event.
        var plan = OverlayCompactor.plan(metadata, before.keySet()).orElseThrow();
        var after = apply(before, plan);
        assertTrue(plan.metadata().getAsJsonObject("overlays").getAsJsonArray("entries").asList().contains(pending));
        before.forEach((path, bytes) -> {
            if (!path.equals("pack.mcmeta")) assertEquals(bytes, after.get(path), path);
        });
        // Later generation must see the same resources under the same physical paths.
        for (int version = 75; version <= 100; version++) {
            assertEquals(activeDirectories(metadata, version), activeDirectories(plan.metadata(), version));
            assertEquals(effective(before, version), effective(after, version));
        }
    }

    private static List<String> activeDirectories(JsonObject metadata, int version) {
        List<String> result = new ArrayList<>();
        for (var item : metadata.getAsJsonObject("overlays").getAsJsonArray("entries")) {
            var entry = item.getAsJsonObject();
            if (active(entry, version)) result.add(entry.get("directory").getAsString());
        }
        return result;
    }

    @Test void leavesThirdPartyOverlaySchemasAndEmptyDirectoriesUntouched() {
        var metadata = JsonParser.parseString(METADATA).getAsJsonObject();
        var original = metadata.getAsJsonObject("overlays").getAsJsonArray("entries");
        var thirdParty = JsonParser.parseString("""
            {"directory":"modelengine_1_19_4","formats":{"min_inclusive":15,"max_inclusive":36},
             "min_format":15,"max_format":36}
            """).getAsJsonObject();
        var entries = new JsonArray();
        entries.add(thirdParty);
        original.forEach(entries::add);
        metadata.getAsJsonObject("overlays").add("entries", entries);
        var plan = OverlayCompactor.plan(metadata, files().keySet()).orElseThrow();
        assertEquals(thirdParty, plan.metadata().getAsJsonObject("overlays").getAsJsonArray("entries").get(0));
        // Moving an intervening third-party layer could change priority; decline it.
        var interleaved = new JsonArray();
        interleaved.add(original.get(0));
        interleaved.add(thirdParty);
        interleaved.add(original.get(1));
        metadata.getAsJsonObject("overlays").add("entries", interleaved);
        assertThrows(IllegalArgumentException.class, () -> OverlayCompactor.plan(metadata, files().keySet()));
    }

    @Test void declinesUnknownMinorFormatsWithoutMutatingInput() {
        JsonObject metadata = JsonParser.parseString(METADATA).getAsJsonObject();
        metadata.getAsJsonObject("overlays").getAsJsonArray("entries").get(0).getAsJsonObject()
                .add("min_format", JsonParser.parseString("[32,1]"));
        JsonObject snapshot = metadata.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> OverlayCompactor.plan(metadata, files().keySet()));
        assertEquals(snapshot, metadata);
    }

    @Test void refusesGeneratedDirectoryCollisions() {
        var files = files();
        files.put("tfmc_overlay_32_62/assets/test/unknown.json", "do not overwrite");
        assertThrows(IllegalArgumentException.class, () -> OverlayCompactor.plan(
                JsonParser.parseString(METADATA).getAsJsonObject(), files.keySet()));
    }

    @Test void refusesMissingOverlayAndUnknownMetadata() {
        var files = files();
        files.keySet().removeIf(p -> p.startsWith("ia_overlay_1_20_5_plus/"));
        assertThrows(IllegalArgumentException.class, () -> OverlayCompactor.plan(
                JsonParser.parseString(METADATA).getAsJsonObject(), files.keySet()));
        var metadata = JsonParser.parseString(METADATA).getAsJsonObject();
        metadata.getAsJsonObject("overlays").getAsJsonArray("entries").get(0).getAsJsonObject()
                .addProperty("future_setting", true);
        assertThrows(IllegalArgumentException.class, () -> OverlayCompactor.plan(metadata, files().keySet()));
    }

    @Test void buildHookPreservesPayloadsAndPublicNames() {
        var before = files();
        var event = event(before);
        new ResourcePackListener(Logger.getAnonymousLogger()).compactOverlays(event);
        Map<String, String> after = texts(event);
        assertNotEquals(before.get("pack.mcmeta"), after.get("pack.mcmeta"));
        for (int version = 0; version <= 100; version++) {
            assertEquals(effective(before, version), effective(after, version));
        }
    }

    @Test void unreadableSourceLeavesOriginalEventUntouched() {
        var event = event(files());
        event.getEntries().put("ia_overlay_1_21_6_to_11/assets/test/models/shared.json", ItemsAdderPackCompressedEvent$PackEntry.stream(
                "ia_overlay_1_21_6_to_11/assets/test/models/shared.json", () -> { throw new IllegalStateException("unreadable"); }));
        Set<?> keys = Set.copyOf(event.getEntries().keySet());
        String metadata = event.getEntry("pack.mcmeta").getText();
        new ResourcePackListener(Logger.getAnonymousLogger()).compactOverlays(event);
        assertEquals(keys, event.getEntries().keySet());
        assertEquals(metadata, event.getEntry("pack.mcmeta").getText());
        assertFalse(event.getEntry("ia_overlay_1_20_5_plus/assets/test/models/shared.json").isRemoved());
    }

    @Test void disabledHookLeavesPackUnchanged() {
        var before = files();
        var event = event(before);
        Cache.compactResourcePackOverlays = false;
        new ResourcePackListener(Logger.getAnonymousLogger()).compactOverlays(event);
        assertEquals(before, texts(event));
    }

    private static ItemsAdderPackCompressedEvent event(Map<String, String> files) {
        var event = new ItemsAdderPackCompressedEvent();
        files.forEach((path, text) -> event.setEntry(path, text.getBytes(StandardCharsets.UTF_8)));
        return event;
    }

    private static Map<String, String> texts(ItemsAdderPackCompressedEvent event) {
        Map<String, String> result = new TreeMap<>();
        for (Object path : event.getEntries().keySet()) result.put((String) path, event.getEntry((String) path).getText());
        return result;
    }

    private static Map<String, String> apply(Map<String, String> source, OverlayCompactor.Plan plan) {
        Map<String, String> result = new LinkedHashMap<>(source);
        plan.removals().forEach(result::remove);
        plan.copies().forEach((to, from) -> result.put(to, source.get(from)));
        result.put("pack.mcmeta", plan.metadata().toString());
        return result;
    }

    private static boolean active(JsonObject entry, int version) {
        return version < 65
                ? entry.getAsJsonArray("formats").get(0).getAsInt() <= version
                  && version <= entry.getAsJsonArray("formats").get(1).getAsInt()
                : entry.get("min_format").getAsInt() <= version && version <= entry.get("max_format").getAsInt();
    }

    private static int activeCount(JsonObject metadata, int version) {
        int count = 0;
        for (var item : metadata.getAsJsonObject("overlays").getAsJsonArray("entries")) {
            if (active(item.getAsJsonObject(), version)) count++;
        }
        return count;
    }

    private static Map<String, String> effective(Map<String, String> files, int version) {
        var metadata = JsonParser.parseString(files.get("pack.mcmeta")).getAsJsonObject();
        Map<String, String> result = new TreeMap<>();
        files.forEach((path, bytes) -> { if (path.startsWith("assets/") || path.startsWith("data/")) result.put(path, bytes); });
        for (var item : metadata.getAsJsonObject("overlays").getAsJsonArray("entries")) {
            var entry = item.getAsJsonObject();
            if (!active(entry, version)) continue;
            String prefix = entry.get("directory").getAsString() + "/";
            files.forEach((path, bytes) -> { if (path.startsWith(prefix)) result.put(path.substring(prefix.length()), bytes); });
        }
        return result;
    }
}
