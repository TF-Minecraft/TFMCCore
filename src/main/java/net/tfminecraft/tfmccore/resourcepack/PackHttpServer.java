package net.tfminecraft.tfmccore.resourcepack;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;

/** Serves only published immutable pack parts, never arbitrary plugin files. */
public final class PackHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final Path root;

    public PackHttpServer(InetSocketAddress address, Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        server = HttpServer.create(address, 32);
        executor = new ThreadPoolExecutor(4, 8, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), runnable -> {
            Thread thread = new Thread(runnable, "TFMC-pack-download"); thread.setDaemon(true); return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1); return;
            }
            String path = exchange.getRequestURI().getRawPath();
            if (!path.matches("/[0-9a-f]{40}/(core|models|sounds)\\.zip")) {
                exchange.sendResponseHeaders(404, -1); return;
            }
            Path file = root.resolve(path.substring(1));
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(file.getParent())
                    || !Files.isRegularFile(file.getParent().resolve("manifest.properties"), LinkOption.NOFOLLOW_LINKS)) {
                exchange.sendResponseHeaders(404, -1); return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
            long size = Files.size(file);
            if (method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Content-Length", Long.toString(size));
                exchange.sendResponseHeaders(200, -1); return;
            }
            exchange.sendResponseHeaders(200, size);
            try (InputStream input = Files.newInputStream(file); OutputStream output = exchange.getResponseBody()) {
                input.transferTo(output);
            }
        }
    }

    public int port() { return server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
