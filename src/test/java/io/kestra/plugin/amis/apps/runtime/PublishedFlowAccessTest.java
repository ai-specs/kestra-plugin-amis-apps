package io.kestra.plugin.amis.apps.runtime;

import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.flows.FlowWithSource;
import io.kestra.core.repositories.FlowRepositoryInterface;
import io.kestra.core.runners.FlowMetaStoreInterface;
import io.kestra.core.runners.ProcessedFlow;
import io.kestra.core.serializers.YamlParser;
import io.kestra.core.tenant.TenantService;
import io.kestra.plugin.amis.apps.testing.PackagedTriggerMain;
import io.kestra.webserver.rbac.RbacAuthorizer;
import io.kestra.webserver.rbac.RbacConfiguration;
import io.kestra.webserver.rbac.RbacGate;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.authentication.Authentication;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

class PublishedFlowAccessTest {
    private final Flow stored = YamlParser.parse(PackagedTriggerMain.coreFlow() + PackagedTriggerMain.declarations(), Flow.class)
        .toBuilder().revision(1).build();
    private final AtomicInteger lookups = new AtomicInteger();
    private final AtomicInteger runtimeLookups = new AtomicInteger();
    private final List<Object> tenants = new ArrayList<>();
    private Optional<Flow> database = Optional.of(stored);
    private Optional<ProcessedFlow> runtime = Optional.of(ProcessedFlow.of(FlowWithSource.of(stored, "test-only-source")));
    private final RecordingGate gate = new RecordingGate();

    private PublishedFlowAccess access(boolean enabled) {
        return access(enabled, new TenantService());
    }

    private PublishedFlowAccess access(boolean enabled, TenantService tenantService) {
        FlowRepositoryInterface repository = (FlowRepositoryInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{FlowRepositoryInterface.class}, (instance, method, arguments) -> {
                if (!method.getName().equals("findByIdForExecutionWithoutAcl")) throw new AssertionError("Unexpected repository scan: " + method.getName());
                lookups.incrementAndGet(); tenants.add(arguments[0]);
                assertEquals("company._team", arguments[1]); assertEquals("model.test", arguments[2]);
                return database;
            });
        FlowMetaStoreInterface metadata = (FlowMetaStoreInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{FlowMetaStoreInterface.class}, (instance, method, arguments) -> {
                if (!method.getName().equals("findByIdForRuntime")) throw new AssertionError("Unexpected runtime scan: " + method.getName());
                runtimeLookups.incrementAndGet(); tenants.add(arguments[0]); assertEquals(Optional.of(1), arguments[3]);
                return runtime;
            });
        return new PublishedFlowAccess(repository, metadata, tenantService, new RbacConfiguration(enabled, false), gate);
    }

    private PublishedFlowAccess.Snapshot snapshot(PublishedFlowAccess access) {
        return access.lookupAfterEntry("company._team", "model.test");
    }

    @Test void preciseLookupUsesPublishedTenantResolutionAndMatchingRevisionWithoutScanning() {
        var snapshot = snapshot(access(true));
        assertEquals(stored.getId(), snapshot.flow().getId());
        assertEquals(1, lookups.get()); assertEquals(1, runtimeLookups.get());
        assertEquals(2, tenants.size());
        assertTrue(tenants.stream().allMatch(value -> java.util.Objects.equals(value, new TenantService().resolveTenant())));
    }

    @Test void explicitNullStorageTenantIsPreservedWithoutGuessingFromTheUrl() {
        snapshot(access(true, new TenantService() { @Override public String resolveTenant() { return null; } }));
        assertEquals(2, tenants.size()); assertTrue(tenants.stream().allMatch(value -> value == null));
    }

