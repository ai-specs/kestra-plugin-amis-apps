package io.kestra.plugin.amis.apps.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeAssetsTest {
    private static final String ROOT = "META-INF/kestra/amis-apps/runtime/";
    private static final byte[] TEMPLATE = "__AMIS_BASE__ __AMIS_CSRF__ __AMIS_BOOTSTRAP__".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SCRIPT = "export const marker = 'real';".getBytes(StandardCharsets.UTF_8);

    @Test void servesOnlyManifestAssetsAndKeepsTemplatePrivate() throws Exception {
        var files = files();
        var assets = runtime(files);
        assertEquals(new String(TEMPLATE, StandardCharsets.UTF_8), assets.entryTemplate());
        assertArrayEquals(SCRIPT, assets.asset("assets/app-123.js").orElseThrow().bytes());
        for (String path : new String[]{"index.html", "runtime-manifest.json", "missing.js", "../secret", "/assets/app-123.js",
            "assets/%2e%2e/secret", "assets\\secret", "assets//app-123.js", "assets/app-123.js?x", "assets/app-123.js#x"}) {
            assertTrue(assets.asset(path).isEmpty(), path);
        }
    }

    @Test void rejectsMissingManifestEntryAssetsAndTamperedBytes() throws Exception {
        assertThrows(IllegalStateException.class, () -> runtime(Map.of()));
        var files = files();
        var assets = runtime(files);
        files.remove(ROOT + "assets/app-123.js");
        assertThrows(IllegalStateException.class, () -> assets.asset("assets/app-123.js"));
        files.put(ROOT + "assets/app-123.js", new byte[]{1});
        assertThrows(IllegalStateException.class, () -> assets.asset("assets/app-123.js"));
        files.put(ROOT + "index.html", new byte[]{2});
        assertThrows(IllegalStateException.class, assets::entryTemplate);
    }

    @Test void rejectsUnsafeManifestKeysContentTypesAndShape() throws Exception {
        for (String path : new String[]{"../secret", "/secret", "assets/%2fsecret", "runtime-manifest.json"}) {
            var files = files();
            files.put(ROOT + "runtime-manifest.json", manifest(path, "text/javascript; charset=utf-8"));
            assertThrows(IllegalStateException.class, () -> runtime(files), path);
        }
        var files = files();
        files.put(ROOT + "runtime-manifest.json", manifest("assets/app-123.js", "text/html\r\nX-Injected: true"));
        assertThrows(IllegalStateException.class, () -> runtime(files));
        files.put(ROOT + "runtime-manifest.json", "{\"version\":1,\"entry\":\"index.html\",\"assets\":{}}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> runtime(files));
        files.put(ROOT + "runtime-manifest.json", new String(manifest("assets/app-123.js", "text/javascript; charset=utf-8"),
            StandardCharsets.UTF_8).replace("\"version\":1", "\"version\":\"1\"").getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> runtime(files));
    }

    @Test void returnedBytesCannotMutateLaterResponses() throws Exception {
        var assets = runtime(files());
        var first = assets.asset("assets/app-123.js").orElseThrow();
        first.bytes()[0] = 0;
        assertArrayEquals(SCRIPT, first.bytes());
        assertArrayEquals(SCRIPT, assets.asset("assets/app-123.js").orElseThrow().bytes());
    }

    @Test void closesManifestAndAssetStreams() throws Exception {
        var files = files();
        var closed = new java.util.concurrent.atomic.AtomicInteger();
        var assets = new RuntimeAssets(path -> {
            byte[] bytes = files.get(path);
            return bytes == null ? null : new ByteArrayInputStream(bytes) {
                @Override public void close() { closed.incrementAndGet(); }
            };
        });
        assets.entryTemplate();
        assets.asset("assets/app-123.js");
        assertEquals(3, closed.get());
    }

    private static RuntimeAssets runtime(Map<String, byte[]> files) {
        return new RuntimeAssets(path -> {
            byte[] bytes = files.get(path);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        });
    }

    private static Map<String, byte[]> files() throws Exception {
        var files = new HashMap<String, byte[]>();
        files.put(ROOT + "runtime-manifest.json", manifest("assets/app-123.js", "text/javascript; charset=utf-8"));
        files.put(ROOT + "index.html", TEMPLATE);
        files.put(ROOT + "assets/app-123.js", SCRIPT);
        return files;
    }

    private static byte[] manifest(String path, String contentType) throws Exception {
        return new ObjectMapper().writeValueAsBytes(Map.of("version", 1, "entry", "index.html", "assets", Map.of(
            "index.html", Map.of("sha256", hash(TEMPLATE), "contentType", "text/html; charset=utf-8"),
            path, Map.of("sha256", hash(SCRIPT), "contentType", contentType))));
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
