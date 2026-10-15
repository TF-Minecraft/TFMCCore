package net.tfminecraft.tfmccore.resourcepack;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.*;

/**
 * Merges one-glyph bitmap font providers that share a shape into grid sheets. The client checks
 * every provider of a font for every code point it knows (unifont's ~60,000 included), so
 * ItemsAdder's hundreds of one-glyph providers cost seconds on each pack reload.
 */
final class FontGridMerger {
    static final String SHEETS = "tfmc_font_grid/";
    private static final int MAX_SHEET = 4096;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private record Glyph(int codePoint, BufferedImage image, JsonElement provider) {}
    private FontGridMerger() {}

    /** Rewritten root font files and their new sheets, keyed by archive path. */
    static Map<String, byte[]> merge(ZipFile zip, Set<String> overlays) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        List<? extends ZipEntry> fonts = zip.stream().filter(entry -> entry.getName().matches("assets/[^/]+/font/.+\\.json")).toList();
        for (ZipEntry entry : fonts) {
            String namespace = entry.getName().split("/")[1];
            Map<String, byte[]> sheets = new TreeMap<>();
            try {
                JsonObject font = JsonParser.parseString(new String(read(zip, entry), StandardCharsets.UTF_8)).getAsJsonObject();
                JsonArray providers = merge(zip, overlays, namespace, font.getAsJsonArray("providers"), sheets);
                if (providers == null) continue;
                font.add("providers", providers);
                files.put(entry.getName(), GSON.toJson(font).getBytes(StandardCharsets.UTF_8));
                files.putAll(sheets);
            } catch (RuntimeException malformed) {
                // The client rejects the same file; leave it exactly as ItemsAdder wrote it.
            }
        }
        return files;
    }

    private static JsonArray merge(ZipFile zip, Set<String> overlays, String namespace, JsonArray providers,
            Map<String, byte[]> sheets) throws IOException {
        Map<Integer, Integer> uses = new HashMap<>();
        for (JsonElement provider : providers) {
            List<Integer> points = codePoints(provider);
            // Merged providers move, so every code point must have exactly one definer in this file.
            if (points == null) return null;
            points.forEach(point -> uses.merge(point, 1, Integer::sum));
        }
        Map<String, List<Glyph>> groups = new LinkedHashMap<>();
        List<Object> order = new ArrayList<>();
        for (JsonElement provider : providers) {
            Glyph glyph = glyph(zip, overlays, provider, uses);
            if (glyph == null) { order.add(provider); continue; }
            String key = glyph.image.getWidth() + "x" + glyph.image.getHeight() + GSON.toJson(shape(provider));
            if (!groups.containsKey(key)) { groups.put(key, new ArrayList<>()); order.add(key); }
            groups.get(key).add(glyph);
        }
        if (groups.values().stream().allMatch(glyphs -> glyphs.size() == 1)) return null;
        JsonArray result = new JsonArray();
        for (Object item : order) {
            if (item instanceof JsonElement provider) { result.add(provider); continue; }
            List<Glyph> glyphs = groups.get(item);
            int width = glyphs.get(0).image.getWidth(), height = glyphs.get(0).image.getHeight();
            int columns = Math.min((int) Math.ceil(Math.sqrt(glyphs.size())), MAX_SHEET / width);
            int perSheet = columns * (MAX_SHEET / height);
            for (int start = 0; start < glyphs.size(); start += perSheet) {
                List<Glyph> chunk = glyphs.subList(start, Math.min(glyphs.size(), start + perSheet));
                if (chunk.size() == 1) { result.add(chunk.get(0).provider); continue; }
                result.add(sheet(chunk, columns, namespace, sheets));
            }
        }
        return result;
    }

    private static JsonObject sheet(List<Glyph> glyphs, int columns, String namespace, Map<String, byte[]> sheets) throws IOException {
        int width = glyphs.get(0).image.getWidth(), height = glyphs.get(0).image.getHeight();
        int rows = (glyphs.size() + columns - 1) / columns;
        BufferedImage sheet = new BufferedImage(columns * width, rows * height, BufferedImage.TYPE_INT_ARGB);
        JsonArray chars = new JsonArray();
        for (int row = 0; row < rows; row++) {
            StringBuilder line = new StringBuilder();
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                if (index >= glyphs.size()) { line.append('\0'); continue; } // The client skips U+0000 cells.
                Glyph glyph = glyphs.get(index);
                sheet.setRGB(column * width, row * height, width, height, glyph.image.getRGB(0, 0, width, height, null, 0, width), 0, width);
                line.appendCodePoint(glyph.codePoint);
            }
            chars.add(line.toString());
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        try (ImageOutputStream out = new MemoryCacheImageOutputStream(png)) { ImageIO.write(sheet, "png", out); }
        String file = SHEETS + PackPartitioner.sha1(png.toByteArray()) + ".png";
        sheets.put("assets/" + namespace + "/textures/" + file, png.toByteArray());
        JsonObject provider = shape(glyphs.get(0).provider);
        provider.addProperty("file", namespace + ":" + file);
        provider.add("chars", chars);
        return provider;
    }

    /** Everything about a provider except its image and characters. */
    private static JsonObject shape(JsonElement provider) {
        JsonObject shape = provider.getAsJsonObject().deepCopy();
        shape.remove("file");
        shape.remove("chars");
        return shape;
    }

    /** Code points a provider defines, or null when they cannot be listed (references, TrueType, unihex). */
    private static List<Integer> codePoints(JsonElement element) {
        JsonObject provider = element.getAsJsonObject();
        List<Integer> points = new ArrayList<>();
        switch (provider.get("type").getAsString().replace("minecraft:", "")) {
            case "bitmap" -> { for (JsonElement row : provider.getAsJsonArray("chars")) row.getAsString().codePoints().forEach(points::add); }
            case "space" -> { for (String key : provider.getAsJsonObject("advances").keySet()) key.codePoints().forEach(points::add); }
            default -> { return null; }
        }
        return points;
    }

    private static Glyph glyph(ZipFile zip, Set<String> overlays, JsonElement element, Map<Integer, Integer> uses) throws IOException {
        JsonObject provider = element.getAsJsonObject();
        if (!provider.get("type").getAsString().replace("minecraft:", "").equals("bitmap")) return null;
        JsonArray rows = provider.getAsJsonArray("chars");
        int[] points = rows.size() == 1 ? rows.get(0).getAsString().codePoints().toArray() : new int[0];
        if (points.length != 1 || points[0] == 0 || uses.get(points[0]) != 1) return null;
        String file = provider.get("file").getAsString();
        int colon = file.indexOf(':');
        String texture = "assets/" + (colon < 0 ? "minecraft" : file.substring(0, colon)) + "/textures/" + file.substring(colon + 1);
        // An overlay may swap this texture for some client versions; a merged copy could not follow it.
        for (String overlay : overlays) if (zip.getEntry(overlay + "/" + texture) != null) return null;
        ZipEntry entry = zip.getEntry(texture);
        if (entry == null) return null; // Vanilla textures are not in the pack.
        BufferedImage image = decode(read(zip, entry));
        return image == null ? null : new Glyph(points[0], image, element);
    }

    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) { return in.readAllBytes(); }
    }

    /**
     * Decodes 8-bit RGB, RGBA and palette PNGs exactly as the client does. ItemsAdder scrambles chunk
     * CRCs and the zlib checksum, which the client ignores, so the image data is inflated raw and
     * rebuilt first. Other formats return null and stay unmerged.
     */
    static BufferedImage decode(byte[] png) throws IOException {
        if (png.length < 33 || ByteBuffer.wrap(png).getLong() != 0x89504E470D0A1A0AL) return null;
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        byte[] header = null, palette = null, transparency = null;
        ByteBuffer chunks = ByteBuffer.wrap(png);
        for (int at = 8; at + 12 <= png.length;) {
            int length = chunks.getInt(at);
            if (length < 0 || at + 12L + length > png.length) return null;
            String type = new String(png, at + 4, 4, StandardCharsets.ISO_8859_1);
            byte[] body = Arrays.copyOfRange(png, at + 8, at + 8 + length);
            switch (type) {
                case "IHDR" -> header = body;
                case "PLTE" -> palette = body;
                case "tRNS" -> transparency = body;
                case "IDAT" -> idat.write(body);
                default -> { }
            }
            if (type.equals("IEND")) break;
            at += 12 + length;
        }
        if (header == null || header.length != 13 || idat.size() < 2) return null;
        int width = ByteBuffer.wrap(header).getInt(0), height = ByteBuffer.wrap(header).getInt(4);
        int depth = header[8], colour = header[9];
        boolean supported = colour == 3 ? depth <= 8 : depth == 8 && (colour == 6 || colour == 2 && transparency == null);
        if (!supported || width < 1 || height < 1 || width > MAX_SHEET || height > MAX_SHEET) return null;
        // Raw inflate skips the two-byte zlib header and never checks the trailing Adler-32.
        Inflater inflater = new Inflater(true);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        long limit = 2L * height * (1 + 4L * width) + 1024;
        try {
            byte[] data = idat.toByteArray(), buffer = new byte[65536];
            inflater.setInput(data, 2, data.length - 2);
            while (!inflater.finished() && !inflater.needsInput() && raw.size() <= limit) {
                raw.write(buffer, 0, inflater.inflate(buffer));
            }
        } catch (DataFormatException corrupt) {
            return null;
        } finally {
            inflater.end();
        }
        if (raw.size() > limit) return null;
        ByteArrayOutputStream rebuilt = new ByteArrayOutputStream();
        rebuilt.write(png, 0, 8);
        chunk(rebuilt, "IHDR", header);
        if (palette != null) chunk(rebuilt, "PLTE", palette);
        if (transparency != null) chunk(rebuilt, "tRNS", transparency);
        Deflater deflater = new Deflater();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (OutputStream out = new DeflaterOutputStream(compressed, deflater)) { raw.writeTo(out); }
        deflater.end();
        chunk(rebuilt, "IDAT", compressed.toByteArray());
        chunk(rebuilt, "IEND", new byte[0]);
        try {
            // Memory cache: ImageIO's default stream cache writes temporary files. ImageIO.read closes it.
            return ImageIO.read(new MemoryCacheImageInputStream(new ByteArrayInputStream(rebuilt.toByteArray())));
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] body) {
        CRC32 crc = new CRC32();
        byte[] name = type.getBytes(StandardCharsets.ISO_8859_1);
        crc.update(name);
        crc.update(body);
        out.writeBytes(ByteBuffer.allocate(4).putInt(body.length).array());
        out.writeBytes(name);
        out.writeBytes(body);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
