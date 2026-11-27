package io.kestra.plugin.amis.apps.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.repositories.AuthorizationEvaluationContext;
import io.kestra.core.repositories.ExecutionRepositoryInterface;
import io.kestra.core.repositories.FlowRepositoryInterface;
import io.kestra.core.runners.FlowInputOutput;
import io.kestra.core.runners.FlowMetaStoreInterface;
import io.kestra.core.services.ConditionService;
import io.kestra.core.services.ExecutionOutputService;
import io.kestra.core.services.FlowService;
import io.kestra.core.services.WebhookService;
import io.kestra.webserver.rbac.FlowNamespaceFileReaderV1;
import io.kestra.core.tenant.TenantService;
import io.kestra.plugin.amis.apps.domain.ApiConfiguration;
import io.kestra.plugin.amis.apps.domain.Identifiers;
import io.kestra.plugin.amis.apps.domain.RuntimeLimits;
import io.kestra.webserver.rbac.HttpExtensionRouteProviderV1;
import io.kestra.webserver.rbac.RbacConfiguration;
import io.kestra.webserver.rbac.RbacExecutionGate;
import io.kestra.webserver.rbac.RbacGate;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.ServerHttpRequest;
import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.csrf.CsrfConfiguration;
import io.micronaut.security.csrf.generator.CsrfTokenGenerator;
import io.micronaut.security.csrf.validator.CsrfTokenValidator;
import io.micronaut.security.filters.SecurityFilter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Singleton
@Requires(property = "kestra.amis-apps.enabled", value = "true")
public final class AmisHttpRouteProvider implements HttpExtensionRouteProviderV1 {
    private final PublishedFlowAccess access;
    private final PublishedFlowInvocation invocation;
    private final PublishedExecutionResults results;
    private final SchemaLookup schemas;
    private final RuntimeAssets assets;
    private final PageDocument documents;
    private final JsonInputParser parser = new JsonInputParser(1_048_576, 64);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Semaphore syncCapacity;
    private final Duration bodyReadTimeout;
    private final long pollTimeoutMs;

    @Inject
    public AmisHttpRouteProvider(FlowRepositoryInterface flows, FlowMetaStoreInterface metadata, TenantService tenants,
        RbacConfiguration configuration, RbacGate gate, RbacExecutionGate executionGate, FlowInputOutput inputs,
        FlowService flowService, ConditionService conditions, WebhookService webhooks,
        ExecutionRepositoryInterface executions, ExecutionOutputService outputService, FlowNamespaceFileReaderV1 namespaceFiles,
        CsrfConfiguration csrf, CsrfTokenGenerator<HttpRequest<?>> generator, CsrfTokenValidator<HttpRequest<?>> validator,
        @Value("${kestra.amis-apps.max-sync-concurrency:4}") int maxSyncConcurrency,
        @Value("${kestra.amis-apps.request-body-timeout:PT30S}") Duration bodyReadTimeout,
        @Value("${kestra.amis-apps.poll-timeout:PT5M}") Duration pollTimeout) {
        this.access = new PublishedFlowAccess(flows, metadata, tenants, configuration, gate);
        this.invocation = new PublishedFlowInvocation(executionGate, inputs, flowService, conditions, webhooks);
        this.results = new PublishedExecutionResults(access, executions, outputService::getOutputs, configuration, gate);
        this.assets = new RuntimeAssets();
        this.documents = new PageDocument(assets, csrf, generator, validator);
        this.schemas = new PublishedFlowSchemas(namespaceFiles)::resolve;
        this.syncCapacity = new Semaphore(new RuntimeLimits(maxSyncConcurrency).maxSyncConcurrency());
        this.bodyReadTimeout = checkedBodyTimeout(bodyReadTimeout);
        this.pollTimeoutMs = checkedPollTimeout(pollTimeout);
    }

    AmisHttpRouteProvider(PublishedFlowAccess access, PublishedFlowInvocation invocation, PublishedExecutionResults results,
        SchemaResolver schemas, RuntimeAssets assets, PageDocument documents, int maxSyncConcurrency) {
        this(access, invocation, results, schemas, assets, documents, maxSyncConcurrency, Duration.ofSeconds(30));
    }

