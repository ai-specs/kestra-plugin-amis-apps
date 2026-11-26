package io.kestra.plugin.amis.apps.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.kestra.plugin.amis.apps.domain.AmisSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AsyncSchemaResolverTest {
    private static final byte[] PAGE = "{\"type\":\"page\",\"body\":{\"type\":\"form\"}}".getBytes(StandardCharsets.UTF_8);
    private static final String URI = "nsfile:///schemas/home.json";

    @Test void resolvesCurrentNamespaceAndReturnsSchema() {
        List<String> calls = new ArrayList<>();
        var resolver = resolver((namespace, path) -> { calls.add(namespace + path); return Mono.just(Optional.of(PAGE)); });
        var result = resolver.resolve("company.team", URI).block().orElseThrow();
        assertEquals("company.team", result.namespace());
        assertEquals("/schemas/home.json", result.path());
        assertEquals("page", result.schema().path("type").asText());
        assertEquals(List.of("company.team/schemas/home.json"), calls);
    }

    @Test void missingCurrentFallsBackToParentButFirstPresentStops() {
        List<String> calls = new ArrayList<>();
        var resolver = resolver((namespace, path) -> { calls.add(namespace); return namespace.equals("company") ? Mono.just(Optional.of(PAGE)) : Mono.just(Optional.empty()); });
        assertEquals("company", resolver.resolve("company.team", URI).block().orElseThrow().namespace());
        assertEquals(List.of("company.team", "company"), calls);
    }

    @Test void badPresentSchemaDoesNotFallback() {
        List<String> calls = new ArrayList<>();
        var resolver = resolver((namespace, path) -> { calls.add(namespace); return Mono.just(Optional.of("{}".getBytes(StandardCharsets.UTF_8))); });
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("company.team", URI).block());
        assertEquals(List.of("company.team"), calls);
    }

    @Test void emptyMonoAndReaderErrorPropagateWithoutFallback() {
        List<String> calls = new ArrayList<>();
        var empty = resolver((namespace, path) -> { calls.add(namespace); return Mono.empty(); });
        assertThrows(IllegalStateException.class, () -> empty.resolve("company.team", URI).block());
        assertEquals(List.of("company.team"), calls);
        var failure = resolver((namespace, path) -> Mono.error(new IllegalStateException("read failed")));
        assertThrows(IllegalStateException.class, () -> failure.resolve("company.team", URI).block());
    }

    @Test void allOptionalMissingReturnsEmptyAndInvalidReferenceIsRejected() {
        var resolver = resolver((namespace, path) -> Mono.just(Optional.empty()));
        assertTrue(resolver.resolve("company.team", URI).block().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("company.team", "nsfile://evil/schemas/home.json"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("company.team", "nsfile:///../home.json"));
    }

    @Test void oversizedAndDeepSchemasAreRejected() {
        var resolver = resolver((namespace, path) -> Mono.just(Optional.of(new byte[AmisSchema.MAX_BYTES + 1])));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("company", URI).block());
        String deep = "{".repeat(65) + "\"type\":\"page\"" + "}".repeat(65);
        var deepResolver = resolver((namespace, path) -> Mono.just(Optional.of(deep.getBytes(StandardCharsets.UTF_8))));
        assertThrows(IllegalArgumentException.class, () -> deepResolver.resolve("company", URI).block());
    }

    @Test void cancellationCancelsReaderAndDoesNotVisitParent() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean parentCalled = new AtomicBoolean();
        Mono<Optional<byte[]>> pending = Mono.<Optional<byte[]>>never().doOnCancel(() -> cancelled.set(true));
        var resolver = resolver((namespace, path) -> namespace.equals("company.team") ? pending : Mono.fromRunnable(() -> parentCalled.set(true)).then(Mono.just(Optional.of(PAGE))));
        var result = resolver.resolve("company.team", URI).subscribe();
        result.dispose();
        for (int i = 0; i < 20 && !cancelled.get(); i++) TimeUnit.MILLISECONDS.sleep(5);
        assertTrue(cancelled.get());
        assertFalse(parentCalled.get());
    }

    @Test void namespaceParentCandidatesKeepValidatedPath() {
        List<String> calls = new ArrayList<>();
        var resolver = resolver((namespace, path) -> { calls.add(namespace + path); return Mono.just(Optional.empty()); });
        resolver.resolve("company._team.sub", URI).block();
        assertEquals(List.of("company._team.sub/schemas/home.json", "company._team/schemas/home.json", "company/schemas/home.json"), calls);
    }

    private static AsyncSchemaResolver resolver(AsyncSchemaResolver.FileReader reader) {
        return new AsyncSchemaResolver(reader, AmisSchema.MAX_BYTES, AmisSchema.MAX_DEPTH);
    }
}
