package io.kestra.plugin.amis.apps.runtime;

import io.kestra.core.models.flows.Flow;
import io.kestra.webserver.rbac.FlowNamespaceFileReaderV1;
import io.micronaut.security.authentication.Authentication;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PublishedFlowSchemasTest {
    private final Authentication identity = Authentication.build("actor");
    private final Flow flow = Flow.builder().id("app").namespace("company.team").revision(1).build();
    private final PublishedFlowAccess.Snapshot snapshot = mock(PublishedFlowAccess.Snapshot.class);
    private final FlowNamespaceFileReaderV1 files = mock(FlowNamespaceFileReaderV1.class);
    private static final byte[] PAGE = "{\"type\":\"page\",\"body\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test void absentOnlyFallsBackInOrderUsingSameFlowAndIdentity() {
        when(snapshot.flow()).thenReturn(flow);
        when(files.read(same(identity), eq("main"), same(flow), eq("company.team"), eq("/home.json"), eq(1_048_576)))
            .thenReturn(Mono.just(Optional.empty()));
        when(files.read(same(identity), eq("main"), same(flow), eq("company"), eq("/home.json"), eq(1_048_576)))
            .thenReturn(Mono.just(Optional.of(new FlowNamespaceFileReaderV1.Content("company", "/home.json", PAGE))));
        var result = new PublishedFlowSchemas(files).resolve(identity, snapshot, "company.team", "nsfile:///home.json").block();
        assertTrue(result.orElseThrow().namespace().equals("company"));
        var ordered = inOrder(files);
        ordered.verify(files).read(identity, "main", flow, "company.team", "/home.json", 1_048_576);
        ordered.verify(files).read(identity, "main", flow, "company", "/home.json", 1_048_576);
        verifyNoMoreInteractions(files);
    }

    @Test void failureDoesNotFallBackOrWrapPublishedException() {
        when(snapshot.flow()).thenReturn(flow);
        var failure = new IllegalStateException("reader unavailable");
        when(files.read(any(), anyString(), any(), anyString(), anyString(), anyInt())).thenReturn(Mono.error(failure));
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> new PublishedFlowSchemas(files).resolve(identity, snapshot, "company.team", "nsfile:///home.json").block()));
        verify(files).read(identity, "main", flow, "company.team", "/home.json", 1_048_576);
        verifyNoMoreInteractions(files);
    }

    @Test void cancellationReachesPublishedReaderWithoutParentLookup() {
        when(snapshot.flow()).thenReturn(flow);
        var cancelled = new AtomicBoolean();
        when(files.read(any(), anyString(), any(), anyString(), anyString(), anyInt()))
            .thenReturn(Mono.<Optional<FlowNamespaceFileReaderV1.Content>>never().doOnCancel(() -> cancelled.set(true)));
        var subscription = new PublishedFlowSchemas(files).resolve(identity, snapshot, "company.team", "nsfile:///home.json").subscribe();
        subscription.dispose();
        assertTrue(cancelled.get());
        verify(files).read(identity, "main", flow, "company.team", "/home.json", 1_048_576);
        verifyNoMoreInteractions(files);
    }

    @Test void mismatchedContentNamespaceFailsWithoutParentLookup() {
        when(snapshot.flow()).thenReturn(flow);
        when(files.read(same(identity), eq("main"), same(flow), eq("company.team"), eq("/home.json"), eq(1_048_576)))
            .thenReturn(Mono.just(Optional.of(new FlowNamespaceFileReaderV1.Content("company", "/home.json", PAGE))));
        var failure = assertThrows(IllegalStateException.class,
            () -> new PublishedFlowSchemas(files).resolve(identity, snapshot, "company.team", "nsfile:///home.json").block());
        assertEquals("Published file tuple differs from requested candidate", failure.getMessage());
        verifyOnlyCurrentCandidate();
    }

    @Test void mismatchedContentPathFailsWithoutParentLookup() {
        when(snapshot.flow()).thenReturn(flow);
        when(files.read(same(identity), eq("main"), same(flow), eq("company.team"), eq("/home.json"), eq(1_048_576)))
            .thenReturn(Mono.just(Optional.of(new FlowNamespaceFileReaderV1.Content("company.team", "/other.json", PAGE))));
        var failure = assertThrows(IllegalStateException.class,
            () -> new PublishedFlowSchemas(files).resolve(identity, snapshot, "company.team", "nsfile:///home.json").block());
        assertEquals("Published file tuple differs from requested candidate", failure.getMessage());
        verifyOnlyCurrentCandidate();
    }

    @Test void malformedJsonAtCurrentCandidateFailsWithoutParentLookup() {
        when(snapshot.flow()).thenReturn(flow);
        byte[] malformed = "{\"type\":".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(files.read(same(identity), eq("main"), same(flow), eq("company.team"), eq("/home.json"), eq(1_048_576)))
            .thenReturn(Mono.just(Optional.of(new FlowNamespaceFileReaderV1.Content("company.team", "/home.json", malformed))));
        assertThrows(IllegalArgumentException.class,
            () -> new PublishedFlowSchemas(files).resolve(identity, snapshot, "company.team", "nsfile:///home.json").block());
        verifyOnlyCurrentCandidate();
    }

    @Test void snapshotNamespaceMismatchNeverCallsFiles() {
        when(snapshot.flow()).thenReturn(flow);
        var failure = assertThrows(IllegalArgumentException.class,
            () -> new PublishedFlowSchemas(files).resolve(identity, snapshot, "company.other", "nsfile:///home.json").block());
        assertEquals("Schema namespace differs from runtime Flow", failure.getMessage());
        verifyNoInteractions(files);
    }

    @Test void emptyReaderMonoFailsClosedWithoutParentLookup() {
        when(snapshot.flow()).thenReturn(flow);
        when(files.read(same(identity), eq("main"), same(flow), eq("company.team"), eq("/home.json"), eq(1_048_576)))
            .thenReturn(Mono.empty());
        var failure = assertThrows(IllegalStateException.class,
            () -> new PublishedFlowSchemas(files).resolve(identity, snapshot, "company.team", "nsfile:///home.json").block());
        assertEquals("FileReader returned empty Mono", failure.getMessage());
        verifyOnlyCurrentCandidate();
    }

    private void verifyOnlyCurrentCandidate() {
        verify(files).read(same(identity), eq("main"), same(flow), eq("company.team"), eq("/home.json"), eq(1_048_576));
        verifyNoMoreInteractions(files);
    }
}
