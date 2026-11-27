package io.kestra.plugin.amis.apps.runtime;

import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.executions.ExecutionTrigger;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.flows.FlowWithSource;
import io.kestra.core.models.flows.State;
import io.kestra.core.repositories.ExecutionRepositoryInterface;
import io.kestra.core.repositories.FlowRepositoryInterface;
import io.kestra.core.runners.FlowMetaStoreInterface;
import io.kestra.core.runners.ProcessedFlow;
import io.kestra.core.serializers.YamlParser;
import io.kestra.core.tenant.TenantService;
import io.kestra.plugin.amis.apps.ApiTrigger;
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
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

class PublishedExecutionResultsTest {
    private final Flow flow = YamlParser.parse(PackagedTriggerMain.coreFlow() + PackagedTriggerMain.declarations()
        + "outputs:\n  - id: response\n    type: JSON\n    value: {}\n", Flow.class)
        .toBuilder().revision(1).build();
    private final PublishedFlowAccess.Snapshot snapshot = new PublishedFlowAccess.Snapshot(
        ProcessedFlow.of(FlowWithSource.of(flow, "test-source")), RouteDeclarations.from(flow));
    private final ApiTrigger trigger = flow.getTriggers().stream().filter(ApiTrigger.class::isInstance).map(ApiTrigger.class::cast).findFirst().orElseThrow();
    private final List<String> trace = new ArrayList<>();
    private String denied;
    private Optional<Execution> stored = Optional.of(execution(State.Type.SUCCESS));
    private Map<String, Object> values = Map.of("response", Map.of("greeting", "Hello Ada"), "sibling", Map.of("private", "not-this-api"));

    private Execution execution(State.Type state) {
        return Execution.newExecution(flow, List.of()).toBuilder().id("execution123").originalId("execution123")
            .flowRevision(1).state(new State(state))
            .trigger(ExecutionTrigger.of(trigger, Map.of("apiId", trigger.getApiId(), "caller", "test-user", "declarationRevision", 1))).build();
    }

    private PublishedExecutionResults service(boolean enabled) {
        var gate = new RbacGate(null) {
            @Override public Mono<Boolean> authorize(RbacAuthorizer.Principal principal, RbacAuthorizer.Resource resource, RbacAuthorizer.Action action) {
                String operation = resource.kind() + "." + action;
                trace.add(operation); return Mono.just(!operation.equals(denied));
            }
            @Override public <Result> Flux<Result> boundary(RbacAuthorizer.Principal principal, String tenant, Supplier<? extends Publisher<Result>> action) {
                return Flux.defer(action);
            }
        };
        var repository = (ExecutionRepositoryInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{ExecutionRepositoryInterface.class}, (instance, method, arguments) -> {
                if (!method.getName().equals("findByIdWithoutAcl")) throw new AssertionError("Unexpected execution scan");
                trace.add("lookup"); assertEquals("execution123", arguments[1]); return stored;
            });
        FlowRepositoryInterface flows = (FlowRepositoryInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{FlowRepositoryInterface.class}, (instance, method, arguments) -> {throw new AssertionError("No additional Flow lookup expected");});
        FlowMetaStoreInterface metadata = (FlowMetaStoreInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{FlowMetaStoreInterface.class}, (instance, method, arguments) -> {throw new AssertionError("No additional metadata lookup expected");});
        var configuration = new RbacConfiguration(enabled, false);
        var access = new PublishedFlowAccess(flows, metadata, new TenantService(), configuration, gate);
        return new PublishedExecutionResults(access, repository, execution -> {trace.add("outputs");return values;}, configuration, gate);
    }

    private ApiResponseEnvelope read(boolean enabled) {
        return service(enabled).read(Authentication.build("ordinary-user"), snapshot, trigger.getApiId(), "execution123").block();
    }

