package net.tfminecraft.tfmccore.resourcepack;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FontGridMergerTest {
    @TempDir Path directory;

    private static BufferedImage image(int width, int height, int seed) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Mixed opacity, including transparent pixels with colour, which must survive exactly.
                image.setRGB(x, y, ((x * 31 + y * 17 + seed) & 0xFF) << 24 | (seed * 0x010305 + x * 7 + y) & 0xFFFFFF);
            }
        }
        return image;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** ItemsAdder's protection: zeroed chunk CRCs and a wrong zlib checksum. */
    private static byte[] protect(byte[] png) {
        byte[] bytes = png.clone();
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        for (int at = 8; at < bytes.length;) {
            int length = buffer.getInt(at);
            if (new String(bytes, at + 4, 4, StandardCharsets.ISO_8859_1).equals("IDAT")) bytes[at + 8 + length - 1] ^= 0x5A;
            buffer.putInt(at + 8 + length, 0);
            at += 12 + length;
        }
        return bytes;
    }

    private static byte[] chunk(String type, byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(type.getBytes(StandardCharsets.ISO_8859_1));
        crc.update(body);
        return ByteBuffer.allocate(12 + body.length).putInt(body.length).put(type.getBytes(StandardCharsets.ISO_8859_1))
                .put(body).putInt((int) crc.getValue()).array();
    }

    private static byte[] zlib(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStream deflate = new DeflaterOutputStream(out)) { deflate.write(raw); } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
        return out.toByteArray();
    }

    /** A hand-built PNG; extra chunks go between IHDR and IDAT. */
    private static byte[] manual(int width, int height, int depth, int colour, byte[] idat, byte[]... extra) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        out.writeBytes(chunk("IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) depth).put((byte) colour).array()));
        for (byte[] chunk : extra) out.writeBytes(chunk);
        out.writeBytes(chunk("IDAT", idat));
        out.writeBytes(chunk("IEND", new byte[0]));
        return out.toByteArray();
    }

    private static String bitmap(String type, String file, String chars, String extra) {
        return "{\"type\":\"" + type + "\",\"file\":\"" + file + "\",\"chars\":[\"" + chars + "\"]" + extra + "}";
    }

    private static String font(String... providers) { return "{\"providers\":[" + String.join(",", providers) + "]}"; }

    private Path zip(Map<String, byte[]> files) throws IOException {
        Path path = directory.resolve(UUID.randomUUID() + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : files.entrySet()) { out.putNextEntry(new ZipEntry(entry.getKey())); out.write(entry.getValue()); out.closeEntry(); }
        }
        return path;
    }

    private Map<String, byte[]> merge(Map<String, byte[]> files) throws IOException {
        try (ZipFile zip = new ZipFile(zip(files).toFile())) { return FontGridMerger.merge(zip, Set.of("new")); }
    }

    private static int[] pixels(BufferedImage image, int x, int y, int width, int height) {
        return image.getRGB(x, y, width, height, null, 0, width);
    }

    @Test void mergesSameShapedGlyphsIntoOneGridAndKeepsEverythingElse() throws Exception {
        BufferedImage a = image(8, 8, 1), b = image(8, 8, 2), c = image(8, 8, 3);
        String shape = ",\"height\":8,\"ascent\":7,\"filter\":{\"uniform\":false}";
        String font = font(
                "{\"type\":\"space\",\"advances\":{\" \":4}}",
                bitmap("bitmap", "minecraft:font/a.png", "\\ue000", shape),
                bitmap("bitmap", "font/b.png", "\\ue001", shape),
                bitmap("minecraft:bitmap", "minecraft:font/wide.png", "\\ue002", shape),
                bitmap("bitmap", "minecraft:item/apple.png", "\\ue003", shape),
                bitmap("bitmap", "minecraft:font/over.png", "\\ue004", shape),
                bitmap("bitmap", "minecraft:font/a.png", "\\ue005\\ue006", shape),
                bitmap("bitmap", "minecraft:font/c.png", "\\udb80\\udc00", shape));
        Map<String, byte[]> files = new TreeMap<>(Map.of(
                "assets/minecraft/font/default.json", font.getBytes(StandardCharsets.UTF_8),
                "assets/minecraft/textures/font/a.png", png(a),
                "assets/minecraft/textures/font/b.png", protect(png(b)),
                "assets/minecraft/textures/font/c.png", png(c),
                "assets/minecraft/textures/font/wide.png", png(image(16, 8, 4)),
                "assets/minecraft/textures/font/over.png", png(image(8, 8, 5)),
                "new/assets/minecraft/textures/font/over.png", png(image(8, 8, 6))));

        Map<String, byte[]> merged = merge(files);

        JsonArray providers = JsonParser.parseString(new String(merged.get("assets/minecraft/font/default.json"), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("providers");
        JsonArray original = JsonParser.parseString(font).getAsJsonObject().getAsJsonArray("providers");
        assertEquals(6, providers.size());
        assertEquals(original.get(0), providers.get(0));
        for (int i = 2; i < 6; i++) assertEquals(original.get(i + 1), providers.get(i), "unmergeable providers keep their order");
        JsonObject grid = providers.get(1).getAsJsonObject();
        assertEquals(8, grid.get("height").getAsInt());
        assertEquals(7, grid.get("ascent").getAsInt());
        assertEquals(JsonParser.parseString("{\"uniform\":false}"), grid.get("filter"));
        assertEquals("bitmap", grid.get("type").getAsString());
        assertEquals(List.of("", "󰀀\u0000"), grid.getAsJsonArray("chars").asList().stream().map(JsonElement::getAsString).toList());
        String sheetPath = "assets/minecraft/textures/" + grid.get("file").getAsString().substring("minecraft:".length());
        assertTrue(sheetPath.startsWith("assets/minecraft/textures/" + FontGridMerger.SHEETS));
        assertEquals(Set.of("assets/minecraft/font/default.json", sheetPath), merged.keySet());
        BufferedImage sheet = ImageIO.read(new ByteArrayInputStream(merged.get(sheetPath)));
        assertEquals(16, sheet.getWidth());
        assertEquals(16, sheet.getHeight());
        assertArrayEquals(pixels(a, 0, 0, 8, 8), pixels(sheet, 0, 0, 8, 8));
        assertArrayEquals(pixels(b, 0, 0, 8, 8), pixels(sheet, 8, 0, 8, 8), "protected PNGs decode like the client");
        assertArrayEquals(pixels(c, 0, 0, 8, 8), pixels(sheet, 0, 8, 8, 8));
        assertArrayEquals(new int[64], pixels(sheet, 8, 8, 8, 8));
        assertEquals(sheetPath, "assets/minecraft/textures/" + FontGridMerger.SHEETS + PackPartitioner.sha1(merged.get(sheetPath)) + ".png");
    }

    @Test void leavesFontsItCannotSafelyMerge() throws Exception {
        String shape = ",\"ascent\":7";
        byte[] glyph = png(image(8, 8, 1));
        byte[] grey = manual(8, 8, 8, 0, zlib(new byte[8 * 9]));
        Map<String, byte[]> files = new TreeMap<>();
        files.put("assets/minecraft/textures/font/a.png", glyph);
        files.put("assets/minecraft/textures/font/grey.png", grey);
        files.put("assets/x/font/reference.json", font("{\"type\":\"reference\",\"id\":\"minecraft:default\"}",
                bitmap("bitmap", "font/a.png", "\\ue000", shape), bitmap("bitmap", "font/a.png", "\\ue001", shape)).getBytes(StandardCharsets.UTF_8));
        files.put("assets/x/font/broken.json", "{not json".getBytes(StandardCharsets.UTF_8));
        files.put("assets/x/font/no-providers.json", "{}".getBytes(StandardCharsets.UTF_8));
        files.put("assets/x/font/duplicate.json", font(bitmap("bitmap", "font/a.png", "\\ue000", shape),
                bitmap("bitmap", "font/a.png", "\\ue000", shape)).getBytes(StandardCharsets.UTF_8));
        files.put("assets/x/font/null.json", font(bitmap("bitmap", "font/a.png", "\\u0000", shape),
                bitmap("bitmap", "font/a.png", "\\ue000", shape)).getBytes(StandardCharsets.UTF_8));
        files.put("assets/x/font/grey.json", font(bitmap("bitmap", "font/grey.png", "\\ue000", shape),
                bitmap("bitmap", "font/grey.png", "\\ue001", shape)).getBytes(StandardCharsets.UTF_8));
        files.put("assets/x/font/shapes.json", font(bitmap("bitmap", "font/a.png", "\\ue000", shape),
                bitmap("bitmap", "font/a.png", "\\ue001", ",\"ascent\":6")).getBytes(StandardCharsets.UTF_8));
        files.put("new/assets/x/font/overlay.json", font(bitmap("bitmap", "font/a.png", "\\ue000", shape),
                bitmap("bitmap", "font/a.png", "\\ue001", shape)).getBytes(StandardCharsets.UTF_8));
        assertEquals(Map.of(), merge(files));
    }

    @Test void boundsWhatItReadsAndHoldsInMemory() throws Exception {
        String shape = ",\"ascent\":7";
        byte[] tile = png(new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB));
        Map<String, byte[]> files = new TreeMap<>();
        files.put("assets/minecraft/textures/font/tile.png", tile);
        files.put("assets/minecraft/textures/font/huge.png", png(new BufferedImage(1025, 1024, BufferedImage.TYPE_INT_ARGB)));
        byte[] padded = Arrays.copyOf(png(image(8, 8, 1)), 9 * 1024 * 1024); // inflates past the texture limit
        files.put("assets/minecraft/textures/font/padded.png", padded);
        List<String> tiles = new ArrayList<>();
        for (int i = 0; i < 17; i++) tiles.add(bitmap("bitmap", "font/tile.png", String.format("\\u%04x", 0xe000 + i), shape));
        files.put("assets/minecraft/font/default.json", font(tiles.toArray(String[]::new)).getBytes(StandardCharsets.UTF_8));
        files.put("assets/x/font/huge.json", font(bitmap("bitmap", "minecraft:font/huge.png", "\\ue000", shape),
                bitmap("bitmap", "minecraft:font/huge.png", "\\ue001", shape)).getBytes(StandardCharsets.UTF_8));
        files.put("assets/x/font/padded.json", font(bitmap("bitmap", "minecraft:font/padded.png", "\\ue000", shape),
                bitmap("bitmap", "minecraft:font/padded.png", "\\ue001", shape)).getBytes(StandardCharsets.UTF_8));
        String spaced = " ".repeat(5 * 1024 * 1024) + font(bitmap("bitmap", "font/tile.png", "\\ue000", shape), bitmap("bitmap", "font/tile.png", "\\ue001", shape));
        files.put("assets/x/font/spaced.json", spaced.getBytes(StandardCharsets.UTF_8));

        Map<String, byte[]> merged = merge(files);

        assertEquals(2, merged.size(), "only default.json and its one sheet");
        JsonArray providers = JsonParser.parseString(new String(merged.get("assets/minecraft/font/default.json"), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("providers");
        assertEquals(2, providers.size(), "16 tiles fill the 16M-pixel budget; the 17th stays as it was");
        assertEquals(4, providers.get(0).getAsJsonObject().getAsJsonArray("chars").size());
        assertEquals(JsonParser.parseString(tiles.get(16)), providers.get(1));
    }

    @Test void decodesWhatTheClientDecodesAndRejectsTheRest() throws Exception {
        BufferedImage palette = FontGridMerger.decode(manual(1, 1, 8, 3, zlib(new byte[] {0, 0}),
                chunk("PLTE", new byte[] {(byte) 255, 0, 0}), chunk("tRNS", new byte[] {(byte) 128}), chunk("tEXt", new byte[] {'a', 0, 'b'})));
        assertEquals(0x80FF0000, palette.getRGB(0, 0));
        assertEquals(0xFF010203, FontGridMerger.decode(manual(1, 1, 8, 2, zlib(new byte[] {0, 1, 2, 3}))).getRGB(0, 0));

        byte[] rgba = zlib(new byte[] {0, 1, 2, 3, 4});
        byte[] badLength = manual(1, 1, 8, 6, rgba);
        ByteBuffer.wrap(badLength).putInt(33, -1);
        byte[] headerless = new byte[45];
        System.arraycopy(manual(1, 1, 8, 6, rgba), 0, headerless, 0, 8);
        System.arraycopy(chunk("IEND", new byte[0]), 0, headerless, 8, 12);
        byte[] signature = Arrays.copyOf(manual(1, 1, 8, 6, rgba), 8);
        byte[] header = chunk("IHDR", ByteBuffer.allocate(13).putInt(1).putInt(1).put((byte) 8).put((byte) 6).array());
        byte[] data = chunk("IDAT", rgba), end = chunk("IEND", new byte[0]);
        byte[] plte = chunk("PLTE", new byte[] {(byte) 255, 0, 0});
        java.util.function.Function<byte[][], byte[]> join = parts -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(signature);
            for (byte[] part : parts) out.writeBytes(part);
            return out.toByteArray();
        };
        byte[] paletteData = zlib(new byte[] {0, 0});
        for (byte[] rejected : List.of(
                join.apply(new byte[][] {header, chunk("ABCD", new byte[0]), data, end}),
                join.apply(new byte[][] {data, header, end}),
                join.apply(new byte[][] {header, header, data, end}),
                join.apply(new byte[][] {header, data, chunk("tRNS", new byte[1]), end}),
                join.apply(new byte[][] {header, data, plte, end}),
                manual(1, 1, 8, 3, paletteData, plte, plte),
                manual(1, 1, 8, 3, paletteData, plte, chunk("tRNS", new byte[1]), chunk("tRNS", new byte[1])),
                manual(1, 1, 8, 3, paletteData, chunk("tRNS", new byte[1]), plte),
                manual(1, 1, 8, 3, paletteData),
                manual(1, 1, 8, 3, paletteData, chunk("PLTE", new byte[4])),
                manual(1, 1, 8, 3, paletteData, chunk("PLTE", new byte[771])),
                manual(1, 1, 8, 3, paletteData, plte, chunk("tRNS", new byte[2])),
                withHeader(rgba, 10, 1),
                withHeader(rgba, 11, 1),
                withHeader(rgba, 12, 2),
                new byte[10],
                new byte[40],
                badLength,
                headerless,
                manual(1, 1, 8, 6, new byte[] {0x78}),
                manual(1, 1, 8, 0, zlib(new byte[] {0, 1})),
                manual(1, 1, 16, 6, zlib(new byte[9])),
                manual(1, 1, 8, 2, zlib(new byte[] {0, 1, 2, 3}), chunk("tRNS", new byte[6])),
                manual(0, 1, 8, 6, rgba),
                manual(5000, 1, 8, 6, rgba),
                manual(1, 1, 8, 6, new byte[] {0x78, (byte) 0x9C, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}),
                manual(1, 1, 8, 6, zlib(new byte[1 << 20])),
                manual(1, 1, 8, 6, zlib(new byte[] {0})))) {
            assertNull(FontGridMerger.decode(rejected));
        }
    }

    /** A 1x1 RGBA PNG with one IHDR field (10 compression, 11 filter, 12 interlace) changed. */
    private static byte[] withHeader(byte[] rgba, int field, int value) {
        byte[] png = manual(1, 1, 8, 6, rgba);
        png[16 + field] = (byte) value;
        byte[] fixed = chunk("IHDR", Arrays.copyOfRange(png, 16, 29));
        System.arraycopy(fixed, 0, png, 8, fixed.length);
        return png;
    }

    @Test void splitWritesMergedFontsAsReadableRecordsInTheFontsPart() throws Exception {
        String shape = ",\"ascent\":7";
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("pack.mcmeta", "{\"pack\":{\"pack_format\":69}}".getBytes(StandardCharsets.UTF_8));
        files.put("assets/minecraft/font/default.json", font(bitmap("bitmap", "font/a.png", "\\ue000", shape),
                bitmap("bitmap", "font/b.png", "\\ue001", shape)).getBytes(StandardCharsets.UTF_8));
        files.put("assets/minecraft/textures/font/a.png", png(image(8, 8, 1)));
        files.put("assets/minecraft/textures/font/b.png", protect(png(image(8, 8, 2))));
        files.put("assets/modelengine/models/creature.json", "{}".getBytes(StandardCharsets.UTF_8));
        files.put("assets/creature_sounds/sounds.json", "{}".getBytes(StandardCharsets.UTF_8));
        Path out = directory.resolve("parts");
        PackPartitioner.split(zip(files), out);
        Map<String, byte[]> core = new TreeMap<>();
        // ZipInputStream checks each record's CRC and sizes, unlike ZipFile and the client.
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(out.resolve("core.zip")))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null;) core.put(entry.getName(), in.readAllBytes());
        }
        JsonObject grid = JsonParser.parseString(new String(core.get("assets/minecraft/font/default.json"), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("providers").get(0).getAsJsonObject();
        assertEquals("", grid.getAsJsonArray("chars").get(0).getAsString());
        String sheet = "assets/minecraft/textures/" + grid.get("file").getAsString().substring("minecraft:".length());
        assertNotNull(ImageIO.read(new ByteArrayInputStream(core.get(sheet))));
        try (ZipFile zip = new ZipFile(out.resolve("core.zip").toFile())) {
            assertEquals(1, zip.stream().filter(entry -> entry.getName().equals("assets/minecraft/font/default.json")).count());
        }
    }
}
