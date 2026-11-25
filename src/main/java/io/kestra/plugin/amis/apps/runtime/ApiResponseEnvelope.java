package io.kestra.plugin.amis.apps.runtime;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.kestra.core.models.flows.State;
import io.kestra.plugin.amis.apps.domain.Identifiers;

import java.util.Objects;

public final class ApiResponseEnvelope {
    private final int httpStatus;
    private final int status;
    private final String msg;
    private final ObjectNode data;
    private final String executionId;
    private final String executionState;
    private final boolean resultReady;
    private final String executionUrl;

    private ApiResponseEnvelope(int httpStatus, int status, String msg, ObjectNode data,
                                Binding binding, State.Type state, boolean resultReady) {
        this.httpStatus = httpStatus;
        this.status = status;
        this.msg = msg;
        this.data = data == null ? null : data.deepCopy();
        executionId = binding.executionId();
        executionState = state.name();
        this.resultReady = resultReady;
        executionUrl = "/" + binding.namespace() + "/" + binding.flowId() + "/apis/" + binding.apiId()
            + "/executions/" + binding.executionId();
    }

    public static ApiResponseEnvelope pending(RouteDeclarations selectedFlow, String selectedApiId,
                                               String expectedExecutionId, Binding source, String executionState) {
        Binding checked = checkedBinding(selectedFlow, selectedApiId, expectedExecutionId, source);
        State.Type state = state(executionState);
        return new ApiResponseEnvelope(202, 0, "Execution pending", JsonNodeFactory.instance.objectNode(), checked, state, false);
    }

    public static ApiResponseEnvelope ready(RouteDeclarations selectedFlow, String selectedApiId,
                                             String expectedExecutionId, Binding source, String executionState,
                                             JsonNode selectedOutput) {
        Binding checked = checkedBinding(selectedFlow, selectedApiId, expectedExecutionId, source);
        State.Type state = state(executionState);
        if (!state.isTerminated()) {
            throw new IllegalArgumentException("Ready response requires a terminal execution state");
        }
        if (!isSuccessState(state)) {
            throw new IllegalArgumentException("Ready response requires an explicitly supported success state");
        }
        requireObjectResult(selectedOutput);
        return new ApiResponseEnvelope(200, 0, "Execution completed", (ObjectNode) selectedOutput, checked, state, true);
    }

    public static ApiResponseEnvelope failed(RouteDeclarations selectedFlow, String selectedApiId,
                                              String expectedExecutionId, Binding source, String executionState) {
        return failed(selectedFlow, selectedApiId, expectedExecutionId, source, executionState,
            JsonNodeFactory.instance.objectNode());
    }

    public static ApiResponseEnvelope failed(RouteDeclarations selectedFlow, String selectedApiId,
                                              String expectedExecutionId, Binding source, String executionState,
                                              JsonNode selectedOutput) {
        Binding checked = checkedBinding(selectedFlow, selectedApiId, expectedExecutionId, source);
        State.Type state = state(executionState);
        if (!isFailureState(state)) {
            throw new IllegalArgumentException("Failed response requires an explicitly supported failure state");
        }
        requireObjectResult(selectedOutput);
        return new ApiResponseEnvelope(200, 1, "Execution failed", (ObjectNode) selectedOutput, checked, state, true);
    }

    private static void requireObjectResult(JsonNode selectedOutput) {
        if (selectedOutput == null || !selectedOutput.isObject()) {
            throw new IllegalArgumentException("Response requires the bound Flow output JSON object");
        }
    }

    private static boolean isSuccessState(State.Type state) {
        return state == State.Type.SUCCESS || state == State.Type.WARNING || state == State.Type.RETRIED
            || state == State.Type.SKIPPED || state == State.Type.RESUBMITTED;
    }

    private static boolean isFailureState(State.Type state) {
        return state == State.Type.FAILED || state == State.Type.KILLED || state == State.Type.CANCELLED;
    }

    private static Binding checkedBinding(RouteDeclarations selectedFlow, String selectedApiId,
                                            String expectedExecutionId, Binding source) {
        Objects.requireNonNull(selectedFlow, "Missing selected Flow snapshot");
        Objects.requireNonNull(source, "Missing trusted execution/result source binding");
        Identifiers.id(expectedExecutionId);
        var selected = selectedFlow.api(selectedFlow.namespace(), selectedFlow.flowId(), selectedApiId);
        if (selected.status() != RouteDeclarations.Status.ACTIVE) {
            throw new IllegalArgumentException("Selected API is not active in the P0 snapshot");
        }
        if (!selectedFlow.namespace().equals(source.namespace()) || !selectedFlow.flowId().equals(source.flowId())
            || !selectedApiId.equals(source.apiId()) || !expectedExecutionId.equals(source.executionId())
            || !selected.declaration().resultOutput().equals(source.resultOutput())) {
            throw new IllegalArgumentException("Execution/result source does not match selected namespace/Flow/API/execution/output");
        }
        return source;
    }

    private static State.Type state(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Missing execution state");
        }
        try {
            return State.Type.valueOf(name);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Unknown execution state", failure);
        }
    }

    public record Binding(String namespace, String flowId, String apiId, String executionId, String resultOutput) {
        public Binding {
            Identifiers.namespace(namespace);
            Identifiers.flowId(flowId);
            Identifiers.id(apiId);
            Identifiers.id(executionId);
            Identifiers.outputId(resultOutput);
        }
    }

    @JsonIgnore
    public int httpStatus() { return httpStatus; }
    @JsonProperty("version")
    public int version() { return 1; }
    @JsonProperty("status")
    public int status() { return status; }
    @JsonProperty("msg")
    public String msg() { return msg; }
    @JsonProperty("data")
    public ObjectNode data() { return data == null ? null : data.deepCopy(); }
    @JsonProperty("executionId")
    public String executionId() { return executionId; }
    @JsonProperty("executionState")
    public String executionState() { return executionState; }
    @JsonProperty("resultReady")
    public boolean resultReady() { return resultReady; }
    @JsonProperty("executionUrl")
    public String executionUrl() { return executionUrl; }
}
