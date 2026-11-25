package io.kestra.plugin.amis.apps.runtime;

import io.kestra.core.storages.Namespace;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PublishedNamespaceFilesTest {
    @Test
    void usesNamespaceAndCanonicalPathWithoutTurningNamespaceIntoTenant() throws Exception {
        List<String> namespaces = new ArrayList<>();
        TrackingNamespace target = new TrackingNamespace(true, bytes("schema"));
        var files = new PublishedNamespaceFiles(namespace -> {
            namespaces.add(namespace);
            return target;
        });
        try (InputStream stream = files.open("company.demo", "/pages/home.json").orElseThrow()) {
            assertEquals("schema", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(List.of("company.demo"), namespaces);
        assertEquals(Path.of("/pages/home.json"), target.requestedPath);
        assertEquals(1, target.getCount);
        assertEquals(1, target.closeCount);
    }

    @Test
    void realNotFoundIsTheOnlyEmptyResult() throws Exception {
        TrackingNamespace target = new TrackingNamespace(false, new byte[0]);
        var files = new PublishedNamespaceFiles(namespace -> target);
        assertTrue(files.open("company.demo", "/pages/missing.json").isEmpty());
        assertEquals(0, target.getCount);
    }

    @Test
    void ordinaryDoubleDotsInsideAFileNameRemainReadable() throws Exception {
        TrackingNamespace target = new TrackingNamespace(true, bytes("double-dot file"));
        var files = new PublishedNamespaceFiles(namespace -> target);
        try (InputStream stream = files.open("company.demo", "/pages/app..json").orElseThrow()) {
            assertEquals("double-dot file", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(Path.of("/pages/app..json"), target.requestedPath);
        assertEquals(1, target.getCount);
        assertEquals(1, target.closeCount);
    }

    @Test
    void backendSqlOrIoErrorsDoNotBecomeParentFallback() {
        IOException failure = new IOException("backend failure");
        var files = new PublishedNamespaceFiles(namespace -> new TrackingNamespace(false, new byte[0]) {
            @Override
            public boolean exists(Path path) throws IOException {
                throw failure;
            }
        });
        assertSame(failure, assertThrows(IOException.class, () -> files.open("company.demo", "/pages/home.json")));
    }

    @Test
    void contentReadFailureIsPropagated() {
        IOException failure = new IOException("content failure");
        var files = new PublishedNamespaceFiles(namespace -> new TrackingNamespace(true, new byte[0]) {
            @Override
            public InputStream getFileContent(Path path) throws IOException {
                throw failure;
            }
        });
        assertSame(failure, assertThrows(IOException.class, () -> files.open("company.demo", "/pages/home.json")));
    }

    @Test
    void unsafeCandidatesAreRejectedBeforeNamespaceAccess() {
        var files = new PublishedNamespaceFiles(namespace -> fail("unsafe candidate reached storage"));
        for (String namespace : List.of("", ".company", "company/other", "company..other", "company\\other")) {
            assertThrows(IllegalArgumentException.class, () -> files.open(namespace, "/pages/home.json"), namespace);
        }
        for (String path : List.of("", "pages/home.json", "/pages/../home.json", "/pages/./home.json", "/pages\\home.json",
            "/pages//home.json", "/pages/%2e%2e/home.json")) {
            assertThrows(IllegalArgumentException.class, () -> files.open("company.demo", path), path);
        }
    }

    @Test
    void nullProviderAndNullNamespaceAreNotSilentNotFound() {
        assertThrows(NullPointerException.class, () -> new PublishedNamespaceFiles((PublishedNamespaceFiles.NamespaceProvider) null));
        var files = new PublishedNamespaceFiles(namespace -> null);
        assertThrows(NullPointerException.class, () -> files.open("company.demo", "/pages/home.json"));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static class TrackingNamespace implements Namespace {
        private final boolean exists;
        private final byte[] content;
        private Path requestedPath;
        private int getCount;
        private int closeCount;

        private TrackingNamespace(boolean exists, byte[] content) {
            this.exists = exists;
            this.content = content;
        }

        @Override public String namespace() { return "company.demo"; }
        @Override public String tenantId() { return null; }
        @Override public List<io.kestra.core.storages.NamespaceFile> all() { return List.of(); }
        @Override public List<io.kestra.core.storages.NamespaceFile> all(String prefix, boolean recursive) { return List.of(); }
        @Override public List<io.kestra.core.models.namespaces.files.NamespaceFileMetadata> children(String path, boolean recursive) { return List.of(); }
        @Override public List<org.apache.commons.lang3.tuple.Pair<io.kestra.core.storages.NamespaceFile, io.kestra.core.storages.NamespaceFile>> move(Path source, Path target) { return List.of(); }
        @Override public io.kestra.core.storages.NamespaceFile get(Path path) { return io.kestra.core.storages.NamespaceFile.of(namespace(), path); }
        @Override public List<io.kestra.core.storages.NamespaceFile> findAllFilesMatching(java.util.function.Predicate<Path> predicate) { return List.of(); }
        @Override public InputStream getFileContent(Path path) throws IOException { requestedPath = path; getCount++; return new TrackingStream(content, this); }
        @Override public InputStream getFileContent(Path path, Integer revision) throws IOException { return getFileContent(path); }
        @Override public InputStream getFileContent(io.kestra.core.storages.NamespaceFile file) throws IOException { return getFileContent(file.filePath()); }
        @Override public io.kestra.core.storages.FileAttributes getFileMetadata(Path path) { return null; }
        @Override public boolean exists(Path path) throws IOException { requestedPath = path; return exists; }
        @Override public List<io.kestra.core.storages.NamespaceFile> putFile(Path path, InputStream input, io.kestra.core.storages.Namespace.Conflicts conflicts) { return List.of(); }
        @Override public io.kestra.core.storages.NamespaceFile createDirectory(Path path) { return io.kestra.core.storages.NamespaceFile.of(namespace(), path); }
        @Override public List<io.kestra.core.storages.NamespaceFile> delete(Path path) { return List.of(); }
    }

    private static final class TrackingStream extends ByteArrayInputStream {
        private final TrackingNamespace owner;
        private TrackingStream(byte[] content, TrackingNamespace owner) { super(content); this.owner = owner; }
        @Override public void close() { owner.closeCount++; }
    }
}
