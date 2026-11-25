package io.kestra.plugin.amis.apps.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

public final class RuntimeAssets {
    private static final String ROOT = "META-INF/kestra/amis-apps/runtime/";
    private static final int MAX_ASSET_BYTES = 32 * 1024 * 1024;
    private static final Set<String> CONTENT_TYPES = Set.of(
        "text/html; charset=utf-8", "text/javascript; charset=utf-8", "text/css; charset=utf-8",
        "application/json; charset=utf-8", "font/woff", "font/woff2", "font/ttf", "font/otf",
        "application/vnd.ms-fontobject", "font/vnd.ms-fontobject", "image/svg+xml", "image/png", "image/jpeg", "image/gif",
        "image/webp", "image/x-icon", "application/octet-stream");
    private final Function<String, InputStream> resources;
    private final Map<String, Descriptor> manifest;

    public RuntimeAssets() {
        this(path -> RuntimeAssets.class.getClassLoader().getResourceAsStream(path));
    }

    RuntimeAssets(Function<String, InputStream> resources) {
        this.resources = resources;
        try (InputStream input = resources.apply(ROOT + "runtime-manifest.json")) {
            if (input == null) throw new IllegalStateException("AMIS runtime manifest is missing");
            JsonNode document = new JsonInputParser(1024 * 1024, 8).parse(input);
            if (document.size() != 3 || !document.path("version").isIntegralNumber()
                || document.path("version").intValue() != 1
                || !document.path("entry").asText().equals("index.html")
                || !document.path("assets").isObject() || document.path("assets").isEmpty()
                || document.path("assets").size() > 2048) {
                throw new IllegalStateException("AMIS runtime manifest is invalid");
            }
            var entries = new HashMap<String, Descriptor>();
            document.path("assets").properties().forEach(entry -> {
                String path = entry.getKey();
                JsonNode descriptor = entry.getValue();
                String hash = descriptor.path("sha256").asText();
                String contentType = descriptor.path("contentType").asText();
                if (!safePath(path) || !descriptor.isObject() || descriptor.size() != 2
                    || !hash.matches("[a-f0-9]{64}") || !CONTENT_TYPES.contains(contentType)) {
                    throw new IllegalStateException("AMIS runtime asset descriptor is invalid");
                }
                entries.put(path, new Descriptor(hash, contentType));
            });
            if (!entries.containsKey("index.html")
                || !entries.get("index.html").contentType().equals("text/html; charset=utf-8")) {
                throw new IllegalStateException("AMIS runtime entry is missing");
            }
            manifest = Map.copyOf(entries);
        } catch (IOException exception) {
            throw new IllegalStateException("AMIS runtime manifest cannot be read", exception);
        }
    }

    public String entryTemplate() {
        Asset entry = load("index.html").orElseThrow();
        String template = new String(entry.bytes(), StandardCharsets.UTF_8);
        for (String placeholder : Set.of("__AMIS_BASE__", "__AMIS_CSRF__", "__AMIS_BOOTSTRAP__")) {
            if (!template.contains(placeholder)) throw new IllegalStateException("AMIS runtime template is incomplete");
        }
        return template;
    }

    public Optional<Asset> asset(String path) {
        if (!safePath(path) || path.equals("index.html")) return Optional.empty();
        return load(path);
    }

    private Optional<Asset> load(String path) {
        Descriptor descriptor = manifest.get(path);
        if (descriptor == null) return Optional.empty();
        try (InputStream input = resources.apply(ROOT + path)) {
            if (input == null) throw new IllegalStateException("AMIS runtime asset is missing");
            byte[] bytes = input.readNBytes(MAX_ASSET_BYTES + 1);
            if (bytes.length > MAX_ASSET_BYTES || !sha256(bytes).equals(descriptor.sha256())) {
                throw new IllegalStateException("AMIS runtime asset integrity check failed");
            }
            return Optional.of(new Asset(bytes, descriptor.contentType(), descriptor.sha256()));
        } catch (IOException exception) {
            throw new IllegalStateException("AMIS runtime asset cannot be read", exception);
        }
    }

    private static boolean safePath(String path) {
        if (path == null || path.length() > 512 || !path.matches("[A-Za-z0-9_/-]+(?:[.][A-Za-z0-9_-]+)*")) return false;
        return !path.startsWith("/") && !path.endsWith("/") && !path.contains("//")
            && !path.contains("/.") && !path.startsWith(".") && !path.equals("runtime-manifest.json");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Descriptor(String sha256, String contentType) { }

    public record Asset(byte[] bytes, String contentType, String sha256) {
        public Asset { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