    AmisHttpRouteProvider(PublishedFlowAccess access, PublishedFlowInvocation invocation, PublishedExecutionResults results,
        SchemaResolver schemas, RuntimeAssets assets, PageDocument documents, int maxSyncConcurrency, Duration bodyReadTimeout) {
        this.access = Objects.requireNonNull(access);
        this.invocation = Objects.requireNonNull(invocation);
        this.results = Objects.requireNonNull(results);
        Objects.requireNonNull(schemas);
        this.schemas = (identity, snapshot, namespace, uri) -> Mono.fromCallable(() -> schemas.resolve(namespace, uri));
        this.assets = Objects.requireNonNull(assets);
        this.documents = Objects.requireNonNull(documents);
        this.syncCapacity = new Semaphore(new RuntimeLimits(maxSyncConcurrency).maxSyncConcurrency());
        this.bodyReadTimeout = checkedBodyTimeout(bodyReadTimeout);
        this.pollTimeoutMs = checkedPollTimeout(Duration.ofMinutes(5));
    }

    AmisHttpRouteProvider(PublishedFlowAccess access, PublishedFlowInvocation invocation, PublishedExecutionResults results,
        PublishedFlowSchemas schemas, RuntimeAssets assets, PageDocument documents, int maxSyncConcurrency) {
        this.access = Objects.requireNonNull(access);
        this.invocation = Objects.requireNonNull(invocation);
        this.results = Objects.requireNonNull(results);
        this.schemas = Objects.requireNonNull(schemas)::resolve;
        this.assets = Objects.requireNonNull(assets);
        this.documents = Objects.requireNonNull(documents);
        this.syncCapacity = new Semaphore(new RuntimeLimits(maxSyncConcurrency).maxSyncConcurrency());
        this.bodyReadTimeout = Duration.ofSeconds(30);
        this.pollTimeoutMs = checkedPollTimeout(Duration.ofMinutes(5));
    }

    @FunctionalInterface
    private interface SchemaLookup {
        Mono<Optional<SchemaResolver.ResolvedSchema>> resolve(Authentication identity,
            PublishedFlowAccess.Snapshot snapshot, String namespace, String uri);
    }

    @Override public String id() { return "amis-apps"; }

    @Override public Optional<Target> match(HttpMethod method, String rawPath) {
        return route(method, rawPath).map(route -> new Target(route.namespace(), route.kind().equals("pages")));
    }

