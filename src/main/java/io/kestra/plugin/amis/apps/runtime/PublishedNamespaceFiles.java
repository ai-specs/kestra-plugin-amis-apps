package io.kestra.plugin.amis.apps.runtime;

import io.kestra.core.storages.Namespace;
import io.kestra.core.storages.NamespaceFactory;
import io.kestra.core.storages.StorageContext;
import io.kestra.plugin.amis.apps.domain.Identifiers;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public final class PublishedNamespaceFiles implements SchemaResolver.FileReader {
    private final NamespaceProvider namespaces;

    public PublishedNamespaceFiles(NamespaceFactory namespaceFactory, String tenantId) {
        Objects.requireNonNull(namespaceFactory, "Missing published NamespaceFactory");
        namespaces = namespace -> namespaceFactory.of(tenantId, namespace);
    }

    PublishedNamespaceFiles(NamespaceProvider namespaces) {
        this.namespaces = Objects.requireNonNull(namespaces, "Missing namespace provider");
    }

    @Override
    public Optional<InputStream> open(String namespace, String path) throws IOException {
        Identifiers.namespace(namespace);
        StorageContext.requireNoEmptySegment(namespace);
        if (namespace.contains("/") || namespace.contains("\\") || namespace.contains("%")
            || path == null || !path.startsWith("/") || path.contains("\\")
            || path.contains("%") || path.contains("?") || path.contains("#")
            || path.contains("//") || pathSegmentsContainTraversal(path)) {
            throw new IllegalArgumentException("Invalid namespace file candidate");
        }
        Path logicalPath = Path.of(path).normalize();
        if (!logicalPath.isAbsolute() || !logicalPath.toString().equals(path)) {
            throw new IllegalArgumentException("Namespace file path must remain canonical");
        }
        Namespace target = namespaces.forNamespace(namespace);
        if (!target.exists(logicalPath)) {
            return Optional.empty();
        }
        return Optional.of(target.getFileContent(logicalPath));
    }

    private static boolean pathSegmentsContainTraversal(String path) {
        for (String segment : path.substring(1).split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    interface NamespaceProvider {
        Namespace forNamespace(String namespace);
    }
}