    @Test void missingDeclaredOutputRejectsWithoutExecutionLookupOrOutputRead() {
        Flow missing = flow.toBuilder().outputs(List.of()).build();
        var missingSnapshot = new PublishedFlowAccess.Snapshot(
            ProcessedFlow.of(FlowWithSource.of(missing, "missing-output-source")), RouteDeclarations.from(missing));
        HttpStatusException failure = assertThrows(HttpStatusException.class, () -> service(true)
            .read(Authentication.build("ordinary-user"), missingSnapshot, trigger.getApiId(), "execution123").block());
        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        assertFalse(trace.contains("lookup"));
        assertFalse(trace.contains("outputs"));
    }

    @Test void fullAuthorizationPlanPrecedesExactLookupAndSelectedOutputRead() {
        var response = read(true);
        assertNotNull(response); assertEquals(200, response.httpStatus()); assertEquals(0, response.status());
        assertEquals("Hello Ada", response.data().path("greeting").asText());
        assertFalse(response.data().has("sibling")); assertFalse(response.data().toString().contains("not-this-api"));
        assertEquals(List.of("FLOW.READ", "FLOW.EXECUTE", "EXECUTION.READ", "EXECUTION.ACCESS_OUTPUTS", "lookup", "outputs"), trace);
    }

    @Test void eachPermissionDenialStopsBeforeExecutionLookupOrOutputStorage() {
        for (String operation : List.of("FLOW.READ", "FLOW.EXECUTE", "EXECUTION.READ", "EXECUTION.ACCESS_OUTPUTS")) {
            trace.clear(); denied = operation;
            var failure = assertThrows(HttpStatusException.class, () -> read(true));
            assertEquals(HttpStatus.FORBIDDEN, failure.getStatus());
            assertFalse(trace.contains("lookup")); assertFalse(trace.contains("outputs"));
        }
    }

    @Test void runningAndFailureStatesNeverReadOutputStorage() {
        for (State.Type state : List.of(State.Type.RUNNING, State.Type.FAILED, State.Type.KILLED, State.Type.CANCELLED)) {
            trace.clear(); stored = Optional.of(execution(state));
            var response = read(true); assertNotNull(response);
            assertEquals(state == State.Type.RUNNING ? 202 : 200, response.httpStatus());
            assertEquals(state == State.Type.RUNNING ? 0 : 1, response.status());
            assertFalse(trace.contains("outputs"));
        }
    }

