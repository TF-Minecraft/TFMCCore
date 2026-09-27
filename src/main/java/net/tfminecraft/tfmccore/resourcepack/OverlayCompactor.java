package net.tfminecraft.tfmccore.resourcepack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.*;

/** Compacts pre-1.21.11 overlays, preserving ItemsAdder's later atlas-generation layers. */
public final class OverlayCompactor {
    private OverlayCompactor() {}

    private static final Set<String> COMPACTABLE = Set.of(
            "ia_overlay_1_20_5_plus", "ia_overlay_1_21_2_plus", "ia_overlay_1_21_4_to_5",
            "ia_overlay_1_21_4_plus", "ia_overlay_1_21_5", "ia_overlay_1_21_6_to_11",
            "ia_overlay_1_21_6_plus");

    public record Plan(JsonObject metadata, Map<String, String> copies, Set<String> removals) {}
    private record Range(int min, int max) {
        boolean contains(int version) { return min <= version && version <= max; }
    }
    private record Overlay(String directory, Range legacy, Range modern, Map<String, String> files) {}
    private record Segment(int min, int max, Map<String, String> files) {}

    public static Optional<Plan> plan(JsonObject metadata, Set<String> paths) {
        if (!metadata.has("overlays")) return Optional.empty();
        JsonArray entries = metadata.getAsJsonObject("overlays").getAsJsonArray("entries");
        if (entries == null || entries.size() < 2) return Optional.empty();
        List<Overlay> overlays = new ArrayList<>();
        Set<String> directories = new HashSet<>();
        TreeSet<Integer> cuts = new TreeSet<>(List.of(0, 65, 75));
        JsonArray retained = new JsonArray();
        JsonArray before = new JsonArray(), after = new JsonArray();
        boolean reachedTail = false;
        Set<String> removals = new HashSet<>();
        // This hook handles ItemsAdder's integer format ranges. Unknown/minor-version
        // schemas fail before the event is mutated rather than guessing compatibility.
        for (JsonElement element : entries) {
            JsonObject entry = element.getAsJsonObject();
            String directory = entry.get("directory").getAsString();
            if (!COMPACTABLE.contains(directory)) {
                // Preserve third-party metadata (including empty pending overlays)
                // and ItemsAdder's newer named layers exactly where they were.
                if (overlays.isEmpty()) before.add(entry.deepCopy());
                else { reachedTail = true; after.add(entry.deepCopy()); }
                continue;
            }
            if (reachedTail) throw new IllegalArgumentException("Interleaved compatibility overlays");
            if (!entry.keySet().equals(Set.of("directory", "formats", "min_format", "max_format"))) {
                throw new IllegalArgumentException("Unsupported overlay metadata fields");
            }
            if (!directories.add(directory)) throw new IllegalArgumentException("Duplicate overlay directory");
            Range modern = range(entry.get("min_format"), entry.get("max_format"));
            JsonArray formats = entry.getAsJsonArray("formats");
            if (formats.size() != 2) throw new IllegalArgumentException("Unsupported legacy format range");
            Range legacy = range(formats.get(0), formats.get(1));
            // ItemsAdder finalizes its modern atlases AFTER this event. Keep their
            // original directory names, resources and precedence from format 75 on.
            if (modern.max() >= 75) {
                JsonObject tail = entry.deepCopy();
                tail.addProperty("min_format", Math.max(75, modern.min()));
                retained.add(tail);
            }
            for (int boundary : List.of(modern.min(), modern.max() + 1, legacy.min(), legacy.max() + 1)) {
                if (boundary < 75) cuts.add(boundary);
            }
            Map<String, String> files = new TreeMap<>();
            for (String path : paths) {
                if (path.startsWith(directory + "/")) {
                    String relative = path.substring(directory.length() + 1);
                    if (!relative.startsWith("assets/") && !relative.startsWith("data/")) {
                        throw new IllegalArgumentException("Unexpected non-resource overlay entry: " + path);
                    }
                    files.put(relative, path);
                    if (modern.max() < 75) removals.add(path);
                }
            }
            // Optional ItemsAdder features can leave an empty declared overlay.
            // It contributes no resources; the later finalizer prunes empty layers.
            overlays.add(new Overlay(directory, legacy, modern, files));
        }
        List<Integer> boundaries = new ArrayList<>(cuts);
        List<Segment> segments = new ArrayList<>();
        boolean overlaps = false;
        for (int i = 0; i + 1 < boundaries.size(); i++) {
            int min = boundaries.get(i), max = boundaries.get(i + 1) - 1;
            Map<String, String> effective = new TreeMap<>();
            int active = 0;
            for (Overlay overlay : overlays) {
                if ((min < 65 ? overlay.legacy() : overlay.modern()).contains(min)) {
                    effective.putAll(overlay.files()); // Later overlays take precedence.
                    active++;
                }
            }
            overlaps |= active > 1;
            if (effective.isEmpty()) continue;
            if (!segments.isEmpty()) {
                Segment previous = segments.getLast();
                if (previous.max() + 1 == min && previous.files().equals(effective)) {
                    segments.set(segments.size() - 1, new Segment(previous.min(), max, effective));
                    continue;
                }
            }
            segments.add(new Segment(min, max, effective));
        }
        if (!overlaps || segments.isEmpty()) return Optional.empty();
        JsonArray compact = new JsonArray();
        before.forEach(compact::add);
        Map<String, String> copies = new TreeMap<>();
        for (Segment segment : segments) {
            String directory = "tfmc_overlay_" + segment.min() + "_" + segment.max();
            if (paths.stream().anyMatch(path -> path.startsWith(directory + "/"))) {
                throw new IllegalArgumentException("Generated overlay directory already exists: " + directory);
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("directory", directory);
            entry.addProperty("min_format", segment.min());
            entry.addProperty("max_format", segment.max());
            JsonArray legacy = new JsonArray();
            legacy.add(segment.min());
            legacy.add(segment.min() < 65 ? Math.min(segment.max(), 64) : segment.max());
            entry.add("formats", legacy);
            compact.add(entry);
            segment.files().forEach((relative, source) -> copies.put(directory + "/" + relative, source));
        }
        // Legacy clients still read formats. The retained tails must be inactive there.
        for (JsonElement tail : retained) {
            JsonObject entry = tail.getAsJsonObject();
            JsonArray formats = new JsonArray();
            formats.add(entry.get("min_format"));
            formats.add(entry.get("max_format"));
            entry.add("formats", formats);
            compact.add(entry);
        }
        after.forEach(compact::add);
        JsonObject updated = metadata.deepCopy();
        updated.getAsJsonObject("overlays").add("entries", compact);
        return Optional.of(new Plan(updated, Collections.unmodifiableMap(copies), Set.copyOf(removals)));
    }

    private static Range range(JsonElement min, JsonElement max) {
        int low = integer(min), high = integer(max);
        if (low < 0 || high < low || high == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid overlay format range");
        }
        return new Range(low, high);
    }

    private static int integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Only integer overlay formats are supported");
        }
        return value.getAsBigDecimal().intValueExact();
    }
}
