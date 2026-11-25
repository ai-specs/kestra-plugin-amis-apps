package io.kestra.plugin.amis.apps.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record FlowDeclarations(String namespace, String flowId, List<Page> pages, List<Api> apis) {
    public FlowDeclarations {
        Identifiers.namespace(namespace);
        Identifiers.flowId(flowId);
        if (pages == null || apis == null || pages.stream().anyMatch(java.util.Objects::isNull)
            || apis.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Missing declarations");
        }
        pages = List.copyOf(pages);
        apis = List.copyOf(apis);
        Set<AppRoute> routes = new HashSet<>();
        for (Page page : pages) {
            if (!routes.add(new AppRoute(namespace, flowId, AppRoute.Kind.PAGE, page.id()))) {
                throw new IllegalArgumentException("Duplicate page route");
            }
        }
        for (Api api : apis) {
            if (!routes.add(new AppRoute(namespace, flowId, AppRoute.Kind.API, api.id()))) {
                throw new IllegalArgumentException("Duplicate API route");
            }
        }
    }

    public record Page(String id, SchemaReference schema) {
        public Page {
            Identifiers.id(id);
            if (schema == null) {
                throw new IllegalArgumentException("Missing schema reference");
            }
        }
    }

    public record Api(String id, ApiConfiguration configuration) {
        public Api {
            Identifiers.id(id);
            configuration = configuration == null ? ApiConfiguration.defaults() : configuration;
        }
    }
}
