package io.kestra.plugin.amis.apps.domain;

public record AppRoute(String namespace, String flowId, Kind kind, String declarationId) {
    public enum Kind { PAGE, API }

    public AppRoute {
        Identifiers.namespace(namespace);
        Identifiers.flowId(flowId);
        Identifiers.id(declarationId);
        if (kind == null) {
            throw new IllegalArgumentException("Missing route kind");
        }
    }

    public static AppRoute parse(String rawPath) {
        if (rawPath == null || rawPath.length() > 512) {
            throw new IllegalArgumentException("Invalid URL path");
        }
        String[] parts = rawPath.split("/", -1);
        if (parts.length != 5 || !parts[0].isEmpty()) {
            throw new IllegalArgumentException("Expected /namespace/flow/pages|apis/id");
        }
        Kind kind = switch (parts[3]) {
            case "pages" -> Kind.PAGE;
            case "apis" -> Kind.API;
            default -> throw new IllegalArgumentException("Invalid route kind");
        };
        return new AppRoute(parts[1], parts[2], kind, parts[4]);
    }
}
