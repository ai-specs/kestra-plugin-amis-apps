package io.kestra.plugin.amis.apps.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.State;
import io.kestra.core.repositories.AuthorizationEvaluationContext;
import io.kestra.core.repositories.ExecutionRepositoryInterface;
import io.kestra.plugin.amis.apps.ApiTrigger;
import io.kestra.plugin.amis.apps.domain.Identifiers;
import io.kestra.webserver.rbac.RbacAuthorizer;
import io.kestra.webserver.rbac.RbacConfiguration;
import io.kestra.webserver.rbac.RbacGate;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.authentication.Authentication;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

public final class PublishedExecutionResults {
    private final PublishedFlowAccess access;
    private final ExecutionRepositoryInterface executions;
    private final OutputReader outputs;
    private final RbacConfiguration configuration;
    private final RbacGate gate;
    private final ObjectMapper mapper = new ObjectMapper();

    public PublishedExecutionResults(PublishedFlowAccess access, ExecutionRepositoryInterface executions,
                                     OutputReader outputs, RbacConfiguration configuration, RbacGate gate) {
        this.access = Objects.requireNonNull(access);
        this.executions = Objects.requireNonNull(executions);
        this.outputs = Objects.requireNonNull(outputs);
        this.configuration = Objects.requireNonNull(configuration);
        this.gate = Objects.requireNonNull(gate);
    }

    public Mono<ApiResponseEnvelope> read(Authentication identity, PublishedFlowAccess.Snapshot snapshot,
                                           String apiId, String executionId) {
        Identifiers.id(apiId);
        Identifiers.id(executionId);
        var selected = snapshot.declarations().api(snapshot.declarations().namespace(), snapshot.declarations().flowId(), apiId);
        if (selected.status() != RouteDeclarations.Status.ACTIVE) {
            return Mono.error(new HttpStatusException(HttpStatus.NOT_FOUND, "API route not found"));
        }
        return access.withFlowExecutionPermission(identity, snapshot, () -> {
            if (!configuration.enabled()) {
                return readBoundExecution(snapshot, selected.declaration(), executionId);
            }
            var principal = new RbacAuthorizer.Principal(identity.getName(), List.copyOf(identity.getRoles()), null, null, List.of());
            var resource = new RbacAuthorizer.Resource(RbacAuthorizer.Kind.EXECUTION, RbacAuthorizer.Scope.NAMESPACE,
                "main", snapshot.declarations().namespace(), executionId);
            return gate.boundary(principal, "main", () -> gate.authorize(principal, resource, RbacAuthorizer.Action.READ).defaultIfEmpty(false)
                .flatMap(allowed -> Boolean.TRUE.equals(allowed)
                    ? gate.authorize(principal, resource, RbacAuthorizer.Action.ACCESS_OUTPUTS).defaultIfEmpty(false) : Mono.just(false))
                .flatMap(allowed -> Boolean.TRUE.equals(allowed)
                    ? readBoundExecution(snapshot, selected.declaration(), executionId)
                    : Mono.error(new HttpStatusException(HttpStatus.FORBIDDEN, "Execution result permission required"))))
                .singleOrEmpty();
        });
    }

    private Mono<ApiResponseEnvelope> readBoundExecution(PublishedFlowAccess.Snapshot snapshot, RouteDeclarations.Api api,
                                                        String executionId) {
        return AuthorizationEvaluationContext.call(() -> {
            if (snapshot.flow().getOutputs() == null || snapshot.flow().getOutputs().stream()
                .noneMatch(output -> api.resultOutput().equals(output.getId()))) {
                throw new HttpStatusException(HttpStatus.CONFLICT, "API result output is not declared by the Flow");
            }
            Execution execution = executions.findByIdWithoutAcl(access.storageTenant(), executionId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Execution result not found"));
            var trigger = execution.getTrigger();
            Map<String, Object> variables = trigger == null ? null : trigger.getVariables();
            if (!executionId.equals(execution.getId()) || !snapshot.declarations().namespace().equals(execution.getNamespace())
                || !snapshot.declarations().flowId().equals(execution.getFlowId())
                || execution.getTenantId() != null && !execution.getTenantId().equals("main")
                || trigger == null || !api.triggerId().equals(trigger.getId()) || !ApiTrigger.class.getName().equals(trigger.getType())
                || variables == null || !api.apiId().equals(variables.get("apiId"))
                || !Objects.equals(execution.getFlowRevision(), variables.get("declarationRevision"))) {
                throw new HttpStatusException(HttpStatus.NOT_FOUND, "Execution result does not belong to this API");
            }
            if (!Objects.equals(snapshot.declarations().flowRevision(), execution.getFlowRevision())) {
                throw new RevisionConflictException();
            }
            if (execution.getState() == null || execution.getState().getCurrent() == null) {
                throw new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Execution state is unavailable");
            }
            var binding = new ApiResponseEnvelope.Binding(execution.getNamespace(), execution.getFlowId(), api.apiId(), executionId, api.resultOutput());
            State.Type state = execution.getState().getCurrent();
            if (!state.isTerminated()) {
                return ApiResponseEnvelope.pending(snapshot.declarations(), api.apiId(), executionId, binding, state.name());
            }
            if (state == State.Type.FAILED || state == State.Type.KILLED || state == State.Type.CANCELLED) {
                return ApiResponseEnvelope.failed(snapshot.declarations(), api.apiId(), executionId, binding, state.name());
            }
            Map<String, Object> values = outputs.read(execution);
            if (values == null || !values.containsKey(api.resultOutput())) {
                return ApiResponseEnvelope.pending(snapshot.declarations(), api.apiId(), executionId, binding, state.name());
            }
            JsonNode output = mapper.valueToTree(values.get(api.resultOutput()));
            if (output == null || !output.isObject()) {
                throw new HttpStatusException(HttpStatus.CONFLICT, "Declared API output is not a JSON object");
            }
            return ApiResponseEnvelope.ready(snapshot.declarations(), api.apiId(), executionId, binding, state.name(), output);
        });
    }

    @FunctionalInterface
    public interface OutputReader {
        Map<String, Object> read(Execution execution) throws Exception;
    }
}
