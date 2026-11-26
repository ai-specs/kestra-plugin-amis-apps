package io.kestra.plugin.amis.apps.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.kestra.plugin.amis.apps.domain.AmisSchema;
import io.kestra.plugin.amis.apps.domain.SchemaReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Optional;

public final class AsyncSchemaResolver {
    private final FileReader reader;
    private final JsonInputParser parser;

    public AsyncSchemaResolver(FileReader reader, int maxBytes, int maxDepth) {
        if (reader == null) {
            throw new IllegalArgumentException("Missing read-only schema file reader");
        }
        if (maxBytes < 1 || maxBytes > AmisSchema.MAX_BYTES || maxDepth < 1 || maxDepth > AmisSchema.MAX_DEPTH) {
            throw new IllegalArgumentException("Schema limits exceed the existing AMIS ceilings");
        }
        this.reader = reader;
        this.parser = new JsonInputParser(maxBytes, maxDepth);
    }

    public Mono<Optional<SchemaResolver.ResolvedSchema>> resolve(String currentNamespace, String schemaUri) {
        final SchemaReference reference = SchemaReference.parse(schemaUri);
        return Flux.fromIterable(reference.candidates(currentNamespace))
            .concatMap(candidate -> readCandidate(candidate)
                .map(schema -> schema.map(value -> new SchemaResolver.ResolvedSchema(candidate.namespace(), candidate.path(), value))))
            .takeUntil(Optional::isPresent)
            .filter(Optional::isPresent)
            .next()
            .defaultIfEmpty(Optional.empty());
    }

    private Mono<Optional<JsonNode>> readCandidate(SchemaReference.Candidate candidate) {
        return Mono.defer(() -> {
            Mono<Optional<byte[]>> result = reader.read(candidate.namespace(), candidate.path());
            if (result == null) {
                return Mono.error(new IllegalStateException("FileReader returned null Mono"));
            }
            return result.switchIfEmpty(Mono.error(new IllegalStateException("FileReader returned empty Mono")))
                .flatMap(bytes -> {
                    try {
                        if (bytes.isEmpty()) return Mono.just(Optional.empty());
                        parser.parse(bytes.orElseThrow());
                        return Mono.just(Optional.of(AmisSchema.parse(bytes.orElseThrow())));
                    } catch (IllegalArgumentException exception) {
                        return Mono.error(new InvalidSchemaException(exception));
                    }
                });
        });
    }

    /** Marks only validation of bytes already returned by the reader. */
    public static final class InvalidSchemaException extends IllegalArgumentException {
        private InvalidSchemaException(IllegalArgumentException cause) {
            super("Invalid AMIS schema document", cause);
        }
    }

    @FunctionalInterface
    public interface FileReader {
        Mono<Optional<byte[]>> read(String namespace, String path);
    }
}
