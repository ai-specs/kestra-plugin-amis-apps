package io.kestra.plugin.amis.apps.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

public record SchemaReference(String path) {
    public SchemaReference {
        if (path == null || path.length() > 1024 || !path.startsWith("/") || !path.endsWith(".json")) {
            throw new IllegalArgumentException("Invalid schema path");
        }
        for (String segment : path.substring(1).split("/", -1)) {
            if (!segment.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}") || segment.equals("..") || segment.equals(".")) {
                throw new IllegalArgumentException("Unsafe schema path segment");
            }
        }
    }

    public static SchemaReference parse(String value) {
        try {
            if (value == null || !value.startsWith("nsfile:///")) {
                throw new IllegalArgumentException("Only canonical nsfile:/// URI is allowed");
            }
            URI uri = new URI(value);
            if (!"nsfile".equals(uri.getScheme()) || uri.getRawAuthority() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.isOpaque()) {
                throw new IllegalArgumentException("Unsupported schema URI component");
            }
            return new SchemaReference(uri.getRawPath());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid schema URI", exception);
        }
    }

    public List<Candidate> candidates(String currentNamespace) {
        String namespace = Identifiers.namespace(currentNamespace);
        List<Candidate> candidates = new ArrayList<>();
        while (true) {
            candidates.add(new Candidate(namespace, path));
            int separator = namespace.lastIndexOf('.');
            if (separator < 0) {
                return List.copyOf(candidates);
            }
            namespace = namespace.substring(0, separator);
        }
    }

    public record Candidate(String namespace, String path) { }
}