    @Test void missingFlowIs404AndDoesNotConsultRuntimeMetadata() {
        database = Optional.empty();
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(HttpStatusException.class, () -> snapshot(access(true))).getStatus());
        assertEquals(0, runtimeLookups.get());
    }

    @Test void malformedIdentityIsRejectedBeforeAnyLookup() {
        assertThrows(IllegalArgumentException.class, () -> access(true).lookupAfterEntry("company.*", "model.test"));
        assertThrows(IllegalArgumentException.class, () -> access(true).lookupAfterEntry("company._team", "../other"));
        assertEquals(0, lookups.get());
    }

    @Test void disabledOrDeletedFlowCannotFallBackToRuntime() {
        for (Flow value : List.of(stored.toBuilder().disabled(true).build(), stored.toDeleted())) {
            database = Optional.of(value);
            assertEquals(HttpStatus.CONFLICT, assertThrows(HttpStatusException.class, () -> snapshot(access(true))).getStatus());
        }
        assertEquals(0, runtimeLookups.get());
    }

    @Test void absentOrStaleRuntimeNeverFallsBackToRawStoredFlow() {
        runtime = Optional.empty();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(HttpStatusException.class, () -> snapshot(access(true))).getStatus());
        runtime = Optional.of(ProcessedFlow.of(FlowWithSource.of(stored.toBuilder().revision(2).build(), "stale")));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(HttpStatusException.class, () -> snapshot(access(true))).getStatus());
    }

    @Test void wrongNamespaceFlowOrTenantReturnedByStorageFailsClosed() {
        for (Flow value : List.of(stored.toBuilder().namespace("other").build(), stored.toBuilder().id("other").build(),
            stored.toBuilder().tenantId("other").build())) {
            database = Optional.of(value);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(HttpStatusException.class, () -> snapshot(access(true))).getStatus());
        }
        assertEquals(0, runtimeLookups.get());
    }

    @Test void modeBRequiresReadThenExecuteBeforeCallingTheAction() {
        var access = access(true); var snapshot = snapshot(access); var actions = new AtomicInteger();
        assertEquals("allowed", access.withFlowExecutionPermission(Authentication.build("ordinary"), snapshot,
            () -> {actions.incrementAndGet(); return Mono.just("allowed");}).block());
        assertEquals(List.of(RbacAuthorizer.Action.READ, RbacAuthorizer.Action.EXECUTE), gate.actions);
        assertEquals(1, actions.get()); assertEquals(1, gate.closed.get());
    }

    @Test void deniedOrMissingDecisionNeverRunsSchemaOrBusinessAction() {
        for (RbacAuthorizer.Action denied : List.of(RbacAuthorizer.Action.READ, RbacAuthorizer.Action.EXECUTE)) {
            var access = access(true); var snapshot = snapshot(access); gate.denied = denied;
            var failure = assertThrows(HttpStatusException.class, () -> access.withFlowExecutionPermission(Authentication.build("ordinary"), snapshot,
                () -> Mono.error(new AssertionError("Action must not run"))).block());
            assertEquals(HttpStatus.FORBIDDEN, failure.getStatus());
        }
        gate.denied = null; gate.empty = true;
        var access = access(true); var snapshot = snapshot(access);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(HttpStatusException.class, () -> access.withFlowExecutionPermission(Authentication.build("ordinary"), snapshot,
            () -> Mono.error(new AssertionError("Action must not run"))).block()).getStatus());
    }

    @Test void modeAStillRequiresAuthenticationButDoesNotInventRbacChecks() {
        var access = access(false); var snapshot = snapshot(access);
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(HttpStatusException.class, () -> access.withFlowExecutionPermission(null, snapshot,
            () -> Mono.error(new AssertionError("Anonymous action must not run"))).block()).getStatus());
        assertEquals("ordinary-user", access.withFlowExecutionPermission(Authentication.build("not-first-admin"), snapshot,
            () -> Mono.just("ordinary-user")).block());
        assertTrue(gate.actions.isEmpty());
    }

    @Test void snapshotRejectsConfusedFlowAndDeclarationPairs() {
        var metadata = ProcessedFlow.of(FlowWithSource.of(stored.toBuilder().id("other").build(), "other"));
        assertThrows(HttpStatusException.class, () -> new PublishedFlowAccess.Snapshot(metadata, RouteDeclarations.from(stored)));
    }

    private static final class RecordingGate extends RbacGate {
        private final List<RbacAuthorizer.Action> actions = new ArrayList<>();
        private final AtomicInteger closed = new AtomicInteger();
        private RbacAuthorizer.Action denied;
        private boolean empty;
        private RecordingGate() { super(null); }
        @Override public Mono<Boolean> authorize(RbacAuthorizer.Principal principal, RbacAuthorizer.Resource resource, RbacAuthorizer.Action action) {
            assertEquals("main", resource.tenant()); assertEquals("company._team", resource.namespace()); assertEquals("model.test", resource.id());
            actions.add(action); return empty ? Mono.empty() : Mono.just(action != denied);
        }
        @Override public <Result> Flux<Result> boundary(RbacAuthorizer.Principal principal, String tenant,
                                                       Supplier<? extends Publisher<Result>> action) {
            return Flux.defer(action).doFinally(signal -> closed.incrementAndGet());
        }
    }
}
