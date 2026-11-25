package io.kestra.plugin.amis.apps.runtime;

import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.flows.FlowWithException;
import io.kestra.core.repositories.FlowRepositoryInterface;
import io.kestra.core.runners.FlowMetaStoreInterface;
import io.kestra.core.runners.ProcessedFlow;
import io.kestra.core.tenant.TenantService;
import io.kestra.plugin.amis.apps.domain.Identifiers;
import io.kestra.webserver.rbac.RbacAuthorizer;
import io.kestra.webserver.rbac.RbacConfiguration;
import io.kestra.webserver.rbac.RbacGate;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.authentication.Authentication;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

public final class PublishedFlowAccess {
    private final FlowRepositoryInterface flows;
    private final FlowMetaStoreInterface metadata;
    private final TenantService tenants;
    private final RbacConfiguration configuration;
    private final RbacGate gate;

    public PublishedFlowAccess(FlowRepositoryInterface flows, FlowMetaStoreInterface metadata, TenantService tenants,
                               RbacConfiguration configuration, RbacGate gate) {
        this.flows = Objects.requireNonNull(flows);
        this.metadata = Objects.requireNonNull(metadata);
        this.tenants = Objects.requireNonNull(tenants);
        this.configuration = Objects.requireNonNull(configuration);
        this.gate = Objects.requireNonNull(gate);
    }

    public Snapshot lookupAfterEntry(String namespace, String flowId) {
        Identifiers.namespace(namespace);
        Identifiers.flowId(flowId);
        String tenant = storageTenant();
        Flow stored = flows.findByIdForExecutionWithoutAcl(tenant, namespace, flowId)
            .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Application route not found"));
        requireTuple(stored, namespace, flowId);
        if (stored instanceof FlowWithException || stored.isDeleted() || stored.isDisabled() || stored.getRevision() == null) {
            throw new HttpStatusException(HttpStatus.CONFLICT, "Flow is not active for application routing");
        }
        ProcessedFlow processed = metadata.findByIdForRuntime(tenant, namespace, flowId, Optional.of(stored.getRevision()))
            .orElseThrow(() -> new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Flow activation is not available"));
        Flow runtime = processed.flow();
        requireTuple(runtime, namespace, flowId);
        if (runtime instanceof FlowWithException || runtime.isDeleted() || runtime.isDisabled()
            || !Objects.equals(stored.getRevision(), runtime.getRevision())) {
            throw new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Flow activation does not match storage");
        }
        return new Snapshot(processed, RouteDeclarations.from(runtime));
    }

    public <Result> Mono<Result> withFlowExecutionPermission(Authentication authentication, Snapshot snapshot,
                                                            Supplier<Mono<Result>> action) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(action);
        if (snapshot.declarations().activation() != RouteDeclarations.Status.ACTIVE) {
            return Mono.error(new HttpStatusException(HttpStatus.CONFLICT, "Application declarations are not active"));
        }
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return Mono.error(new HttpStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
        }
        if (!configuration.enabled()) {
            return Mono.defer(action);
        }
        var principal = new RbacAuthorizer.Principal(authentication.getName(), List.copyOf(authentication.getRoles()), null, null, List.of());
        var resource = new RbacAuthorizer.Resource(RbacAuthorizer.Kind.FLOW, RbacAuthorizer.Scope.NAMESPACE,
            "main", snapshot.declarations().namespace(), snapshot.declarations().flowId());
        return gate.boundary(principal, "main", () -> gate.authorize(principal, resource, RbacAuthorizer.Action.READ).defaultIfEmpty(false)
            .flatMap(allowed -> Boolean.TRUE.equals(allowed)
                ? gate.authorize(principal, resource, RbacAuthorizer.Action.EXECUTE).defaultIfEmpty(false) : Mono.just(false))
            .flatMap(allowed -> Boolean.TRUE.equals(allowed) ? Mono.defer(action)
                : Mono.error(new HttpStatusException(HttpStatus.FORBIDDEN, "Flow execution permission required"))))
            .singleOrEmpty();
    }

    public String storageTenant() {
        String tenant = tenants.resolveTenant();
        if (tenant != null && !tenant.equals("main")) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Only the configured single tenant is supported");
        }
        return tenant;
    }

    private static void requireTuple(Flow flow, String namespace, String flowId) {
        if (flow == null || !namespace.equals(flow.getNamespace()) || !flowId.equals(flow.getId())
            || flow.getTenantId() != null && !flow.getTenantId().equals("main")) {
            throw new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Flow lookup returned an invalid target");
        }
    }

    public record Snapshot(ProcessedFlow processed, RouteDeclarations declarations) {
        public Snapshot {
            Objects.requireNonNull(processed);
            Objects.requireNonNull(declarations);
            Flow flow = processed.flow();
            requireTuple(flow, declarations.namespace(), declarations.flowId());
            if (!Objects.equals(flow.getRevision(), declarations.flowRevision())) {
                throw new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Flow snapshot revision is inconsistent");
            }
        }
        public Flow flow() { return processed.flow(); }
    }
}
