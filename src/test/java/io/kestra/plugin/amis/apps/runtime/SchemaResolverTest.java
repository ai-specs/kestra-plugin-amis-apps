package io.kestra.plugin.amis.apps.runtime;

import io.kestra.plugin.amis.apps.domain.AmisSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SchemaResolverTest {
    @Test void p2SchemaDigestUsesActualBytesAndObservesIndependentFileUpdates() throws Exception {
        var bytes = new java.util.concurrent.atomic.AtomicReference<>("{ \"type\": \"page\", \"body\": [] }".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var resolver = new SchemaResolver((namespace, path) -> Optional.of(new java.io.ByteArrayInputStream(bytes.get())), 1048576, 64);
        var first = resolver.resolve("company.team", "nsfile:///home.json").orElseThrow();
        assertArrayEquals(bytes.get(), first.bytes());
        assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.get())), first.sha256());
        byte[] escaped = first.bytes(); escaped[0] = 0;
        assertEquals((byte) '{', first.bytes()[0]);
        bytes.set("{\"type\":\"page\",\"body\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var second = resolver.resolve("company.team", "nsfile:///home.json").orElseThrow();
        assertEquals(first.schema(), second.schema());
        assertNotEquals(first.sha256(), second.sha256());
    }

    @Test
    void currentFileWinsAndItsOriginAndFullSchemaAreReturned() throws Exception {
        List<String> calls = new ArrayList<>();
        var stream = new TrackingStream(bytes("{\"type\":\"page\",\"body\":{\"type\":\"tpl\",\"tpl\":\"{{ untouched }}\"}}"));
        var resolver = resolver((namespace, path) -> {
            calls.add(namespace + path);
            return Optional.of(stream);
        });
        var result = resolver.resolve("company.team.app", "nsfile:///pages/home.json").orElseThrow();
        assertEquals("company.team.app", result.namespace());
        assertEquals("/pages/home.json", result.path());
        assertEquals("{{ untouched }}", result.schema().path("body").path("tpl").textValue());
        assertEquals(List.of("company.team.app/pages/home.json"), calls);
        assertEquals(1, stream.closeCount);
    }

    @Test
    void onlyMissingFilesAllowOrderedParentFallback() throws Exception {
        List<String> calls = new ArrayList<>();
        var parent = page();
        var resolver = resolver((namespace, path) -> {
            calls.add(namespace + path);
            return namespace.equals("company.team") ? Optional.of(parent) : Optional.empty();
        });
        var result = resolver.resolve("company.team.app", "nsfile:///home.json").orElseThrow();
        assertEquals("company.team", result.namespace());
        assertEquals(List.of("company.team.app/home.json", "company.team/home.json"), calls);
        assertEquals(1, parent.closeCount);
    }

    @Test
    void missingEverywhereVisitsOnlyCurrentAndParentChainWithoutSiblingOrEmptyNamespace() throws Exception {
        List<String> calls = new ArrayList<>();
        var resolver = resolver((namespace, path) -> {
            calls.add(namespace);
            assertEquals("/home.json", path);
            return Optional.empty();
        });
        assertTrue(resolver.resolve("company.team.app", "nsfile:///home.json").isEmpty());
        assertEquals(List.of("company.team.app", "company.team", "company"), calls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{}", "{\"type\":\"form\"}", "{\"type\":[]}",
        "{\"type\":\"page\"} {}", "{\"type\":\"page\",\"type\":\"page\"}", "{"})
    void existingButInvalidCurrentFileNeverFallsBack(String json) {
        List<String> calls = new ArrayList<>();
        var stream = new TrackingStream(bytes(json));
        var resolver = resolver((namespace, path) -> {
            calls.add(namespace);
            return Optional.of(stream);
        });
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("company.team", "nsfile:///home.json"));
        assertEquals(List.of("company.team"), calls);
        assertEquals(1, stream.closeCount);
    }

    @Test
    void invalidParentStopsBeforeAnyMoreDistantAncestor() {
        List<String> calls = new ArrayList<>();
        var invalid = new TrackingStream(bytes("{\"type\":\"form\"}"));
        var resolver = resolver((namespace, path) -> {
            calls.add(namespace);
            return namespace.equals("company.team.app") ? Optional.empty() : Optional.of(invalid);
        });
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("company.team.app", "nsfile:///home.json"));
        assertEquals(List.of("company.team.app", "company.team"), calls);
        assertEquals(1, invalid.closeCount);
    }

    @Test
    void readFailureClosesOnceAndDoesNotQueryParent() {
        List<String> calls = new ArrayList<>();
        var stream = page();
        stream.readFailure = new IOException("read failed");
        var resolver = resolver((namespace, path) -> {
            calls.add(namespace);
            return Optional.of(stream);
        });
        assertSame(stream.readFailure, assertThrows(IOException.class,
            () -> resolver.resolve("company.team", "nsfile:///home.json")));
        assertEquals(1, stream.closeCount);
        assertEquals(List.of("company.team"), calls);
    }

    @Test
    void openFailuresIncludingFileNotFoundAreNotReinterpretedAsMissing() {
        for (IOException failure : List.of(new IOException("open failed"), new FileNotFoundException("reader failure"))) {
            List<String> calls = new ArrayList<>();
            var resolver = resolver((namespace, path) -> {
                calls.add(namespace);
                throw failure;
            });
            assertSame(failure, assertThrows(IOException.class, () -> resolver.resolve("company.team", "nsfile:///home.json")));
            assertEquals(List.of("company.team"), calls);
        }
    }

    @Test
    void closeFailureIsNotSwallowedOrRetriedAndDoesNotFallBack() {
        List<String> calls = new ArrayList<>();
        var stream = page();
        stream.closeFailure = new IOException("close failed");
        var resolver = resolver((namespace, path) -> {
            calls.add(namespace);
            return Optional.of(stream);
        });
        assertSame(stream.closeFailure, assertThrows(IOException.class,
            () -> resolver.resolve("company.team", "nsfile:///home.json")));
        assertEquals(1, stream.closeCount);
        assertEquals(List.of("company.team"), calls);
    }

    @Test
    void invalidSchemaKeepsItsPrimaryErrorAndSuppressesTheSingleCloseFailure() {
        var stream = new TrackingStream(bytes("{}"));
        stream.closeFailure = new IOException("close failed after invalid schema");
        var resolver = resolver((namespace, path) -> Optional.of(stream));
        var failure = assertThrows(IllegalArgumentException.class, () -> resolver.resolve("company", "nsfile:///home.json"));
        assertArrayEquals(new Throwable[] {stream.closeFailure}, failure.getSuppressed());
        assertEquals(1, stream.closeCount);
    }

    @Test
    void malformedUtf8ClosesOnceAndNeverFallsBack() {
        List<String> calls = new ArrayList<>();
        var stream = new TrackingStream(new byte[] {(byte) 0xc3, 0x28});
        var resolver = resolver((namespace, path) -> {
            calls.add(namespace);
            return Optional.of(stream);
        });
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("company.team", "nsfile:///home.json"));
        assertEquals(1, stream.closeCount);
        assertEquals(List.of("company.team"), calls);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"https://host/home.json", "nsfile://company.sibling/home.json", "nsfile:/home.json",
        "nsfile:////home.json", "nsfile:///../home.json", "nsfile:///a/./home.json", "nsfile:///%2e%2e/home.json",
        "nsfile:///%252e%252e/home.json", "nsfile:///a%2fhome.json", "nsfile:///a\\home.json", "nsfile:///a//home.json",
        "nsfile:///home.json?namespace=company.sibling", "nsfile:///home.json#other", "nsfile:///home.yaml"})
    void unsafeReferenceIsRejectedBeforeAnyReaderCall(String uri) {
        var resolver = resolver((namespace, path) -> {
            fail("Unsafe reference must not reach FileReader");
            return Optional.empty();
        });
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("company.team", uri));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Company", ".company", "company..team", "company/other", "company.*"})
    void invalidNamespaceIsRejectedBeforeAnyReaderCall(String currentNamespace) {
        var resolver = resolver((namespace, path) -> {
            fail("Invalid namespace must not reach FileReader");
            return Optional.empty();
        });
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(currentNamespace, "nsfile:///home.json"));
    }

    @Test
    void nullReaderResultIsAnErrorNotParentFallback() {
        List<String> calls = new ArrayList<>();
        var resolver = resolver((namespace, path) -> {
            calls.add(namespace);
            return null;
        });
        assertThrows(IOException.class, () -> resolver.resolve("company.team", "nsfile:///home.json"));
        assertEquals(List.of("company.team"), calls);
    }

    @Test
    void schemaCeilingsCannotBeRaisedAndConfiguredLimitsMustBeValid() {
        SchemaResolver.FileReader missing = (namespace, path) -> Optional.empty();
        assertThrows(IllegalArgumentException.class, () -> new SchemaResolver(null, 128, 4));
        assertThrows(IllegalArgumentException.class, () -> new SchemaResolver(missing, 0, 4));
        assertThrows(IllegalArgumentException.class, () -> new SchemaResolver(missing, -1, 4));
        assertThrows(IllegalArgumentException.class, () -> new SchemaResolver(missing, AmisSchema.MAX_BYTES + 1, 4));
        assertThrows(IllegalArgumentException.class, () -> new SchemaResolver(missing, 128, 0));
        assertThrows(IllegalArgumentException.class, () -> new SchemaResolver(missing, 128, -1));
        assertThrows(IllegalArgumentException.class, () -> new SchemaResolver(missing, 128, AmisSchema.MAX_DEPTH + 1));
    }

    @Test
    void oneMiBIsAcceptedExactlyAndOverflowReadsOnlyOneExtraByte() throws Exception {
        String prefix = "{\"type\":\"page\",\"body\":\"";
        String suffix = "\"}";
        var exact = new TrackingStream(bytes(prefix + "a".repeat(AmisSchema.MAX_BYTES - prefix.length() - suffix.length()) + suffix));
        assertTrue(resolver((namespace, path) -> Optional.of(exact)).resolve("company", "nsfile:///home.json").isPresent());
        assertEquals(AmisSchema.MAX_BYTES, exact.bytesRead);
        assertEquals(1, exact.closeCount);
        var excessive = new TrackingStream(new byte[AmisSchema.MAX_BYTES + 100]);
        List<String> calls = new ArrayList<>();
        var resolver = resolver((namespace, path) -> {
            calls.add(namespace);
            return Optional.of(excessive);
        });
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("company.team", "nsfile:///home.json"));
        assertEquals(AmisSchema.MAX_BYTES + 1, excessive.bytesRead);
        assertEquals(1, excessive.closeCount);
        assertEquals(List.of("company.team"), calls);
    }

    @Test
    void deploymentCanTightenByteAndDepthLimitsWithoutChangingGlobalConfiguration() {
        var oversized = page();
        var small = new SchemaResolver((namespace, path) -> Optional.of(oversized), 8, 4);
        assertThrows(IllegalArgumentException.class, () -> small.resolve("company", "nsfile:///home.json"));
        assertEquals(9, oversized.bytesRead);
        assertEquals(1, oversized.closeCount);
        var deep = new TrackingStream(bytes("{\"type\":\"page\",\"body\":{\"nested\":{}}}"));
        var shallow = new SchemaResolver((namespace, path) -> Optional.of(deep), 128, 2);
        assertThrows(IllegalArgumentException.class, () -> shallow.resolve("company", "nsfile:///home.json"));
        assertEquals(1, deep.closeCount);
    }

    @Test
    void amisDepthCeilingIsEnforcedAtTheExactBoundary() throws Exception {
        String prefix = "{\"type\":\"page\",\"body\":";
        var exact = new TrackingStream(bytes(prefix + "[".repeat(63) + "0" + "]".repeat(63) + "}"));
        assertTrue(resolver((namespace, path) -> Optional.of(exact)).resolve("company", "nsfile:///home.json").isPresent());
        assertEquals(1, exact.closeCount);
        var excessive = new TrackingStream(bytes(prefix + "[".repeat(64) + "0" + "]".repeat(64) + "}"));
        assertThrows(IllegalArgumentException.class, () -> resolver((namespace, path) -> Optional.of(excessive))
            .resolve("company", "nsfile:///home.json"));
        assertEquals(1, excessive.closeCount);
    }

    private static SchemaResolver resolver(SchemaResolver.FileReader reader) {
        return new SchemaResolver(reader, AmisSchema.MAX_BYTES, AmisSchema.MAX_DEPTH);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static TrackingStream page() {
        return new TrackingStream(bytes("{\"type\":\"page\"}"));
    }

    private static final class TrackingStream extends InputStream {
        private final ByteArrayInputStream delegate;
        private int bytesRead;
        private int closeCount;
        private IOException readFailure;
        private IOException closeFailure;

        private TrackingStream(byte[] bytes) {
            delegate = new ByteArrayInputStream(bytes);
        }

        @Override
        public int read() throws IOException {
            if (readFailure != null) {
                throw readFailure;
            }
            int value = delegate.read();
            if (value >= 0) {
                bytesRead++;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (readFailure != null) {
                throw readFailure;
            }
            int count = delegate.read(bytes, offset, length);
            if (count > 0) {
                bytesRead += count;
            }
            return count;
        }

        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeFailure != null) {
                throw closeFailure;
            }
            delegate.close();
        }
    }
}
