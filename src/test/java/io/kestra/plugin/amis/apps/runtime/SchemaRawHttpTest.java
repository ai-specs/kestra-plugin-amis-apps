package io.kestra.plugin.amis.apps.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.runners.ProcessedFlow;
import io.kestra.core.serializers.YamlParser;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextConfiguration;
import io.micronaut.context.DefaultApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.inject.BeanDefinitionReference;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.filters.SecurityFilter;
import io.micronaut.web.router.DefaultRouteBuilder;
import io.micronaut.web.router.RouteBuilder;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual Micronaut TCP response encoding; test identity/storage are not production authorization evidence. */
class SchemaRawHttpTest {
    @Test void schemaUtf8BytesAndDigestSurviveMicronautHttpEncodingAndIndependentReplacement() throws Exception {
        String path = "/company.demo/greeting-app/schemas/home";
        byte[] first = "{\n  \"type\": \"page\", \"title\": \"你好\", \"body\": []\n}\n".getBytes(StandardCharsets.UTF_8);
        byte[] second = "{\"type\":\"page\",\"title\":\"你好\",\"body\":[]}\n".getBytes(StandardCharsets.UTF_8);
        var source = new AtomicReference<>(first);
        Flow flow = YamlParser.parse("namespace: company.demo\nid: greeting-app\nrevision: 7\ntriggers:\n"
            + "  - id: invokeAlpha\n    type: io.kestra.plugin.amis.apps.ApiTrigger\n    apiId: alpha\n"
            + "  - id: page-home\n    type: io.kestra.plugin.amis.apps.PageTrigger\n    pageId: home\n    schema: nsfile:///home.json\n", Flow.class);
        var snapshot = new PublishedFlowAccess.Snapshot(ProcessedFlow.of(io.kestra.core.services.FlowParsingService.toFlowWithSource(flow)), RouteDeclarations.from(flow));
        var access = mock(PublishedFlowAccess.class);
        when(access.lookupAfterEntry("company.demo", "greeting-app")).thenReturn(snapshot);
        when(access.withFlowExecutionPermission(any(), same(snapshot), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(2)).get());
        var invocation = mock(PublishedFlowInvocation.class);
        when(invocation.finishRequest()).thenReturn(Mono.empty());
        var files = mock(io.kestra.webserver.rbac.FlowNamespaceFileReaderV1.class);
        when(files.read(any(), anyString(), same(snapshot.flow()), eq("company.demo"), eq("/home.json"), anyInt()))
            .thenAnswer(call -> Mono.just(Optional.of(new io.kestra.webserver.rbac.FlowNamespaceFileReaderV1.Content("company.demo", "/home.json", source.get()))));
        var results = mock(PublishedExecutionResults.class);
        var provider = new AmisHttpRouteProvider(access, invocation, results,
            new PublishedFlowSchemas(files), mock(RuntimeAssets.class), mock(PageDocument.class), 1);
        try (var context = new HttpOnlyContext()) {
            context.getEnvironment().addPropertySource(io.micronaut.context.env.PropertySource.of("p2-http-only", Map.of(
                "micronaut.server.host", "127.0.0.1", "micronaut.server.port", -1,
                "micronaut.server.netty.worker.threads", 2, "micronaut.server.netty.parent.threads", 1)));
            var filter = new SchemaFilter(provider);
            context.registerSingleton(SchemaFilter.class, filter);
            var executable = new io.micronaut.context.AbstractExecutableMethod(SchemaFilter.class, "response",
                io.micronaut.core.type.Argument.of(Publisher.class, io.micronaut.core.type.Argument.of(MutableHttpResponse.class)),
                io.micronaut.core.type.Argument.of(HttpRequest.class, "request")) {
                @Override protected Object invokeInternal(Object target, Object[] arguments) {
                    return ((SchemaFilter) target).response((HttpRequest<?>) arguments[0]);
                }
            };
            var handle = io.micronaut.inject.ExecutionHandle.of(filter, executable);
            var routes = new DefaultRouteBuilder(context) {
                {
                    buildRoute(io.micronaut.http.HttpMethod.GET, path, handle);
                    buildRoute(io.micronaut.http.HttpMethod.GET, "/company.demo/greeting-app/apis/alpha/executions/execution1", handle);
                    buildRoute(io.micronaut.http.HttpMethod.POST, "/company.demo/greeting-app/apis/alpha", handle);
                }
            };
            context.registerSingleton(RouteBuilder.class, routes);
            context.registerSingleton(io.micronaut.web.router.Router.class, new io.micronaut.web.router.DefaultRouter(routes));
            var registryConstructor = io.micronaut.http.body.DefaultMessageBodyHandlerRegistry.class
                .getDeclaredConstructor(io.micronaut.context.BeanContext.class, List.class);
            registryConstructor.setAccessible(true);
            context.registerSingleton(io.micronaut.http.body.MessageBodyHandlerRegistry.class,
                registryConstructor.newInstance(context, List.of()));
            context.registerSingleton(java.util.concurrent.ExecutorService.class,
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(), io.micronaut.inject.qualifiers.Qualifiers.byName("blocking"));
            context.registerSingleton(io.micronaut.scheduling.executor.ExecutorSelector.class,
                new io.micronaut.scheduling.executor.DefaultExecutorSelector(context,
                    () -> context.getBean(java.util.concurrent.ExecutorService.class, io.micronaut.inject.qualifiers.Qualifiers.byName("blocking")),
                    () -> context.getBean(java.util.concurrent.ExecutorService.class, io.micronaut.inject.qualifiers.Qualifiers.byName("blocking"))) { });
            context.registerSingleton(java.util.concurrent.ThreadFactory.class, Thread.ofPlatform().daemon().factory(),
                io.micronaut.inject.qualifiers.Qualifiers.byName("netty"));
            context.registerSingleton(ObjectMapper.class, new ObjectMapper().findAndRegisterModules());
            context.start();
            try (var server = context.getBean(EmbeddedServer.class).start(); var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                var request = java.net.http.HttpRequest.newBuilder(server.getURI().resolve(path))
                    .timeout(Duration.ofSeconds(10)).header("Accept", "application/json").header("Accept-Encoding", "identity")
                    .header("X-AMIS-Flow-Revision", "7").GET().build();
                var a = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                assertRawResponse(a, first);
                source.set(second);
                var b = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                assertRawResponse(b, second);
                assertNotEquals(a.headers().firstValue("X-AMIS-Schema-SHA256"), b.headers().firstValue("X-AMIS-Schema-SHA256"));
                assertEquals(new ObjectMapper().readTree(a.body()), new ObjectMapper().readTree(b.body()));
                verify(files, times(2)).read(any(), anyString(), same(snapshot.flow()), eq("company.demo"), eq("/home.json"), anyInt());
                var stale = java.net.http.HttpRequest.newBuilder(server.getURI().resolve(path))
                    .timeout(Duration.ofSeconds(10)).header("X-AMIS-Flow-Revision", "6").GET().build();
                var conflict = client.send(stale, HttpResponse.BodyHandlers.ofByteArray());
                assertRevisionConflict(conflict);
                verify(files, times(2)).read(any(), anyString(), same(snapshot.flow()), eq("company.demo"), eq("/home.json"), anyInt());
                var poll = java.net.http.HttpRequest.newBuilder(server.getURI().resolve("/company.demo/greeting-app/apis/alpha/executions/execution1"))
                    .timeout(Duration.ofSeconds(10)).GET().build();
                when(results.read(any(), same(snapshot), eq("alpha"), eq("execution1"))).thenReturn(Mono.error(new RevisionConflictException()));
                assertRevisionConflict(client.send(poll, HttpResponse.BodyHandlers.ofByteArray()));
                for (String reason : List.of("API result output is not declared by the Flow", "Declared API output is not a JSON object")) {
                    when(results.read(any(), same(snapshot), eq("alpha"), eq("execution1")))
                        .thenReturn(Mono.error(new io.micronaut.http.exceptions.HttpStatusException(io.micronaut.http.HttpStatus.CONFLICT, reason)));
                    var ordinary = client.send(poll, HttpResponse.BodyHandlers.ofByteArray());
                    assertEquals(409, ordinary.statusCode());
                    assertTrue(ordinary.headers().firstValue("X-AMIS-Error-Code").isEmpty());
                }
                var stalePost = java.net.http.HttpRequest.newBuilder(server.getURI().resolve("/company.demo/greeting-app/apis/alpha"))
                    .timeout(Duration.ofSeconds(10)).header("X-AMIS-Flow-Revision", "6")
                    .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build();
                assertRevisionConflict(client.send(stalePost, HttpResponse.BodyHandlers.ofByteArray()));
                verify(invocation, never()).submit(any(), any(), anyString(), any());
                when(access.withFlowExecutionPermission(any(), same(snapshot), any()))
                    .thenReturn(Mono.error(new io.micronaut.http.exceptions.HttpStatusException(io.micronaut.http.HttpStatus.FORBIDDEN, "denied")));
                var forbidden = client.send(stale, HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(403, forbidden.statusCode());
                assertTrue(forbidden.headers().firstValue("X-AMIS-Error-Code").isEmpty());

            }
        }
    }

    private static void assertRevisionConflict(HttpResponse<byte[]> response) throws Exception {
        assertEquals(409, response.statusCode());
        assertEquals("FLOW_REVISION_CONFLICT", response.headers().firstValue("X-AMIS-Error-Code").orElseThrow());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        var body = new ObjectMapper().readTree(response.body());
        assertEquals(new ObjectMapper().readTree("{\"status\":1,\"msg\":\"Flow declaration changed\",\"data\":{},\"code\":\"FLOW_REVISION_CONFLICT\"}"), body);
        assertFalse(body.has("revision"));
        assertFalse(body.has("flowRevision"));
        assertFalse(body.has("executionId"));
    }

    private static void assertRawResponse(HttpResponse<byte[]> response, byte[] expected) throws Exception {
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        assertArrayEquals(expected, response.body(), "Micronaut must send raw UTF-8 schema, not base64 or a byte array JSON value");
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response.body())),
            response.headers().firstValue("X-AMIS-Schema-SHA256").orElseThrow());
        assertEquals("7", response.headers().firstValue("X-AMIS-Flow-Revision").orElseThrow());
        var json = new ObjectMapper().readTree(response.body());
        assertTrue(json.isObject(), "base64 strings and numeric arrays are invalid schema responses");
        assertEquals("page", json.path("type").asText());
        assertEquals("你好", json.path("title").asText());
    }

    /** Uses a test identity only; actual provider response passes through the real Micronaut Netty server. */
    public static final class SchemaFilter implements HttpServerFilter {
        private final AmisHttpRouteProvider provider;
        SchemaFilter(AmisHttpRouteProvider provider) { this.provider = provider; }
        public Publisher<MutableHttpResponse<?>> response(HttpRequest<?> request) {
            return doFilter(request, null);
        }
        @Override public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
            request.setAttribute(SecurityFilter.AUTHENTICATION, Authentication.build("schema-http-test"));
            return provider.handle(request, provider.match(request.getMethod(), request.getUri().getRawPath()).orElseThrow());
        }
    }

    /** Framework-only context avoids loading Kestra application beans or any database/storage engine. */
    private static final class HttpOnlyContext extends DefaultApplicationContext {
        HttpOnlyContext() {
            super((ApplicationContextConfiguration) ApplicationContext.builder(Map.of(
                "micronaut.server.host", "127.0.0.1", "micronaut.server.port", -1,
                "micronaut.security.enabled", false, "micronaut.server.netty.worker.threads", 2,
                "micronaut.server.netty.parent.threads", 1))
                .enableDefaultPropertySources(false).environmentPropertySource(false).deduceEnvironment(false)
                .deduceCloudEnvironment(false).environments("p2-schema-http").banner(false));
        }
        @Override protected List<BeanDefinitionReference> resolveBeanDefinitionReferences() {
            return super.resolveBeanDefinitionReferences().stream().filter(HttpOnlyContext::framework).toList();
        }
        @Override protected List<BeanDefinitionReference> resolveBeanDefinitionReferences(Predicate<BeanDefinitionReference> predicate) {
            return super.resolveBeanDefinitionReferences(predicate).stream().filter(HttpOnlyContext::framework).toList();
        }
        private static boolean framework(BeanDefinitionReference reference) {
            String name = reference.getName();
            return name.startsWith("io.micronaut.") && !name.startsWith("io.micronaut.security.")
                && !name.startsWith("io.micronaut.data.") && !name.startsWith("io.micronaut.configuration.") && !name.startsWith("io.micronaut.grpc.");
        }
    }
}
