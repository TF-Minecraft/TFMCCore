package net.tfminecraft.tfmccore.resourcepack;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Complete immutable generations; current changes only after every part is written. */
public final class PackPublisher {
    public record Bundle(String sourceHash, Path directory, Map<String, String> hashes) {
        public Bundle { hashes = Map.copyOf(hashes); }
    }
    private final Path root;
    public PackPublisher(Path root) { this.root = root; }

    public Bundle publish(Path source, String expectedHash) throws IOException {
        if (!expectedHash.matches("[0-9a-f]{40}")) throw new IOException("Invalid source hash");
        Files.createDirectories(root);
        Path staging = Files.createTempDirectory(root, ".building-");
        try {
            Path snapshot = staging.resolve("source.zip");
            Files.copy(source, snapshot);
            if (!PackPartitioner.sha1(snapshot).equals(expectedHash)) throw new IOException("Pack build is still changing");
            Map<String, String> hashes = PackPartitioner.split(snapshot, staging);
            Files.delete(snapshot);
            Properties manifest = new Properties();
            manifest.setProperty("source", expectedHash);
            hashes.forEach(manifest::setProperty);
            try (var out = Files.newOutputStream(staging.resolve("manifest.properties"))) { manifest.store(out, "TFMC pack generation"); }
            Path destination = root.resolve(expectedHash);
            if (Files.exists(destination)) {
                Properties existing = new Properties();
                try (var in = Files.newInputStream(destination.resolve("manifest.properties"))) { existing.load(in); }
                if (!expectedHash.equals(existing.getProperty("source"))) throw new IOException("Invalid published manifest");
                for (String part : PackPartitioner.PARTS) {
                    if (!hashes.get(part).equals(existing.getProperty(part))
                            || !PackPartitioner.sha1(destination.resolve(part + ".zip")).equals(hashes.get(part))) {
                        throw new IOException("Existing generation failed integrity check: " + expectedHash);
                    }
                }
            } else {
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
            }
            return new Bundle(expectedHash, destination, hashes);
        } finally {
            if (Files.exists(staging)) {
                try (var paths = Files.walk(staging)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }
}
