package io.kestra.plugin.amis.apps.runtime;

import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.runners.ProcessedFlow;
import io.kestra.core.serializers.YamlParser;
import io.kestra.plugin.amis.apps.ApiTrigger;
import io.kestra.plugin.amis.apps.domain.ApiConfiguration;
import io.kestra.webserver.rbac.HttpExtensionRouteProviderV1;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.ServerHttpRequest;
import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.authentication.Authentication;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import java.time.Instant;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AmisHttpRouteProviderTest {
    @Test
    void matchesPagesSchemasAssetsApisAndPollWithExactMethods() {
        AmisHttpRouteProvider provider = provider(snapshot(flow(false, false)), 2);
        assertTarget(provider, HttpMethod.GET, "/company.demo/greeting-app/pages/home", true);
        assertTarget(provider, HttpMethod.GET, "/company.demo/greeting-app/schemas/home", false);
        assertTarget(provider, HttpMethod.GET, "/company.demo/greeting-app/assets/index.js", false);
        assertTarget(provider, HttpMethod.HEAD, "/company.demo/greeting-app/assets/index.js", false);
        assertTarget(provider, HttpMethod.POST, "/company.demo/greeting-app/apis/greet", false);
        assertTarget(provider, HttpMethod.GET, "/company.demo/greeting-app/apis/greet/executions/execution123", false);
        for (String path : new String[] {"/company.demo/greeting-app/pages/home/", "/company.demo/greeting-app/pages/%68ome",
            "/company.demo/greeting-app/pages/../home", "/company.demo/greeting-app//pages/home", "/ui/login"}) {
            assertTrue(provider.match(HttpMethod.GET, path).isEmpty(), path);
        }
        assertTrue(provider.match(HttpMethod.POST, "/company.demo/greeting-app/pages/home").isEmpty());
        assertTrue(provider.match(HttpMethod.GET, "/company.demo/greeting-app/apis/greet").isEmpty());
    }

    @Test
    void targetDocumentReturnOnlyAppliesToPages() {
        AmisHttpRouteProvider provider = provider(snapshot(flow(false, false)), 2);
        assertTrue(provider.match(HttpMethod.GET, "/company.demo/greeting-app/pages/home").orElseThrow().documentReturn());
        assertFalse(provider.match(HttpMethod.GET, "/company.demo/greeting-app/schemas/home").orElseThrow().documentReturn());
    }

    @Test
    void providerHasStableCapabilityIdAndSyncCapacityIsValidated() {
        AmisHttpRouteProvider provider = provider(snapshot(flow(false, false)), 2);
        assertEquals("amis-apps", provider.id());
        assertThrows(IllegalArgumentException.class, () -> provider(snapshot(flow(false, false)), 0));
    }

    @Test
    void deniedAssetAuthorizationDoesNotReadAsset() {
        PublishedFlowAccess access = mock(PublishedFlowAccess.class);
        PublishedFlowAccess.Snapshot snapshot = snapshot(flow(false, false));
        AtomicReference<String> lookupThread = new AtomicReference<>();
        when(access.lookupAfterEntry("company.demo", "greeting-app")).thenAnswer(call -> {
            lookupThread.set(Thread.currentThread().getName());
            return snapshot;
        });
        PublishedFlowInvocation invocation = mock(PublishedFlowInvocation.class);
        when(invocation.finishRequest()).thenReturn(Mono.empty());
        when(access.withFlowExecutionPermission(any(), eq(snapshot), any())).thenReturn(Mono.error(new HttpStatusException(HttpStatus.FORBIDDEN, "denied")));
        RuntimeAssets assets = mock(RuntimeAssets.class);
        AmisHttpRouteProvider provider = new AmisHttpRouteProvider(access, invocation,
            mock(PublishedExecutionResults.class), mock(SchemaResolver.class), assets, mock(PageDocument.class), 2);
        String callerThread = Thread.currentThread().getName();
        HttpRequest<?> request = authenticated(HttpRequest.GET("/company.demo/greeting-app/assets/index.js"));
        assertStatus(HttpStatus.FORBIDDEN, () -> Mono.from(provider.handle(request, provider.match(HttpMethod.GET,
            request.getUri().getRawPath()).orElseThrow())).block());
        assertNotNull(lookupThread.get());
        assertNotEquals(callerThread, lookupThread.get());
        verifyNoInteractions(assets);
    }

    @Test
    void serverRequestAssetDenialDoesNotMoveByteBody() {
        PublishedFlowAccess access = mock(PublishedFlowAccess.class);
        PublishedFlowAccess.Snapshot snapshot = snapshot(flow(false, false));
        when(access.lookupAfterEntry(anyString(), anyString())).thenReturn(snapshot);
        when(access.withFlowExecutionPermission(any(), eq(snapshot), any()))
            .thenReturn(Mono.error(new HttpStatusException(HttpStatus.FORBIDDEN, "denied")));
        PublishedFlowInvocation invocation = mock(PublishedFlowInvocation.class);
        when(invocation.finishRequest()).thenReturn(Mono.empty());
        ServerHttpRequest<?> request = serverRequest(HttpMethod.GET, "/company.demo/greeting-app/assets/index.js", null);
        CloseableByteBody body = mock(CloseableByteBody.class);
        when(request.byteBody()).thenReturn(body);
        AmisHttpRouteProvider provider = new AmisHttpRouteProvider(access, invocation, mock(PublishedExecutionResults.class),
            mock(SchemaResolver.class), mock(RuntimeAssets.class), mock(PageDocument.class), 1);
        assertStatus(HttpStatus.FORBIDDEN, () -> Mono.from(provider.handle(request, provider.match(HttpMethod.GET,
            request.getUri().getRawPath()).orElseThrow())).block());
        verify(body, never()).move();
    }

    @Test
    void serverRequestMalformedJsonClosesInputAndBodyBeforeRejecting() throws Exception {
        PublishedFlowAccess access = mock(PublishedFlowAccess.class);
        PublishedFlowAccess.Snapshot snapshot = snapshot(flow(false, false));
        when(access.lookupAfterEntry(anyString(), anyString())).thenReturn(snapshot);
        when(access.withFlowExecutionPermission(any(), eq(snapshot), any())).thenAnswer(call -> invokeSupplier(call.getArgument(2)));
        PublishedFlowInvocation invocation = mock(PublishedFlowInvocation.class);
        when(invocation.finishRequest()).thenReturn(Mono.empty());
        CloseableByteBody body = mock(CloseableByteBody.class);
        when(body.move()).thenReturn(body);
        InputStream input = spy(new ByteArrayInputStream("{\"x\":1} trailing".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(body.toInputStream()).thenReturn(input);
        ServerHttpRequest<?> request = serverRequest(HttpMethod.POST, "/company.demo/greeting-app/apis/greet", "application/json");
        when(request.byteBody()).thenReturn(body);
        AmisHttpRouteProvider provider = new AmisHttpRouteProvider(access, invocation, mock(PublishedExecutionResults.class),
            mock(SchemaResolver.class), mock(RuntimeAssets.class), mock(PageDocument.class), 1);
        assertStatus(HttpStatus.BAD_REQUEST, () -> Mono.from(provider.handle(request, provider.match(HttpMethod.POST,
            request.getUri().getRawPath()).orElseThrow())).block());
        verify(input).close();
        verify(body).close();
        verify(invocation, never()).submit(any(), any(), anyString(), anyMap());
    }

    @Test
    void cancelledServerRequestClosesBlockingBodyWithoutSubmitting() throws Exception {
        PublishedFlowAccess access = mock(PublishedFlowAccess.class);
        PublishedFlowAccess.Snapshot snapshot = snapshot(flow(true, false));
        when(access.lookupAfterEntry(anyString(), anyString())).thenReturn(snapshot);
        when(access.withFlowExecutionPermission(any(), eq(snapshot), any())).thenAnswer(call -> invokeSupplier(call.getArgument(2)));
        PublishedFlowInvocation invocation = mock(PublishedFlowInvocation.class);
        when(invocation.finishRequest()).thenReturn(Mono.empty());
        CloseableByteBody body = mock(CloseableByteBody.class);
        when(body.move()).thenReturn(body);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        InputStream input = new InputStream() {
            @Override public int read() throws java.io.IOException {
                reading.countDown();
                try { Thread.sleep(10_000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                throw new java.io.IOException("cancelled");
            }
            @Override public void close() { closed.countDown(); }
        };
        when(body.toInputStream()).thenReturn(input);
        ServerHttpRequest<?> request = serverRequest(HttpMethod.POST, "/company.demo/greeting-app/apis/greet", "application/json");
        when(request.byteBody()).thenReturn(body);
        PublishedFlowInvocation.Submission ignored = null;
        AmisHttpRouteProvider provider = new AmisHttpRouteProvider(access, invocation, mock(PublishedExecutionResults.class),
            mock(SchemaResolver.class), mock(RuntimeAssets.class), mock(PageDocument.class), 1);
        reactor.core.Disposable subscription = Mono.from(provider.handle(request, provider.match(HttpMethod.POST,
            request.getUri().getRawPath()).orElseThrow())).subscribe();
        assertTrue(reading.await(2, TimeUnit.SECONDS));
        subscription.dispose();
        assertTrue(closed.await(2, TimeUnit.SECONDS));
        verify(invocation, never()).submit(any(), any(), anyString(), anyMap());
        verify(body).close();
    }

    @Test
    void slowServerUploadTimesOutClosesResourcesAndRejectsInvalidBodyTimeouts() throws Exception {
        PublishedFlowAccess access = mock(PublishedFlowAccess.class);
        PublishedFlowAccess.Snapshot snapshot = snapshot(flow(false, false));
        when(access.lookupAfterEntry(anyString(), anyString())).thenReturn(snapshot);
        when(access.withFlowExecutionPermission(any(), eq(snapshot), any())).thenAnswer(call -> invokeSupplier(call.getArgument(2)));
        PublishedFlowInvocation invocation = mock(PublishedFlowInvocation.class);
        when(invocation.finishRequest()).thenReturn(Mono.empty());
        CloseableByteBody body = mock(CloseableByteBody.class);
        when(body.move()).thenReturn(body);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        when(body.toInputStream()).thenReturn(new InputStream() {
            @Override public int read() throws java.io.IOException {
                reading.countDown();
                try { Thread.sleep(10_000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                throw new java.io.IOException("timeout");
            }
            @Override public void close() { closed.countDown(); }
        });
        ServerHttpRequest<?> request = serverRequest(HttpMethod.POST, "/company.demo/greeting-app/apis/greet", "application/json");
        when(request.byteBody()).thenReturn(body);
        AmisHttpRouteProvider provider = new AmisHttpRouteProvider(access, invocation, mock(PublishedExecutionResults.class),
            mock(SchemaResolver.class), mock(RuntimeAssets.class), mock(PageDocument.class), 1, java.time.Duration.ofMillis(20));
        assertStatus(HttpStatus.REQUEST_TIMEOUT, () -> Mono.from(provider.handle(request, provider.match(HttpMethod.POST,
            request.getUri().getRawPath()).orElseThrow())).block());
        assertTrue(reading.await(2, TimeUnit.SECONDS));
        assertTrue(closed.await(2, TimeUnit.SECONDS));
        verify(body).close();
        verify(invocation, never()).submit(any(), any(), anyString(), anyMap());
        assertThrows(IllegalArgumentException.class, () -> new AmisHttpRouteProvider(access, invocation,
            mock(PublishedExecutionResults.class), mock(SchemaResolver.class), mock(RuntimeAssets.class), mock(PageDocument.class), 1, java.time.Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new AmisHttpRouteProvider(access, invocation,
            mock(PublishedExecutionResults.class), mock(SchemaResolver.class), mock(RuntimeAssets.class), mock(PageDocument.class), 1, java.time.Duration.ofSeconds(31)));
        assertThrows(IllegalArgumentException.class, () -> new AmisHttpRouteProvider(access, invocation,
            mock(PublishedExecutionResults.class), mock(SchemaResolver.class), mock(RuntimeAssets.class), mock(PageDocument.class), 1, java.time.Duration.ofMillis(-1)));
    }

    @Test
    void flowPermissionWaitsForFirstEntryFinishAndFinishFailureDoesNotDispatch() throws Exception {
        PublishedFlowAccess access = mock(PublishedFlowAccess.class);
        PublishedFlowAccess.Snapshot snapshot = snapshot(flow(false, false));
        when(access.lookupAfterEntry(anyString(), anyString())).thenReturn(snapshot);
        AtomicReference<MonoSink<Void>> finish = new AtomicReference<>();
        CountDownLatch finishSubscribed = new CountDownLatch(1);
        when(access.withFlowExecutionPermission(any(), eq(snapshot), any())).thenReturn(Mono.error(new HttpStatusException(HttpStatus.FORBIDDEN, "denied")));
        PublishedFlowInvocation invocation = mock(PublishedFlowInvocation.class);
        when(invocation.finishRequest()).thenReturn(Mono.create(sink -> {
            finish.set(sink);
            finishSubscribed.countDown();
        }));
        AmisHttpRouteProvider provider = new AmisHttpRouteProvider(access, invocation, mock(PublishedExecutionResults.class),
            mock(SchemaResolver.class), mock(RuntimeAssets.class), mock(PageDocument.class), 1);
        HttpRequest<?> request = authenticated(HttpRequest.GET("/company.demo/greeting-app/assets/index.js"));
        var result = Mono.from(provider.handle(request, provider.match(HttpMethod.GET,
            request.getUri().getRawPath()).orElseThrow())).materialize().toFuture();
        assertTrue(finishSubscribed.await(2, TimeUnit.SECONDS));
        verify(access, never()).withFlowExecutionPermission(any(), any(), any());
        finish.get().success();
        assertTrue(result.get(2, TimeUnit.SECONDS).isOnError());
        assertEquals(HttpStatus.FORBIDDEN, ((HttpStatusException) result.get().getThrowable()).getStatus());
        verify(access).withFlowExecutionPermission(any(), eq(snapshot), any());

        reset(access, invocation);
        when(access.lookupAfterEntry(anyString(), anyString())).thenReturn(snapshot);
        when(invocation.finishRequest()).thenReturn(Mono.error(new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "finish failed")));
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, () -> Mono.from(provider.handle(request,
            provider.match(HttpMethod.GET, request.getUri().getRawPath()).orElseThrow())).block());
        verify(access, never()).withFlowExecutionPermission(any(), any(), any());
    }

    @Test
    void malformedBodiesAreRejectedBeforeInvocation() {
        PublishedFlowAccess access = mock(PublishedFlowAccess.class);
        PublishedFlowAccess.Snapshot snapshot = snapshot(flow(false, false));
        when(access.lookupAfterEntry(anyString(), anyString())).thenReturn(snapshot);
        when(access.withFlowExecutionPermission(any(), eq(snapshot), any())).thenAnswer(call -> invokeSupplier(call.getArgument(2)));
        PublishedFlowInvocation invocation = mock(PublishedFlowInvocation.class);
        when(invocation.finishRequest()).thenReturn(Mono.empty());
        AmisHttpRouteProvider provider = new AmisHttpRouteProvider(access, invocation, mock(PublishedExecutionResults.class),
            mock(SchemaResolver.class), mock(RuntimeAssets.class), mock(PageDocument.class), 2);
        for (String body : List.of("{\"a\":1,\"a\":2}", "{\"a\":1} trailing", "[]")) {
            HttpRequest<byte[]> request = authenticated(HttpRequest.POST("/company.demo/greeting-app/apis/greet", body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_JSON_TYPE));
            assertStatus(HttpStatus.BAD_REQUEST, () -> Mono.from(provider.handle(request, provider.match(HttpMethod.POST,
                request.getUri().getRawPath()).orElseThrow())).block());
        }
        String oversized = "{\"value\":\"" + "x".repeat(1_048_570) + "\"}";
        HttpRequest<byte[]> request = authenticated(HttpRequest.POST("/company.demo/greeting-app/apis/greet", oversized.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .contentType(MediaType.APPLICATION_JSON_TYPE));
        assertStatus(HttpStatus.BAD_REQUEST, () -> Mono.from(provider.handle(request, provider.match(HttpMethod.POST,
            request.getUri().getRawPath()).orElseThrow())).block());
        verify(invocation, times(4)).finishRequest();
        verify(invocation, never()).submit(any(), any(), anyString(), anyMap());
    }

    @Test
    void syncTimeoutReturnsPendingAndDoesNotSubmitTwice() {
        PublishedFlowAccess access = mock(PublishedFlowAccess.class);
        PublishedFlowAccess.Snapshot snapshot = snapshot(flow(true, false));
        when(access.lookupAfterEntry(anyString(), anyString())).thenReturn(snapshot);
        when(access.withFlowExecutionPermission(any(), eq(snapshot), any())).thenAnswer(call -> invokeSupplier(call.getArgument(2)));
        PublishedFlowInvocation invocation = mock(PublishedFlowInvocation.class);
        Execution execution = Execution.newExecution(snapshot.flow(), List.of());
        RouteDeclarations.Api declaration = snapshot.declarations().apis().getFirst();
        when(invocation.submit(any(), eq(snapshot), eq("greet"), anyMap())).thenReturn(Mono.just(new PublishedFlowInvocation.Submitted(execution, declaration)));
        when(invocation.finishRequest()).thenReturn(Mono.empty());
        PublishedExecutionResults results = mock(PublishedExecutionResults.class);
        ApiResponseEnvelope pending = ApiResponseEnvelope.pending(snapshot.declarations(), "greet", execution.getId(),
            new ApiResponseEnvelope.Binding("company.demo", "greeting-app", "greet", execution.getId(), declaration.resultOutput()),
            "RUNNING");
        when(results.read(any(), eq(snapshot), eq("greet"), eq(execution.getId()))).thenReturn(Mono.just(pending));
        AmisHttpRouteProvider provider = new AmisHttpRouteProvider(access, invocation, results, mock(SchemaResolver.class),
            mock(RuntimeAssets.class), mock(PageDocument.class), 1);
        HttpRequest<byte[]> request = authenticated(HttpRequest.POST("/company.demo/greeting-app/apis/greet", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .contentType(MediaType.APPLICATION_JSON_TYPE));
        MutableHttpResponse<?> response = Mono.from(provider.handle(request, provider.match(HttpMethod.POST,
            request.getUri().getRawPath()).orElseThrow())).block();
        assertEquals(HttpStatus.ACCEPTED, response.getStatus());
        verify(invocation, times(1)).submit(any(), eq(snapshot), eq("greet"), anyMap());
    }

    @Test
    void syncEmptyResultsAreServiceUnavailableRatherThanAnEmptyResponse() {
        PublishedFlowAccess access = mock(PublishedFlowAccess.class);
        PublishedFlowAccess.Snapshot snapshot = snapshot(flow(true, false));
        when(access.lookupAfterEntry(anyString(), anyString())).thenReturn(snapshot);
        when(access.withFlowExecutionPermission(any(), eq(snapshot), any())).thenAnswer(call -> invokeSupplier(call.getArgument(2)));
        PublishedFlowInvocation invocation = mock(PublishedFlowInvocation.class);
        Execution execution = Execution.newExecution(snapshot.flow(), List.of());
        RouteDeclarations.Api declaration = snapshot.declarations().apis().getFirst();
        when(invocation.submit(any(), eq(snapshot), eq("greet"), anyMap())).thenReturn(Mono.just(new PublishedFlowInvocation.Submitted(execution, declaration)));
        when(invocation.finishRequest()).thenReturn(Mono.empty());
        PublishedExecutionResults results = mock(PublishedExecutionResults.class);
        when(results.read(any(), eq(snapshot), eq("greet"), eq(execution.getId()))).thenReturn(Mono.empty());
        AmisHttpRouteProvider provider = new AmisHttpRouteProvider(access, invocation, results, mock(SchemaResolver.class),
            mock(RuntimeAssets.class), mock(PageDocument.class), 1);
        HttpRequest<byte[]> request = authenticated(HttpRequest.POST("/company.demo/greeting-app/apis/greet",
            "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)).contentType(MediaType.APPLICATION_JSON_TYPE));
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, () -> Mono.from(provider.handle(request, provider.match(HttpMethod.POST,
            request.getUri().getRawPath()).orElseThrow())).block());
        verify(invocation, times(1)).submit(any(), eq(snapshot), eq("greet"), anyMap());
    }

    @Test
    void syncCapacityReturns429WithoutSubmissionAndCancellationReleasesPermit() throws Exception {
        PublishedFlowAccess access = mock(PublishedFlowAccess.class);
        PublishedFlowAccess.Snapshot snapshot = snapshot(flow(true, false));
        when(access.lookupAfterEntry(anyString(), anyString())).thenReturn(snapshot);
        when(access.withFlowExecutionPermission(any(), eq(snapshot), any())).thenAnswer(call -> invokeSupplier(call.getArgument(2)));
        PublishedFlowInvocation invocation = mock(PublishedFlowInvocation.class);
        Execution execution = Execution.newExecution(snapshot.flow(), List.of());
        RouteDeclarations.Api declaration = snapshot.declarations().apis().getFirst();
        CountDownLatch submitted = new CountDownLatch(1);
        CountDownLatch resubmitted = new CountDownLatch(1);
        AtomicInteger submitCount = new AtomicInteger();
        when(invocation.submit(any(), eq(snapshot), eq("greet"), anyMap())).thenAnswer(call -> {
            if (submitCount.incrementAndGet() == 1) {
                submitted.countDown();
                return Mono.never();
            }
            resubmitted.countDown();
            return Mono.never();
        });
        when(invocation.finishRequest()).thenReturn(Mono.empty());
        AmisHttpRouteProvider provider = new AmisHttpRouteProvider(access, invocation, mock(PublishedExecutionResults.class),
            mock(SchemaResolver.class), mock(RuntimeAssets.class), mock(PageDocument.class), 1);
        HttpRequest<byte[]> request = authenticated(HttpRequest.POST("/company.demo/greeting-app/apis/greet", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .contentType(MediaType.APPLICATION_JSON_TYPE));
        reactor.core.Disposable first = Mono.from(provider.handle(request, provider.match(HttpMethod.POST,
            request.getUri().getRawPath()).orElseThrow())).subscribe();
        assertTrue(submitted.await(2, TimeUnit.SECONDS));
        assertStatus(HttpStatus.TOO_MANY_REQUESTS, () -> Mono.from(provider.handle(request, provider.match(HttpMethod.POST,
            request.getUri().getRawPath()).orElseThrow())).block());
        first.dispose();
        reactor.core.Disposable second = Mono.from(provider.handle(request, provider.match(HttpMethod.POST,
            request.getUri().getRawPath()).orElseThrow())).subscribe();
        assertTrue(resubmitted.await(2, TimeUnit.SECONDS));
        second.dispose();
        verify(invocation, times(2)).submit(any(), eq(snapshot), eq("greet"), anyMap());
    }

    private static Object invokeSupplier(Supplier<?> supplier) { return supplier.get(); }

    private static <T> T authenticated(T request) {
        ((HttpRequest<?>) request).setAttribute(io.micronaut.security.filters.SecurityFilter.AUTHENTICATION, Authentication.build("ada"));
        return request;
    }

    @SuppressWarnings("unchecked")
    private static ServerHttpRequest<?> serverRequest(HttpMethod method, String path, String contentType) {
        ServerHttpRequest<?> request = mock(ServerHttpRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getUri()).thenReturn(URI.create(path));
        when(request.getContentType()).thenReturn(contentType == null ? Optional.empty() : Optional.of(MediaType.of(contentType)));
        doReturn(Optional.of(Authentication.build("ada"))).when(request)
            .getAttribute(eq(io.micronaut.security.filters.SecurityFilter.AUTHENTICATION), eq(Authentication.class));
        return request;
    }

    @Test void schemaHttpDispatchUsesPublishedFlowReaderAndCurrentIdentity() {
        var access = mock(PublishedFlowAccess.class);
        var snapshot = snapshot(flow(false, false));
        when(access.lookupAfterEntry("company.demo", "greeting-app")).thenReturn(snapshot);
        when(access.withFlowExecutionPermission(any(), eq(snapshot), any())).thenAnswer(call -> invokeSupplier(call.getArgument(2)));
        var invocation = mock(PublishedFlowInvocation.class);
        when(invocation.finishRequest()).thenReturn(Mono.empty());
        var files = mock(io.kestra.webserver.rbac.FlowNamespaceFileReaderV1.class);
        var request = authenticated(HttpRequest.GET("/company.demo/greeting-app/schemas/home"));
        var identity = request.getAttribute(io.micronaut.security.filters.SecurityFilter.AUTHENTICATION, Authentication.class).orElseThrow();
        byte[] bytes = "{\"type\":\"page\",\"body\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(files.read(same(identity), eq("main"), same(snapshot.flow()), eq("company.demo"), eq("/home.json"), eq(1_048_576)))
            .thenReturn(Mono.just(Optional.of(new io.kestra.webserver.rbac.FlowNamespaceFileReaderV1.Content("company.demo", "/home.json", bytes))));
        var provider = new AmisHttpRouteProvider(access, invocation, mock(PublishedExecutionResults.class),
            new PublishedFlowSchemas(files), mock(RuntimeAssets.class), mock(PageDocument.class), 1);
        var response = Mono.from(provider.handle(request, provider.match(HttpMethod.GET, request.getUri().getRawPath()).orElseThrow())).block();
        assertEquals(HttpStatus.OK, response.getStatus());
        verify(files).read(same(identity), eq("main"), same(snapshot.flow()), eq("company.demo"), eq("/home.json"), eq(1_048_576));
        verifyNoMoreInteractions(files);
    }

    private static PublishedFlowAccess.Snapshot snapshot(Flow flow) {
        return new PublishedFlowAccess.Snapshot(ProcessedFlow.of(io.kestra.core.services.FlowParsingService.toFlowWithSource(flow)),
            RouteDeclarations.from(flow));
    }

    private static Flow flow(boolean sync, boolean disabled) {
        String mode = sync ? "SYNC" : "ASYNC";
        return new YamlParser().parse("namespace: company.demo\nid: greeting-app\nrevision: 1\ntriggers:\n  - id: page-home\n    type: io.kestra.plugin.amis.apps.PageTrigger\n    pageId: home\n    schema: nsfile:///home.json\n  - id: api-greet\n    type: io.kestra.plugin.amis.apps.ApiTrigger\n    apiId: greet\n    mode: " + mode + (sync ? "\n    syncTimeout: PT0.01S" : "") + "\n", Flow.class);
    }

    private static AmisHttpRouteProvider provider(PublishedFlowAccess.Snapshot snapshot, int capacity) {
        PublishedFlowAccess access = mock(PublishedFlowAccess.class);
        when(access.lookupAfterEntry(anyString(), anyString())).thenReturn(snapshot);
        when(access.withFlowExecutionPermission(any(), eq(snapshot), any())).thenAnswer(call -> invokeSupplier(call.getArgument(2)));
        return new AmisHttpRouteProvider(access, mock(PublishedFlowInvocation.class), mock(PublishedExecutionResults.class),
            mock(SchemaResolver.class), mock(RuntimeAssets.class), mock(PageDocument.class), capacity);
    }

    private static void assertTarget(AmisHttpRouteProvider provider, HttpMethod method, String path, boolean document) {
        HttpExtensionRouteProviderV1.Target target = provider.match(method, path).orElseThrow(() -> new AssertionError(path));
        assertEquals(document, target.documentReturn(), path);
        assertEquals("company.demo", target.namespace(), path);
    }

    private static void assertStatus(HttpStatus status, Runnable action) {
        HttpStatusException error = assertThrows(HttpStatusException.class, action::run);
        assertEquals(status, error.getStatus());
    }
}
