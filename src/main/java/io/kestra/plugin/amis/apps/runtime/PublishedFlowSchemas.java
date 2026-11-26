package io.kestra.plugin.amis.apps.runtime;

import io.kestra.plugin.amis.apps.domain.AmisSchema;
import io.kestra.webserver.rbac.FlowNamespaceFileReaderV1;
import io.micronaut.security.authentication.Authentication;
import reactor.core.publisher.Mono;
import java.util.Objects;
import java.util.Optional;

/** Request-bound consumer of the published Flow-authorized file reader. */
final class PublishedFlowSchemas {
    private final FlowNamespaceFileReaderV1 files;

    PublishedFlowSchemas(FlowNamespaceFileReaderV1 files) {
        this.files = Objects.requireNonNull(files, "Published Flow namespace reader required");
    }

    Mono<Optional<SchemaResolver.ResolvedSchema>> resolve(Authentication identity,
        PublishedFlowAccess.Snapshot snapshot, String namespace, String uri) {
        return Mono.defer(() -> {
            Objects.requireNonNull(identity, "Production identity required");
            Objects.requireNonNull(snapshot, "Validated runtime Flow required");
            if (!snapshot.flow().getNamespace().equals(namespace))
                return Mono.error(new IllegalArgumentException("Schema namespace differs from runtime Flow"));
            var resolver = new AsyncSchemaResolver((candidateNamespace, path) ->
                Mono.defer(() -> files.read(identity, "main", snapshot.flow(), candidateNamespace, path, AmisSchema.MAX_BYTES))
                    .map(content -> content.map(value -> {
                        if (!candidateNamespace.equals(value.namespace()) || !path.equals(value.path()))
                            throw new IllegalStateException("Published file tuple differs from requested candidate");
                        return value.bytes();
                    })), AmisSchema.MAX_BYTES, AmisSchema.MAX_DEPTH);
            return resolver.resolve(namespace, uri);
        });
    }
}
