package io.kestra.plugin.amis.apps.runtime;

import io.kestra.core.async.AsyncOperationProcessedEvent;
import io.kestra.core.models.Label;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.executions.ExecutionTrigger;
import io.kestra.core.models.flows.check.Check;
import io.kestra.core.repositories.AuthorizationEvaluationContext;
import io.kestra.core.runners.FlowInputOutput;
import io.kestra.core.services.ConditionService;
import io.kestra.core.services.FlowService;
import io.kestra.core.services.LabelService;
import io.kestra.core.services.WebhookService;
import io.kestra.plugin.amis.apps.ApiTrigger;
import io.kestra.webserver.rbac.RbacExecutionGate;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.authentication.Authentication;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

public final class PublishedFlowInvocation {
    private final RbacExecutionGate authorization;
    private final FlowInputOutput inputs;
    private final FlowService flows;
    private final ConditionService conditions;
    private final WebhookService webhooks;
    private final Submission submission;

    public PublishedFlowInvocation(RbacExecutionGate authorization, FlowInputOutput inputs, FlowService flows,
                                   ConditionService conditions, WebhookService webhooks) {
        this(authorization, inputs, flows, conditions, webhooks, webhooks::startExecution);
    }

    PublishedFlowInvocation(RbacExecutionGate authorization, FlowInputOutput inputs, FlowService flows,
                            ConditionService conditions, WebhookService webhooks, Submission submission) {
        this.authorization = Objects.requireNonNull(authorization);
        this.inputs = Objects.requireNonNull(inputs);
        this.flows = Objects.requireNonNull(flows);
        this.conditions = Objects.requireNonNull(conditions);
        this.webhooks = Objects.requireNonNull(webhooks);
        this.submission = Objects.requireNonNull(submission);
    }

    public Mono<Submitted> submit(Authentication identity, PublishedFlowAccess.Snapshot snapshot,
                                   String apiId, Map<String, Object> requestInputs) {
        if (identity == null || identity.getName() == null || identity.getName().isBlank()) {
            return Mono.error(new HttpStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
        }
        if (requestInputs == null) {
            return Mono.error(new HttpStatusException(HttpStatus.BAD_REQUEST, "JSON object inputs required"));
        }
        var selected = snapshot.declarations().api(snapshot.declarations().namespace(), snapshot.declarations().flowId(), apiId);
        if (selected.status() != RouteDeclarations.Status.ACTIVE) {
            return Mono.error(new HttpStatusException(HttpStatus.NOT_FOUND, "API route not found"));
        }
        ApiTrigger trigger = snapshot.flow().getTriggers().stream().filter(ApiTrigger.class::isInstance)
            .map(ApiTrigger.class::cast).filter(candidate -> candidate.getId().equals(selected.declaration().triggerId()))
            .findFirst().orElseThrow(() -> new HttpStatusException(HttpStatus.CONFLICT, "API declaration changed"));
        if (requestInputs.containsKey("apiId") && !apiId.equals(requestInputs.get("apiId"))) {
            return Mono.error(new HttpStatusException(HttpStatus.BAD_REQUEST, "API identity cannot be replaced by inputs"));
        }
        List<Label> contributed = LabelService.withoutPinned(List.of(), snapshot.processed().pinnedLabelKeys());
        Execution candidate = Execution.newExecution(snapshot.flow(), contributed);
        candidate = candidate.withLabels(LabelService.forExecution(snapshot.flow(), contributed, candidate.getId()));
        candidate = candidate.withTrigger(ExecutionTrigger.of(trigger,
            Map.of("apiId", apiId, "caller", identity.getName(), "declarationRevision", snapshot.declarations().flowRevision())));
        Execution prepared = candidate;
        return authorization.checked(identity, "main", snapshot.flow(), prepared.getId(), false,
            () -> AuthorizationEvaluationContext.call(() -> {
                if (snapshot.flow().getOutputs() == null || snapshot.flow().getOutputs().stream()
                    .noneMatch(output -> selected.declaration().resultOutput().equals(output.getId()))) {
                    throw new HttpStatusException(HttpStatus.CONFLICT, "API result output is not declared by the Flow");
                }
                if (flows.validateForExecution(snapshot.flow()).isPresent()) {
                    throw new HttpStatusException(HttpStatus.CONFLICT, "Flow definition is not executable");
                }
                Map<String, Object> supplied = new HashMap<>(requestInputs);
                boolean declaredApiId = snapshot.flow().getInputs() != null
                    && snapshot.flow().getInputs().stream().anyMatch(input -> input.getId().equals("apiId"));
                if (declaredApiId) supplied.put("apiId", apiId);
                else supplied.remove("apiId");
                Map<String, Object> values = inputs.readExecutionInputs(snapshot.flow(), prepared, supplied);
                Execution execution = prepared.withInputs(values);
                if (!conditions.isValid(trigger, snapshot.flow(), webhooks.runContext(snapshot.flow(), execution))) {
                    throw new HttpStatusException(HttpStatus.CONFLICT, "API trigger condition did not allow execution");
                }
                Check.Behavior behavior = Check.resolveBehavior(flows.getFailedChecks(snapshot.flow(), values));
                if (behavior == Check.Behavior.BLOCK_EXECUTION) {
                    throw new HttpStatusException(HttpStatus.CONFLICT, "Flow checks blocked execution");
                }
                if (behavior == Check.Behavior.FAIL_EXECUTION) {
                    execution = execution.withState(io.kestra.core.models.flows.State.Type.FAILED);
                }
                return execution;
            }).flatMap(execution -> submission.start(execution).flatMap(acknowledgement -> {
                if (acknowledgement.outcome() != AsyncOperationProcessedEvent.Outcome.SUCCEEDED
                    || !execution.getId().equals(acknowledgement.itemId())) {
                    return Mono.error(new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Execution submission was not confirmed"));
                }
                return Mono.just(new Submitted(execution, selected.declaration()));
            })));
    }

    public Mono<Void> finishRequest() { return authorization.finishRequest(); }

    public record Submitted(Execution execution, RouteDeclarations.Api declaration) { }

    @FunctionalInterface
    interface Submission {
        Mono<AsyncOperationProcessedEvent> start(Execution execution);
    }
}
