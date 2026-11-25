package io.kestra.plugin.amis.apps.runtime;

import io.kestra.core.async.AsyncOperationProcessedEvent;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.flows.State;
import io.kestra.core.models.flows.check.Check;
import io.kestra.core.serializers.YamlParser;
import io.kestra.core.services.ConditionService;
import io.kestra.core.services.FlowService;
import io.kestra.core.services.LabelService;
import io.kestra.core.services.WebhookService;
import io.kestra.core.runners.FlowInputOutput;
import io.kestra.core.runners.ProcessedFlow;
import io.kestra.plugin.amis.apps.ApiTrigger;
import io.kestra.webserver.rbac.RbacExecutionGate;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.authentication.Authentication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublishedFlowInvocationTest {
    private final RbacExecutionGate authorization = mock(RbacExecutionGate.class);
    private final FlowInputOutput inputs = mock(FlowInputOutput.class);
    private final FlowService flows = mock(FlowService.class);
    private final ConditionService conditions = mock(ConditionService.class);
    private final WebhookService webhooks = mock(WebhookService.class);
    private final PublishedFlowInvocation.Submission submission = mock(PublishedFlowInvocation.Submission.class);
    private final Authentication identity = Authentication.build("ada");
    private Flow flow;
    private PublishedFlowAccess.Snapshot snapshot;
    private PublishedFlowInvocation invocation;
    private final AtomicInteger supplierCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        flow = flow();
        RouteDeclarations declarations = RouteDeclarations.from(flow);
        snapshot = mock(PublishedFlowAccess.Snapshot.class);
        when(snapshot.flow()).thenReturn(flow);
        when(snapshot.declarations()).thenReturn(declarations);
        when(snapshot.processed()).thenReturn(ProcessedFlow.of(io.kestra.core.services.FlowParsingService.toFlowWithSource(flow)));
        when(conditions.isValid(any(), eq(flow), any())).thenReturn(true);
        when(flows.validateForExecution(flow)).thenReturn(java.util.Optional.empty());
        when(flows.getFailedChecks(eq(flow), anyMap())).thenReturn(List.of());
        when(inputs.readExecutionInputs(eq(flow), any(Execution.class), anyMap())).thenAnswer(invocation -> invocation.getArgument(2));
        when(submission.start(any(Execution.class))).thenAnswer(invocation -> {
            Execution execution = invocation.getArgument(0);
            return Mono.just(new AsyncOperationProcessedEvent("op", execution.getTenantId(), execution.getId(),
                AsyncOperationProcessedEvent.Outcome.SUCCEEDED, null, Instant.now()));
        });
        doAnswer(call -> {
            supplierCalls.incrementAndGet();
            java.util.function.Supplier<?> supplier = call.getArgument(5);
            assertNotNull(supplier);
            return supplier.get();
        }).when(authorization).checked(any(), anyString(), any(), anyString(), anyBoolean(), any());
        invocation = new PublishedFlowInvocation(authorization, inputs, flows, conditions, webhooks, submission);
    }

    @Test
    void anonymousWrongApiAndForgedApiRejectBeforeAuthorizationInputsChecksOrSubmission() {
        assertStatus(HttpStatus.UNAUTHORIZED, () -> invocation.submit(null, snapshot, "greet", Map.of()).block());
        assertStatus(HttpStatus.NOT_FOUND, () -> invocation.submit(identity, snapshot, "missing", Map.of()).block());
        assertStatus(HttpStatus.BAD_REQUEST, () -> invocation.submit(identity, snapshot, "greet", Map.of("apiId", "forged")).block());
        verifyNoInteractions(authorization, inputs, flows, conditions, submission);
    }

    @Test
    void checkedSupplierIsTheFirstPointThatReadsInputsAndRunsChecks() {
        doReturn(Mono.empty()).when(authorization).checked(any(), anyString(), any(), anyString(), anyBoolean(), any());
        assertTrue(invocation.submit(identity, snapshot, "greet", Map.of("name", "{{secret}}")).blockOptional().isEmpty());
        assertEquals(0, supplierCalls.get());
        verifyNoInteractions(inputs, flows, conditions, submission);
    }

    @Test
    void coreInputServiceReceivesLiteralTemplateWithoutRenderingAndSuccessfulAckReturnsId() {
        Map<String, Object> request = Map.of("name", "{{ user.secret }}");
        PublishedFlowInvocation.Submitted result = invocation.submit(identity, snapshot, "greet", request).block();
        assertNotNull(result);
        ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
        verify(inputs).readExecutionInputs(eq(flow), any(Execution.class), values.capture());
        assertEquals("{{ user.secret }}", values.getValue().get("name"));
        verify(submission).start(any(Execution.class));
        assertEquals("greet", result.declaration().apiId());
    }

    @Test
    void blockDoesNotSubmitAndFailPreparesFailedExecution() {
        Check failedCheck = checkWith(Check.Behavior.FAIL_EXECUTION);
        when(flows.getFailedChecks(eq(flow), anyMap())).thenReturn(List.of(failedCheck));
        Execution failed = invocation.submit(identity, snapshot, "greet", Map.of()).block().execution();
        assertEquals(State.Type.FAILED, failed.getState().getCurrent());
        verify(submission).start(failed);

        reset(submission);
        Check blockedCheck = checkWith(Check.Behavior.BLOCK_EXECUTION);
        when(flows.getFailedChecks(eq(flow), anyMap())).thenReturn(List.of(blockedCheck));
        assertStatus(HttpStatus.CONFLICT, () -> invocation.submit(identity, snapshot, "greet", Map.of()).block());
        verifyNoInteractions(submission);
    }

    @Test
    void conditionFailureDoesNotSubmit() {
        when(conditions.isValid(any(), eq(flow), any())).thenReturn(false);
        assertStatus(HttpStatus.CONFLICT, () -> invocation.submit(identity, snapshot, "greet", Map.of()).block());
        verifyNoInteractions(submission);
    }

    @Test
    void failedAckIsNotReportedAsSuccessfulSubmission() {
        when(submission.start(any(Execution.class))).thenReturn(Mono.just(new AsyncOperationProcessedEvent(
            "op", null, "execution123", AsyncOperationProcessedEvent.Outcome.FAILED, "failed", Instant.now())));
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, () -> invocation.submit(identity, snapshot, "greet", Map.of()).block());
    }

    @Test
    void triggerCarriesTrustedCallerApiAndRevisionAndPinnedLabelsAreNotOverridden() {
        Execution result = invocation.submit(identity, snapshot, "greet", Map.of()).block().execution();
        assertEquals("ada", result.getTrigger().getVariables().get("caller"));
        assertEquals("greet", result.getTrigger().getVariables().get("apiId"));
        assertEquals(flow.getRevision(), result.getTrigger().getVariables().get("declarationRevision"));
        assertEquals(List.of(), result.getLabels().stream().filter(label -> label.key().equals("system")).toList());
    }

    @Test
    void inputFailureAndFlowValidationFailureDoNotSubmit() {
        when(inputs.readExecutionInputs(eq(flow), any(Execution.class), anyMap())).thenThrow(new IllegalArgumentException("input failure"));
        assertThrows(IllegalArgumentException.class, () -> invocation.submit(identity, snapshot, "greet", Map.of()).block());
        verifyNoInteractions(submission);
        reset(inputs);
        when(inputs.readExecutionInputs(eq(flow), any(Execution.class), anyMap())).thenReturn(Map.of());
        when(flows.validateForExecution(flow)).thenReturn(java.util.Optional.of(mock(jakarta.validation.ConstraintViolationException.class)));
        assertStatus(HttpStatus.CONFLICT, () -> invocation.submit(identity, snapshot, "greet", Map.of()).block());
        verifyNoInteractions(submission);
    }

    private static Check checkWith(Check.Behavior behavior) {
        Check check = mock(Check.class);
        when(check.getBehavior()).thenReturn(behavior);
        return check;
    }

    private static Flow flow() {
        return YamlParser.parse("id: greeting-app\nnamespace: company.demo\nrevision: 3\n"
            + "tasks:\n  - id: log\n    type: io.kestra.plugin.core.log.Log\n    message: greeting\n"
            + "outputs:\n  - id: response\n    type: JSON\n    value: {}\n"
            + "triggers:\n  - id: api-trigger\n    type: io.kestra.plugin.amis.apps.ApiTrigger\n    apiId: greet\n    resultOutput: response\n", Flow.class);
    }

    @Test void missingResultDeclarationRejectsBeforeInputChecksAndSubmission() {
        Flow missing = flow.toBuilder().outputs(List.of()).build();
        when(snapshot.flow()).thenReturn(missing);
        assertStatus(HttpStatus.CONFLICT, () -> invocation.submit(identity, snapshot, "greet", Map.of()).block());
        verifyNoInteractions(inputs, submission);
    }

    private static void assertStatus(HttpStatus expected, java.util.function.Supplier<?> action) {
        HttpStatusException failure = assertThrows(HttpStatusException.class, () -> action.get());
        assertEquals(expected, failure.getStatus());
    }
}
