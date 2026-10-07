package net.tfminecraft.tfmccore.resourcepack;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultipartPackTest {
    @TempDir Path directory;
    private static final String METADATA = """
            {"pack":{"pack_format":69},"overlays":{"entries":[
            {"directory":"old","formats":[32,64],"min_format":32,"max_format":64},
            {"directory":"new","formats":[65,99],"min_format":65,"max_format":99}]}}
            """;
    private Map<String, String> resources() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("pack.mcmeta", METADATA);
        files.put("assets/minecraft/atlases/blocks.json", "atlas references modelengine textures");
        files.put("assets/modelengine/models/creature.json", "original model");
        files.put("old/assets/modelengine/models/creature.json", "legacy model");
        files.put("new/assets/modelengine/models/creature.json", "modern model");
        files.put("assets/creature_sounds/sounds.json", "sound definitions");
        files.put("new/assets/creature_sounds/sounds.json", "modern sounds");
        files.put("assets/minecraft/sounds/creature.ogg", "unchanged sound bytes");
        files.put("assets/mixed/sounds.json", "sounds with other resources");
        files.put("new/assets/mixed/textures/texture.png", "texture in overlay only");
        return files;
    }
    private Path zip(Map<String, String> files, boolean protectedZip) throws IOException {
        Path path = directory.resolve(UUID.randomUUID() + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : files.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey())); out.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            }
        }
        if (protectedZip) {
            byte[] bytes = Files.readAllBytes(path);
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int cursor = buffer.getInt(bytes.length - 6);
            while (buffer.getInt(cursor) == 0x02014b50) {
                int local = buffer.getInt(cursor + 42), length = Short.toUnsignedInt(buffer.getShort(local + 26));
                Arrays.fill(bytes, local + 30, local + 30 + length, (byte)'\\');
                buffer.putInt(cursor + 16, 0); // ItemsAdder deliberately masks these central fields.
                buffer.putInt(cursor + 24, 1337);
                cursor += 46 + Short.toUnsignedInt(buffer.getShort(cursor + 28))
                        + Short.toUnsignedInt(buffer.getShort(cursor + 30)) + Short.toUnsignedInt(buffer.getShort(cursor + 32));
            }
            Files.write(path, bytes);
        }
        return path;
    }
    private Map<String, String> read(Path path) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            for (var entry : zip.stream().toList()) {
                try (InputStream in = zip.getInputStream(entry)) { files.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
            }
        }
        return files;
    }
    @Test void partitionsProtectedRecordsWithoutChangingResourcesOrOverlayPrecedence() throws Exception {
        var original = resources(); Path source = zip(original, true), out = directory.resolve("parts");
        var hashes = PackPartitioner.split(source, out);
        Map<String, String> all = new TreeMap<>();
        for (String part : PackPartitioner.PARTS) {
            Path path = out.resolve(part + ".zip"); assertEquals(hashes.get(part), PackPartitioner.sha1(path));
            var files = read(path); assertEquals(METADATA, files.remove("pack.mcmeta"));
            for (String name : files.keySet()) assertFalse(all.containsKey(name), "resource appears in two parts");
            all.putAll(files);
            try (ZipFile z = new ZipFile(path.toFile())) { assertEquals(1337, z.getEntry("pack.mcmeta").getSize()); }
        }
        original.remove("pack.mcmeta"); assertEquals(original, all);
        var models = read(out.resolve("models.zip"));
        assertTrue(models.containsKey("old/assets/modelengine/models/creature.json"));
        assertTrue(models.containsKey("new/assets/modelengine/models/creature.json"));
        assertTrue(read(out.resolve("core.zip")).containsKey("assets/mixed/sounds.json"), "classify all overlays, not just base");
        assertEquals(Set.of("pack.mcmeta", "assets/creature_sounds/sounds.json", "new/assets/creature_sounds/sounds.json"), read(out.resolve("sounds.zip")).keySet());
    }
    @Test void ordinaryZipAlsoWorks() throws Exception {
        assertEquals(3, PackPartitioner.split(zip(resources(), false), directory.resolve("parts")).size());
    }
    @Test void rejectsCrossPackFilters() throws Exception {
        var files = resources(); files.put("pack.mcmeta", "{\"pack\":{},\"filter\":{\"block\":[]}}");
        assertThrows(IOException.class, () -> PackPartitioner.split(zip(files, false), directory.resolve("parts")));
    }
    @Test void rejectsUnsupportedPackWithoutPublishingPartialGeneration() throws Exception {
        var files = resources(); files.remove("pack.mcmeta"); Path source = zip(files, false);
        var publisher = new PackPublisher(directory.resolve("published"));
        assertThrows(IOException.class, () -> publisher.publish(source, PackPartitioner.sha1(source)));
        try (var list = Files.list(directory.resolve("published"))) { assertEquals(0, list.count()); }
    }
    @Test void rejectsTraversalNames() throws Exception {
        var files = resources(); files.put("../secret", "secret");
        assertThrows(IOException.class, () -> PackPartitioner.split(zip(files, false), directory.resolve("parts")));
    }
    @Test void rejectsTruncatedZip() throws Exception {
        Path source = zip(resources(), false); byte[] bytes = Files.readAllBytes(source);
        Files.write(source, Arrays.copyOf(bytes, bytes.length - 5));
        assertThrows(IOException.class, () -> PackPartitioner.split(source, directory.resolve("parts")));
    }
    @Test void rejectsUnsafeOrMalformedOverlayMetadataBeforeCreatingParts() throws Exception {
        for (String metadata : List.of(METADATA.replace("\"old\"", "\"../old\""), "{invalid", "[]")) {
            var files = resources();
            files.put("pack.mcmeta", metadata);
            Path source = zip(files, false), output = directory.resolve(UUID.randomUUID().toString());
            IOException error = assertThrows(IOException.class, () -> PackPartitioner.split(source, output));
            assertTrue(error.getMessage().contains("metadata") || error.getMessage().contains("overlay directory"));
            assertFalse(Files.exists(output));
        }
    }
    @Test void requiresBothModelAndSoundNamespaceGroups() throws Exception {
        for (String omitted : List.of("/modelengine/", "/creature_sounds/")) {
            var files = resources();
            files.keySet().removeIf(path -> path.contains(omitted));
            Path source = zip(files, false), output = directory.resolve(UUID.randomUUID().toString());
            IOException error = assertThrows(IOException.class, () -> PackPartitioner.split(source, output));
            assertEquals("Pack does not contain the three expected namespace groups", error.getMessage());
            assertFalse(Files.exists(output));
        }
    }
    @Test void rejectsInconsistentDirectoryCountsAndEncryptedRecords() throws Exception {
        Path countSource = zip(resources(), false);
        byte[] bytes = Files.readAllBytes(countSource);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort(bytes.length - 14, (short) 0);
        Files.write(countSource, bytes);
        assertEquals("ZIP64 or inconsistent ZIP directory", assertThrows(IOException.class,
                () -> PackPartitioner.split(countSource, directory.resolve("bad-count"))).getMessage());

        Path encryptedSource = zip(resources(), false);
        bytes = Files.readAllBytes(encryptedSource);
        buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int central = buffer.getInt(bytes.length - 6);
        buffer.putShort(central + 8, (short) (buffer.getShort(central + 8) | 1));
        Files.write(encryptedSource, bytes);
        assertEquals("Unsupported local record", assertThrows(IOException.class,
                () -> PackPartitioner.split(encryptedSource, directory.resolve("encrypted"))).getMessage());
        assertFalse(Files.exists(directory.resolve("bad-count")));
        assertFalse(Files.exists(directory.resolve("encrypted")));
    }
    @Test void truncatedCentralFieldsReportAnIoFailureInsteadOfIndexErrors() throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x02014b50).putInt(0x06054b50).putShort((short) 0).putShort((short) 0)
                .putShort((short) 1).putShort((short) 1).putInt(4).putInt(0).putShort((short) 0);
        Path source = directory.resolve("truncated-central.zip");
        Files.write(source, buffer.array());
        IOException failure = assertThrows(IOException.class,
                () -> PackPartitioner.split(source, directory.resolve("truncated")));
        assertEquals("Truncated ZIP", failure.getMessage());
        assertInstanceOf(IndexOutOfBoundsException.class, failure.getCause());
        assertFalse(Files.exists(directory.resolve("truncated")));
    }
    @Test void missingRequiredDigestProviderIsReportedAsAnInvariantFailure() throws Exception {
        var unavailable = new java.security.NoSuchAlgorithmException("SHA-1 provider missing");
        try (var algorithms = org.mockito.Mockito.mockStatic(java.security.MessageDigest.class)) {
            algorithms.when(() -> java.security.MessageDigest.getInstance("SHA-1")).thenThrow(unavailable);
            assertSame(unavailable, assertThrows(AssertionError.class,
                    () -> PackPartitioner.sha1(directory.resolve("unused.zip"))).getCause());
        }
    }
    @Test void generationIsImmutableAndRetryIsIdempotent() throws Exception {
        Path source = zip(resources(), true); String hash = PackPartitioner.sha1(source);
        var publisher = new PackPublisher(directory.resolve("published"));
        var first = publisher.publish(source, hash); var second = publisher.publish(source, hash);
        assertEquals(first, second); assertTrue(Files.exists(first.directory().resolve("manifest.properties")));
        var changed = resources(); changed.put("assets/modelengine/models/creature.json", "updated");
        Path other = zip(changed, true); var next = publisher.publish(other, PackPartitioner.sha1(other));
        assertNotEquals(first.sourceHash(), next.sourceHash());
        assertEquals("original model", read(first.directory().resolve("models.zip")).get("assets/modelengine/models/creature.json"));
    }
    @Test void hashMismatchKeepsPreviousGenerationAndCleansStaging() throws Exception {
        Path source = zip(resources(), false); var publisher = new PackPublisher(directory.resolve("published"));
        var good = publisher.publish(source, PackPartitioner.sha1(source));
        assertThrows(IOException.class, () -> publisher.publish(source, "0".repeat(40)));
        assertTrue(Files.exists(good.directory().resolve("core.zip")));
        try (var list = Files.list(directory.resolve("published"))) { assertEquals(1, list.count()); }
    }
    @Test void corruptedPublishedPartCannotBeReused() throws Exception {
        Path source = zip(resources(), false); var publisher = new PackPublisher(directory.resolve("published"));
        var good = publisher.publish(source, PackPartitioner.sha1(source));
        Files.writeString(good.directory().resolve("models.zip"), "broken");
        assertThrows(IOException.class, () -> publisher.publish(source, good.sourceHash()));
    }
    @Test void httpServesPublishedBytesAndRejectsOtherFiles() throws Exception {
        Path root = directory.resolve("published"), source = zip(resources(), true);
        var bundle = new PackPublisher(root).publish(source, PackPartitioner.sha1(source));
        try (var http = new PackHttpServer(new InetSocketAddress("127.0.0.1", 0), root)) {
            var client = HttpClient.newHttpClient(); String base = "http://127.0.0.1:" + http.port();
            URI url = URI.create(base + "/" + bundle.sourceHash() + "/models.zip");
            var get = client.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, get.statusCode()); assertArrayEquals(Files.readAllBytes(bundle.directory().resolve("models.zip")), get.body());
            var head = client.send(HttpRequest.newBuilder(url).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, head.statusCode()); assertEquals(0, head.body().length);
            assertEquals(get.body().length, Long.parseLong(head.headers().firstValue("Content-Length").orElseThrow()));
            for (String path : List.of("/../config.yml", "/%2e%2e/config.yml", "/" + bundle.sourceHash() + "/manifest.properties", "/" + "0".repeat(40) + "/core.zip")) {
                assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            }
            assertEquals(405, client.send(HttpRequest.newBuilder(url).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }
    @Test void completionRequiresEveryPartAndIgnoresDuplicatesAndUnknownIds() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        var tracker = new PackLoadTracker(Set.of(a, b, c));
        assertEquals(PackLoadTracker.Result.IGNORED, tracker.accept(UUID.randomUUID(), true, false));
        assertEquals(PackLoadTracker.Result.WAITING, tracker.accept(a, true, false));
        assertEquals(PackLoadTracker.Result.WAITING, tracker.accept(a, true, false));
        assertEquals(PackLoadTracker.Result.WAITING, tracker.accept(c, true, false));
        assertEquals(PackLoadTracker.Result.COMPLETE, tracker.accept(b, true, false));
        assertEquals(PackLoadTracker.Result.IGNORED, tracker.accept(a, false, true));
    }
    @Test void failedPartEmitsOneTerminalFailure() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(); var tracker = new PackLoadTracker(Set.of(a, b));
        assertEquals(PackLoadTracker.Result.WAITING, tracker.accept(a, true, false));
        assertEquals(PackLoadTracker.Result.FAILED, tracker.accept(b, false, true));
        assertEquals(PackLoadTracker.Result.IGNORED, tracker.accept(b, true, false));
    }
}
