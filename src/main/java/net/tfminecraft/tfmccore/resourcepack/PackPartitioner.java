package net.tfminecraft.tfmccore.resourcepack;

import com.google.gson.JsonParser;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/**
 * Partitions namespaces without rewriting ItemsAdder's protected local ZIP records. Only merged
 * font files and their sheets are written as new records.
 */
public final class PackPartitioner {
    static final List<String> CORE_PARTS = List.of("core", "core2", "core3");
    public static final List<String> PARTS = List.of("core", "core2", "core3", "models", "sounds");
    private static final int MAX_BYTES = 256 * 1024 * 1024;
    /** A record copied from {@code data}: the source pack, or a new record's own bytes. */
    private record Entry(String name, byte[] data, int central, int centralLength, int local, int localLength) {}
    private PackPartitioner() {}

    public static Map<String, String> split(Path source, Path destination) throws IOException {
        if (Files.size(source) > MAX_BYTES) throw new IOException("Pack exceeds 256 MiB");
        byte[] bytes = Files.readAllBytes(source);
        List<Entry> entries = entries(bytes);
        Set<String> overlays = new HashSet<>();
        // Java's ZipFile reads protected central-directory names, just like Minecraft.
        try (ZipFile zip = new ZipFile(source.toFile())) {
            var metadata = zip.getEntry("pack.mcmeta");
            if (metadata == null) throw new IOException("Missing pack.mcmeta");
            try (InputStream stream = zip.getInputStream(metadata)) {
                byte[] text = stream.readNBytes(1024 * 1024 + 1);
                if (text.length > 1024 * 1024) throw new IOException("Oversized pack metadata");
                var json = JsonParser.parseString(new String(text, StandardCharsets.UTF_8)).getAsJsonObject();
                // Filters cross pack boundaries, so splitting such a pack can change precedence.
                if (json.has("filter")) throw new IOException("Pack filters cannot be partitioned safely");
                if (json.has("overlays")) {
                    for (var element : json.getAsJsonObject("overlays").getAsJsonArray("entries")) {
                        String directory = element.getAsJsonObject().get("directory").getAsString();
                        if (!directory.matches("[a-zA-Z0-9_.-]+") || directory.equals("..")) {
                            throw new IOException("Unsupported overlay directory");
                        }
                        overlays.add(directory);
                    }
                }
            }
        } catch (RuntimeException error) {
            throw new IOException("Invalid pack metadata", error);
        }
        Map<String, byte[]> rewritten;
        try (ZipFile zip = new ZipFile(source.toFile())) { rewritten = FontGridMerger.merge(zip, overlays); }
        entries.removeIf(entry -> rewritten.containsKey(entry.name));
        rewritten.forEach((name, content) -> entries.add(created(name, content)));
        Map<String, Set<String>> namespaces = new HashMap<>();
        for (Entry entry : entries) {
            String resource = resource(entry.name, overlays);
            if (resource != null) {
                String[] pieces = resource.split("/", 3);
                namespaces.computeIfAbsent(pieces[1], ignored -> new HashSet<>()).add(pieces[2]);
            }
        }
        Set<String> soundOnly = new HashSet<>();
        namespaces.forEach((namespace, paths) -> {
            if (paths.equals(Set.of("sounds.json"))) soundOnly.add(namespace);
        });
        if (!namespaces.containsKey("modelengine") || soundOnly.isEmpty()) {
            throw new IOException("Pack does not contain the three expected namespace groups");
        }
        Map<String, String> partOf = new HashMap<>(Map.of("", "core", "modelengine", "models"));
        soundOnly.forEach(namespace -> partOf.put(namespace, "sounds"));
        Map<String, Integer> coreSizes = new HashMap<>();
        for (Entry entry : entries) {
            String namespace = namespace(entry.name, overlays);
            if (!partOf.containsKey(namespace)) coreSizes.merge(namespace, 1, Integer::sum);
        }
        List<List<String>> runs = balance(coreSizes, CORE_PARTS.size());
        for (int i = 0; i < runs.size(); i++) {
            for (String namespace : runs.get(i)) partOf.put(namespace, CORE_PARTS.get(i));
        }
        Map<String, List<Entry>> groups = new LinkedHashMap<>();
        PARTS.forEach(part -> groups.put(part, new ArrayList<>()));
        for (Entry entry : entries) {
            if (entry.name.equals("pack.mcmeta") || entry.name.equals("pack.png")) {
                groups.values().forEach(group -> group.add(entry));
                continue;
            }
            groups.get(partOf.get(namespace(entry.name, overlays))).add(entry);
        }
        Files.createDirectories(destination);
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String part : PARTS) {
            Path output = destination.resolve(part + ".zip");
            write(groups.get(part), output);
            hashes.put(part, sha1(output));
        }
        return Collections.unmodifiableMap(hashes);
    }

    /**
     * Splits namespaces, largest first, into contiguous runs that minimise namespaces x entries per
     * part. A 1.21 client reads every entry of a pack once per namespace it holds whenever it lists
     * resources, so one pack with every small namespace and every large one is the slowest layout.
     */
    static List<List<String>> balance(Map<String, Integer> sizes, int parts) {
        List<String> order = new ArrayList<>(sizes.keySet());
        order.sort(Comparator.comparing((String namespace) -> -sizes.get(namespace)).thenComparing(namespace -> namespace));
        int count = order.size(), used = Math.min(parts, count);
        long[] prefix = new long[count + 1];
        for (int i = 0; i < count; i++) prefix[i + 1] = prefix[i] + sizes.get(order.get(i));
        long[][] cost = new long[used + 1][count + 1];
        int[][] cut = new int[used + 1][count + 1];
        for (long[] row : cost) Arrays.fill(row, Long.MAX_VALUE);
        cost[0][0] = 0;
        for (int part = 1; part <= used; part++) {
            for (int end = part; end <= count; end++) {
                for (int start = part - 1; start < end; start++) {
                    if (cost[part - 1][start] == Long.MAX_VALUE) continue;
                    long total = cost[part - 1][start] + (end - start) * (prefix[end] - prefix[start]);
                    if (total < cost[part][end]) { cost[part][end] = total; cut[part][end] = start; }
                }
            }
        }
        List<List<String>> runs = new ArrayList<>();
        for (int part = used, end = count; part > 0; end = cut[part][end], part--) {
            runs.add(0, order.subList(cut[part][end], end));
        }
        while (runs.size() < parts) runs.add(List.of());
        return runs;
    }

    private static String namespace(String name, Set<String> overlays) throws IOException {
        String resource = resource(name, overlays);
        return resource == null ? "" : resource.split("/", 3)[1];
    }

    private static String resource(String name, Set<String> overlays) throws IOException {
        int slash = name.indexOf('/');
        String relative = slash >= 0 && overlays.contains(name.substring(0, slash)) ? name.substring(slash + 1) : name;
        if (!relative.startsWith("assets/") && !relative.startsWith("data/")) return null;
        if (relative.endsWith("/")) return null;
        if (relative.split("/", 3).length != 3) throw new IOException("Invalid resource path");
        return relative;
    }

    private static List<Entry> entries(byte[] bytes) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int end = -1;
        for (int i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--) {
            if (b.getInt(i) == 0x06054b50 && i + 22 + u16(b, i + 20) == bytes.length) { end = i; break; }
        }
        if (end < 0 || u16(b, end + 4) != 0 || u16(b, end + 6) != 0) throw new IOException("Unsupported ZIP directory");
        int count = u16(b, end + 10), offset = b.getInt(end + 16), directorySize = b.getInt(end + 12);
        if (count == 65535 || count != u16(b, end + 8) || offset < 0 || directorySize < 0 || (long)offset + directorySize != end) {
            throw new IOException("ZIP64 or inconsistent ZIP directory");
        }
        List<Entry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int cursor = offset;
        try {
            for (int i = 0; i < count; i++) {
                if (b.getInt(cursor) != 0x02014b50) throw new IOException("Invalid central record");
                int nameLength = u16(b, cursor + 28), centralLength = 46 + nameLength + u16(b, cursor + 30) + u16(b, cursor + 32);
                if ((long)cursor + centralLength > end) throw new IOException("Truncated central record");
                String name = new String(bytes, cursor + 46, nameLength, StandardCharsets.UTF_8);
                if (!names.add(name) || name.startsWith("/") || name.contains("\\") || Arrays.asList(name.split("/")).contains("..")) {
                    throw new IOException("Duplicate or unsafe central path");
                }
                int local = b.getInt(cursor + 42), compressed = b.getInt(cursor + 20), flags = u16(b, cursor + 8);
                if (local < 0 || compressed < 0 || local >= offset || (flags & 1) != 0 || b.getInt(local) != 0x04034b50) {
                    throw new IOException("Unsupported local record");
                }
                long dataEnd = (long)local + 30 + u16(b, local + 26) + u16(b, local + 28) + compressed;
                if (dataEnd > offset) throw new IOException("Truncated entry");
                int localEnd = (int)dataEnd;
                if ((flags & 8) != 0) {
                    boolean signature = b.getInt(localEnd) == 0x08074b50;
                    int descriptor = localEnd + (signature ? 4 : 0);
                    if (b.getInt(descriptor + 4) != compressed) throw new IOException("Inconsistent descriptor");
                    localEnd += signature ? 16 : 12;
                }
                if (localEnd > offset) throw new IOException("Truncated descriptor");
                entries.add(new Entry(name, bytes, cursor, centralLength, local, localEnd - local));
                cursor += centralLength;
            }
        } catch (IndexOutOfBoundsException error) {
            throw new IOException("Truncated ZIP", error);
        }
        if (cursor != end) throw new IOException("Unexpected directory data");
        return entries;
    }

    private static int u16(ByteBuffer b, int offset) { return Short.toUnsignedInt(b.getShort(offset)); }

    /** A plain deflated record: local header and data, then its central header. */
    private static Entry created(String name, byte[] content) {
        byte[] path = name.getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(content);
        deflater.finish();
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        while (!deflater.finished()) packed.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        CRC32 crc = new CRC32();
        crc.update(content);
        int localLength = 30 + path.length + packed.size(), centralLength = 46 + path.length;
        ByteBuffer record = ByteBuffer.allocate(localLength + centralLength).order(ByteOrder.LITTLE_ENDIAN);
        record.putInt(0x04034b50).putShort((short)20).putShort((short)0x800).putShort((short)8).putInt(0)
                .putInt((int)crc.getValue()).putInt(packed.size()).putInt(content.length)
                .putShort((short)path.length).putShort((short)0).put(path).put(packed.toByteArray());
        record.putInt(0x02014b50).putShort((short)20).putShort((short)20).putShort((short)0x800).putShort((short)8).putInt(0)
                .putInt((int)crc.getValue()).putInt(packed.size()).putInt(content.length)
                .putShort((short)path.length).putLong(0).putInt(0).putInt(0).put(path);
        return new Entry(name, record.array(), localLength, centralLength, 0, localLength);
    }

    private static void write(List<Entry> entries, Path output) throws IOException {
        ByteArrayOutputStream directory = new ByteArrayOutputStream();
        int position = 0;
        try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(output))) {
            for (Entry entry : entries) {
                byte[] central = Arrays.copyOfRange(entry.data, entry.central, entry.central + entry.centralLength);
                ByteBuffer.wrap(central).order(ByteOrder.LITTLE_ENDIAN).putInt(42, position);
                directory.write(central);
                stream.write(entry.data, entry.local, entry.localLength);
                position += entry.localLength;
            }
            directory.writeTo(stream);
            var end = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
            end.putInt(0x06054b50).putShort((short)0).putShort((short)0).putShort((short)entries.size())
                    .putShort((short)entries.size()).putInt(directory.size()).putInt(position).putShort((short)0);
            stream.write(end.array());
        }
    }

    public static String sha1(Path path) throws IOException {
        MessageDigest digest = sha1();
        try (InputStream stream = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int length; (length = stream.read(buffer)) >= 0;) digest.update(buffer, 0, length);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String sha1(byte[] bytes) { return HexFormat.of().formatHex(sha1().digest(bytes)); }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
