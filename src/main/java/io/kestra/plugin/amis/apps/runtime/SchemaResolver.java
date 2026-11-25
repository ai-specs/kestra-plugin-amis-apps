package io.kestra.plugin.amis.apps.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.kestra.plugin.amis.apps.domain.AmisSchema;
import io.kestra.plugin.amis.apps.domain.SchemaReference;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

public final class SchemaResolver {
    private final FileReader reader;
    private final JsonInputParser parser;

    public SchemaResolver(FileReader reader, int maxBytes, int maxDepth) {
        if (reader == null) {
            throw new IllegalArgumentException("Missing read-only schema file reader");
        }
        if (maxBytes > AmisSchema.MAX_BYTES || maxDepth > AmisSchema.MAX_DEPTH) {
            throw new IllegalArgumentException("Schema limits cannot exceed the existing AMIS byte/depth ceilings");
        }
        this.reader = reader;
        parser = new JsonInputParser(maxBytes, maxDepth);
    }

    public Optional<ResolvedSchema> resolve(String currentNamespace, String schemaUri) throws IOException {
        SchemaReference reference = SchemaReference.parse(schemaUri);
        for (SchemaReference.Candidate candidate : reference.candidates(currentNamespace)) {
            Optional<InputStream> found = reader.open(candidate.namespace(), candidate.path());
            if (found == null) {
                throw new IOException("FileReader must return Optional.empty only for a missing file, not null");
            }
            if (found.isEmpty()) {
                continue;
            }
            try (InputStream stream = found.orElseThrow()) {
                byte[] bytes = parser.readBounded(stream);
                parser.parse(bytes);
                JsonNode schema = AmisSchema.parse(bytes);
                return Optional.of(new ResolvedSchema(candidate.namespace(), candidate.path(), schema));
            }
        }
        return Optional.empty();
    }

    @FunctionalInterface
    public interface FileReader {
        Optional<InputStream> open(String namespace, String path) throws IOException;
    }

    public record ResolvedSchema(String namespace, String path, JsonNode schema) { }
}