    @Test void missingExecutionReturns404AfterAuthorizationWithoutReadingOutputs() {
        stored = Optional.empty();
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(HttpStatusException.class, () -> read(true)).getStatus());
        assertTrue(trace.contains("lookup")); assertFalse(trace.contains("outputs"));
    }

    @Test void foreignNamespaceFlowApiTriggerAndExecutionIdsCannotReturnResults() {
        Execution original = execution(State.Type.SUCCESS);
        for (Execution wrong : List.of(original.toBuilder().namespace("other").build(), original.toBuilder().flowId("other").build(),
            original.toBuilder().id("other").build(), original.withTrigger(ExecutionTrigger.of(trigger, Map.of("apiId", "other"))),
            original.toBuilder().tenantId("other").build())) {
            trace.clear(); stored = Optional.of(wrong);
            assertEquals(HttpStatus.NOT_FOUND, assertThrows(HttpStatusException.class, () -> read(true)).getStatus());
            assertFalse(trace.contains("outputs"));
        }
    }

    @Test void changedFlowRevisionIsConflictRatherThanMixedResultInterpretation() {
        stored = Optional.of(execution(State.Type.SUCCESS).toBuilder().flowRevision(2)
            .trigger(ExecutionTrigger.of(trigger, Map.of("apiId", trigger.getApiId(), "declarationRevision", 2))).build());
        assertEquals(HttpStatus.CONFLICT, assertThrows(RevisionConflictException.class, () -> read(true)).getStatus());
        assertFalse(trace.contains("outputs"));
    }

    @Test void p2SameFlowApisSelectDistinctOutputsAndRejectCrossApiExecution() {
        Flow multi = YamlParser.parse(PackagedTriggerMain.coreFlow() + PackagedTriggerMain.declarations()
            + "  - id: api-beta\n    type: io.kestra.plugin.amis.apps.ApiTrigger\n    apiId: beta\n    resultOutput: betaResponse\n"
            + "outputs:\n  - id: response\n    type: JSON\n    value: {}\n  - id: betaResponse\n    type: JSON\n    value: {}\n", Flow.class).toBuilder().revision(1).build();
        var selected = new PublishedFlowAccess.Snapshot(ProcessedFlow.of(FlowWithSource.of(multi, "multi")), RouteDeclarations.from(multi));
        var beta = multi.getTriggers().stream().filter(ApiTrigger.class::isInstance).map(ApiTrigger.class::cast)
            .filter(value -> value.getApiId().equals("beta")).findFirst().orElseThrow();
        values = Map.of("response", Map.of("alpha", 7), "betaResponse", Map.of("beta", true));
        var alphaResult = service(false).read(Authentication.build("ordinary-user"), selected, trigger.getApiId(), "execution123").block();
        assertEquals(7, alphaResult.data().path("alpha").asInt());
        assertFalse(alphaResult.data().has("beta"));
        trace.clear();
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(HttpStatusException.class, () -> service(false)
            .read(Authentication.build("ordinary-user"), selected, "beta", "execution123").block()).getStatus());
        assertFalse(trace.contains("outputs"));
        stored = Optional.of(execution(State.Type.SUCCESS).withTrigger(ExecutionTrigger.of(beta,
            Map.of("apiId", "beta", "declarationRevision", 1))));
        var betaResult = service(false).read(Authentication.build("ordinary-user"), selected, "beta", "execution123").block();
        assertTrue(betaResult.data().path("beta").asBoolean());
        assertFalse(betaResult.data().has("alpha"));
    }

    @Test void p2MissingOrForgedTriggerRevisionIsNotFoundBeforeOutputRead() {
        for (Map<String, Object> variables : List.<Map<String, Object>>of(
            Map.of("apiId", trigger.getApiId()),
            Map.of("apiId", trigger.getApiId(), "declarationRevision", 2),
            Map.of("apiId", trigger.getApiId(), "declarationRevision", "1"))) {
            stored = Optional.of(execution(State.Type.SUCCESS).withTrigger(ExecutionTrigger.of(trigger, variables)));
            assertEquals(HttpStatus.NOT_FOUND, assertThrows(HttpStatusException.class, () -> read(true)).getStatus());
            assertFalse(trace.contains("outputs"));
        }
    }

    @Test void absentOutputRemainsPendingButExplicitEmptyObjectIsReady() {
        values = Map.of("sibling", Map.of("private", "not-this-api"));
        assertEquals(202, read(true).httpStatus());
        values = Map.of("response", Map.of());
        var ready = read(true); assertTrue(ready.resultReady()); assertTrue(ready.data().isEmpty());
    }

    @Test void scalarOutputIsNotSilentlyConvertedToAnEmptySuccess() {
        values = Map.of("response", "invalid-result");
        assertEquals(HttpStatus.CONFLICT, assertThrows(HttpStatusException.class, () -> read(true)).getStatus());
    }

    @Test void modeAUsesTheSameBindingRulesWithoutRbacAndStillRejectsAnonymous() {
        assertEquals(200, read(false).httpStatus());
        assertEquals(List.of("lookup", "outputs"), trace);
        trace.clear();
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(HttpStatusException.class, () -> service(false)
            .read(null, snapshot, trigger.getApiId(), "execution123").block()).getStatus());
        assertTrue(trace.isEmpty());
    }

    @Test void invalidOrMissingApiIdentityCannotTriggerRepositoryLookup() {
        assertThrows(IllegalArgumentException.class, () -> service(true).read(Authentication.build("ordinary-user"), snapshot, "../other", "execution123").block());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(HttpStatusException.class, () -> service(true)
            .read(Authentication.build("ordinary-user"), snapshot, "missing", "execution123").block()).getStatus());
        assertTrue(trace.isEmpty());
    }
}