    @Override public Publisher<MutableHttpResponse<?>> handle(HttpRequest<?> request, Target target) {
        return Mono.<MutableHttpResponse<?>>defer(() -> {
            Route route = route(request.getMethod(), request.getUri().getRawPath())
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND));
            if (!route.namespace().equals(target.namespace()) || target.documentReturn() != route.kind().equals("pages")) {
                throw failure(HttpStatus.FORBIDDEN);
            }
            Authentication identity = request.getAttribute(SecurityFilter.AUTHENTICATION, Authentication.class).orElse(null);
            if (identity == null || identity.getName() == null || identity.getName().isBlank()) throw failure(HttpStatus.UNAUTHORIZED);
            return AuthorizationEvaluationContext.call(() -> access.lookupAfterEntry(route.namespace(), route.flowId()))
                .flatMap(snapshot -> invocation.finishRequest().then(Mono.defer(() -> access.withFlowExecutionPermission(identity, snapshot,
                    () -> {
                        requireExpectedRevision(request, snapshot);
                        return dispatch(request, identity, route, snapshot)
                            .map(response -> response.header("X-AMIS-Flow-Revision", snapshot.declarations().flowRevision().toString()));
                    }))));
        }).switchIfEmpty(Mono.error(failure(HttpStatus.SERVICE_UNAVAILABLE)))
            .onErrorResume(RevisionConflictException.class, conflict -> Mono.<MutableHttpResponse<?>>just(HttpResponse.status(HttpStatus.CONFLICT)
                .header("X-AMIS-Error-Code", "FLOW_REVISION_CONFLICT")
                .contentType("application/json")
                .body(Map.of("status", 1, "msg", "Flow declaration changed", "data", Map.of(), "code", "FLOW_REVISION_CONFLICT"))))
            .<MutableHttpResponse<?>>map(response -> response.header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff"))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<MutableHttpResponse<?>> dispatch(HttpRequest<?> request, Authentication identity, Route route,
                                                 PublishedFlowAccess.Snapshot snapshot) {
        return switch (route.kind()) {
            case "pages" -> {
                requirePage(snapshot, route.item());
                String prefix = "/" + route.namespace() + "/" + route.flowId();
                var bootstrap = new java.util.LinkedHashMap<String, Object>();
                bootstrap.put("version", 1);
                bootstrap.put("namespace", route.namespace());
                bootstrap.put("flowId", route.flowId());
                bootstrap.put("pageId", route.item());
                bootstrap.put("flowRevision", snapshot.declarations().flowRevision());
                bootstrap.put("pageIds", snapshot.declarations().pages().stream().filter(page -> !page.disabled()).map(RouteDeclarations.Page::pageId).toList());
                bootstrap.put("apiIds", snapshot.declarations().apis().stream().filter(api -> !api.disabled()).map(RouteDeclarations.Api::apiId).toList());
                bootstrap.put("schemaUrl", prefix + "/schemas/" + route.item());
                bootstrap.put("apiBase", prefix + "/apis/");
                bootstrap.put("assetBase", prefix + "/assets/");
                bootstrap.put("limits", Map.of("requestTimeoutMs", 30000, "pollIntervalMs", 250, "pollTimeoutMs", pollTimeoutMs));
                yield Mono.fromCallable(() -> documents.response(request, prefix + "/", mapper.writeValueAsString(bootstrap)));
            }
            case "schemas" -> {
                var page = requirePage(snapshot, route.item());
                yield schemas.resolve(identity, snapshot, route.namespace(), "nsfile://" + page.schema().path())
                    .map(schema -> {
                        var resolved = schema.orElseThrow(() -> failure(HttpStatus.NOT_FOUND));
                        return HttpResponse.ok(resolved.bytes()).header("X-AMIS-Schema-SHA256", resolved.sha256())
                            .contentType("application/json");
                    });
            }
            case "assets" -> {
                if (snapshot.declarations().pages().isEmpty()
                    || snapshot.declarations().pages().stream().allMatch(RouteDeclarations.Page::disabled)) throw failure(HttpStatus.NOT_FOUND);
                var asset = assets.asset("assets/" + route.item()).orElseThrow(() -> failure(HttpStatus.NOT_FOUND));
                byte[] payload = asset.bytes();
                MutableHttpResponse<?> response = request.getMethod() == HttpMethod.HEAD ? HttpResponse.ok() : HttpResponse.ok(payload);
                yield Mono.just(response.contentType(asset.contentType()).contentLength(payload.length));
            }
            case "poll" -> results.read(identity, snapshot, route.item(), route.executionId()).map(this::envelope);
            case "apis" -> submit(request, identity, snapshot, route.item());
            default -> Mono.error(failure(HttpStatus.NOT_FOUND));
        };
    }

    private void requireExpectedRevision(HttpRequest<?> request, PublishedFlowAccess.Snapshot snapshot) {
        var values = request.getHeaders().getAll("X-AMIS-Flow-Revision");
        if (values.isEmpty()) return;
        if (values.size() != 1 || !values.getFirst().matches("[1-9][0-9]{0,9}")) throw failure(HttpStatus.BAD_REQUEST);
        final int expected;
        try { expected = Integer.parseInt(values.getFirst()); }
        catch (NumberFormatException invalid) { throw failure(HttpStatus.BAD_REQUEST); }
        if (expected != snapshot.declarations().flowRevision()) throw new RevisionConflictException();
    }

    private Mono<MutableHttpResponse<?>> submit(HttpRequest<?> request, Authentication identity,
        PublishedFlowAccess.Snapshot snapshot, String apiId) {
        var selected = snapshot.declarations().api(snapshot.declarations().namespace(), snapshot.declarations().flowId(), apiId);
        if (selected.status() != RouteDeclarations.Status.ACTIVE) return Mono.error(failure(HttpStatus.NOT_FOUND));
        boolean synchronous = selected.declaration().configuration().mode() == ApiConfiguration.Mode.SYNC;
        return Mono.using(() -> {
            if (synchronous && !syncCapacity.tryAcquire()) throw failure(HttpStatus.TOO_MANY_REQUESTS);
            return synchronous;
        }, acquired -> readInputs(request).flatMap(body -> invocation.submit(identity, snapshot, apiId, body))
            .flatMap(submitted -> invocation.finishRequest().then(Mono.defer(() -> {
                ApiResponseEnvelope pending = ApiResponseEnvelope.pending(snapshot.declarations(), apiId, submitted.execution().getId(),
                    new ApiResponseEnvelope.Binding(snapshot.declarations().namespace(), snapshot.declarations().flowId(), apiId,
                        submitted.execution().getId(), submitted.declaration().resultOutput()), submitted.execution().getState().getCurrent().name());
                if (!synchronous) return Mono.just(envelope(pending));
                return Mono.defer(() -> results.read(identity, snapshot, apiId, submitted.execution().getId()))
                    .switchIfEmpty(Mono.error(failure(HttpStatus.SERVICE_UNAVAILABLE)))
                    .expand(result -> result.resultReady() ? Mono.empty()
                        : Mono.delay(Duration.ofMillis(100)).flatMap(ignored -> results.read(identity, snapshot, apiId, submitted.execution().getId())))
                    .filter(ApiResponseEnvelope::resultReady).next()
                    .timeout(selected.declaration().configuration().syncTimeout(), Mono.just(pending))
                    .map(this::envelope);
            }))), acquired -> { if (acquired) syncCapacity.release(); });
    }

    private Mono<Map<String, Object>> readInputs(HttpRequest<?> request) {
        if (!request.getContentType().map(type -> type.matches(io.micronaut.http.MediaType.APPLICATION_JSON_TYPE)).orElse(false)) {
            return Mono.error(failure(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
        }
        Mono<byte[]> bytes;
        if (request instanceof ServerHttpRequest<?> server) {
            bytes = Mono.using(server.byteBody()::move, body -> Mono.fromCallable(() -> {
                try (var input = body.toInputStream()) { return parser.readBounded(input); }
            }).subscribeOn(Schedulers.boundedElastic()), CloseableByteBody::close);
        } else {
            bytes = Mono.fromSupplier(() -> request.getBody(byte[].class).orElseThrow(() -> failure(HttpStatus.BAD_REQUEST)));
        }
        return bytes.timeout(bodyReadTimeout)
            .onErrorMap(java.util.concurrent.TimeoutException.class, failure -> failure(HttpStatus.REQUEST_TIMEOUT))
            .map(body -> mapper.convertValue(parser.parse(body), new TypeReference<Map<String, Object>>() { }))
            .onErrorMap(IllegalArgumentException.class, failure -> failure(HttpStatus.BAD_REQUEST));
    }

    private MutableHttpResponse<?> envelope(ApiResponseEnvelope value) {
        return HttpResponse.status(HttpStatus.valueOf(value.httpStatus())).body(value).contentType("application/json");
    }

    private static RouteDeclarations.Page requirePage(PublishedFlowAccess.Snapshot snapshot, String pageId) {
        var selected = snapshot.declarations().page(snapshot.declarations().namespace(), snapshot.declarations().flowId(), pageId);
        if (selected.status() != RouteDeclarations.Status.ACTIVE) throw failure(HttpStatus.NOT_FOUND);
        return selected.declaration();
    }

    private static Optional<Route> route(HttpMethod method, String path) {
        if (method == null || path == null || path.length() > 1024 || path.contains("%") || path.contains("\\") || path.contains("//")) return Optional.empty();
        String[] parts = path.split("/", -1);
        if (parts.length < 5 || !parts[0].isEmpty()) return Optional.empty();
        try {
            Identifiers.namespace(parts[1]); Identifiers.flowId(parts[2]);
            String kind = parts[3];
            if (kind.equals("assets") && (method == HttpMethod.GET || method == HttpMethod.HEAD)) {
                String asset = path.substring(("/" + parts[1] + "/" + parts[2] + "/assets/").length());
                if (asset.isEmpty() || asset.startsWith(".") || asset.contains("/.") || !asset.matches("[A-Za-z0-9_/-]+(?:[.][A-Za-z0-9_-]+)*")) return Optional.empty();
                return Optional.of(new Route(parts[1], parts[2], kind, asset, null));
            }
            Identifiers.id(parts[4]);
            if (parts.length == 5 && ((kind.equals("pages") || kind.equals("schemas")) && method == HttpMethod.GET
                || kind.equals("apis") && method == HttpMethod.POST)) return Optional.of(new Route(parts[1], parts[2], kind, parts[4], null));
            if (parts.length == 7 && kind.equals("apis") && parts[5].equals("executions") && method == HttpMethod.GET) {
                Identifiers.id(parts[6]);
                return Optional.of(new Route(parts[1], parts[2], "poll", parts[4], parts[6]));
            }
            return Optional.empty();
        } catch (IllegalArgumentException failure) { return Optional.empty(); }
    }

    private static HttpStatusException failure(HttpStatus status) { return new HttpStatusException(status, "AMIS request cannot be fulfilled"); }
    static long checkedPollTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()
            || timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Poll timeout must be at least 1 millisecond and at most 5 minutes");
        }
        return timeout.toMillis();
    }

    private static Duration checkedBodyTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Request body timeout must be positive and at most 30 seconds");
        }
        return timeout;
    }
    private record Route(String namespace, String flowId, String kind, String item, String executionId) { }
}
