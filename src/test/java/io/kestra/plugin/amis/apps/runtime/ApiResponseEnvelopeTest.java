package io.kestra.plugin.amis.apps.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.kestra.core.models.flows.State;
import io.kestra.core.serializers.YamlParser;
import io.kestra.plugin.amis.apps.testing.PackagedTriggerMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ApiResponseEnvelopeTest {
    private static final RouteDeclarations ROUTES = RouteDeclarations.from(
        YamlParser.parse(PackagedTriggerMain.coreFlow() + PackagedTriggerMain.declarations(), io.kestra.core.models.flows.Flow.class));
    private static final ApiResponseEnvelope.Binding BINDING =
        new ApiResponseEnvelope.Binding("company._team", "model.test", "home", "execution1", "response");

    @Test
    void pending202KeepsDataEmptyAndPreservesExecutionIdentity() throws Exception {
        var response = ApiResponseEnvelope.pending(ROUTES, "home", "execution1", BINDING, "RUNNING");
        assertEquals(202, response.httpStatus());
        assertEquals(0, response.status());
        assertNotNull(response.data());
        assertTrue(response.data().isEmpty());
        assertFalse(response.resultReady());
        assertEquals("execution1", response.executionId());
        assertEquals("RUNNING", response.executionState());
        assertEquals("/company._team/model.test/apis/home/executions/execution1", response.executionUrl());
        String json = new ObjectMapper().writeValueAsString(response);
        assertTrue(json.contains("\"data\":{}"));
    }

    @Test
    void ready200RequiresTerminalStateAndObjectOutput() {
        var data = JsonNodeFactory.instance.objectNode().put("message", "ok");
        var response = ApiResponseEnvelope.ready(ROUTES, "home", "execution1", BINDING, "SUCCESS", data);
        assertEquals(200, response.httpStatus());
        assertEquals(0, response.status());
        assertTrue(response.resultReady());
        assertEquals("SUCCESS", response.executionState());
        assertEquals("ok", response.data().path("message").textValue());
        assertNotSame(data, response.data());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "QUEUED", "RUNNING", "PAUSED", "RETRYING", "SUBMITTED"})
    void pendingAllowsNonTerminalKnownStates(String state) {
        assertFalse(ApiResponseEnvelope.pending(ROUTES, "home", "execution1", BINDING, state).resultReady());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "QUEUED", "RUNNING", "PAUSED", "RETRYING", "SUBMITTED", "NOT_A_STATE"})
    void readyRejectsUnknownOrNonTerminalStates(String state) {
        assertThrows(IllegalArgumentException.class, () -> ApiResponseEnvelope.ready(ROUTES, "home", "execution1", BINDING,
            state, JsonNodeFactory.instance.objectNode()));
    }

    @Test
    void failedTerminalStateIsNonzeroAndNeverAnEmptySuccess() {
        var response = ApiResponseEnvelope.failed(ROUTES, "home", "execution1", BINDING, "FAILED",
            JsonNodeFactory.instance.objectNode().put("error", "reviewed"));
        assertEquals(1, response.status());
        assertTrue(response.resultReady());
        assertEquals("reviewed", response.data().path("error").textValue());
        assertDoesNotThrow(() -> ApiResponseEnvelope.failed(ROUTES, "home", "execution1", BINDING,
            "FAILED", JsonNodeFactory.instance.objectNode()));
        assertThrows(IllegalArgumentException.class, () -> ApiResponseEnvelope.failed(ROUTES, "home", "execution1", BINDING,
            "FAILED", JsonNodeFactory.instance.nullNode()));
    }

    @Test
    void readyRejectsScalarArrayOrMissingResults() {
        for (var node : new com.fasterxml.jackson.databind.JsonNode[] {null, JsonNodeFactory.instance.nullNode(),
            JsonNodeFactory.instance.textNode("ok"), JsonNodeFactory.instance.arrayNode()}) {
            assertThrows(IllegalArgumentException.class, () -> ApiResponseEnvelope.ready(ROUTES, "home", "execution1", BINDING,
                "SUCCESS", node));
        }
    }

    @Test
    void terminalOutputNotYetReadableRemainsPendingAndNeverBecomesEmptySuccess() {
        var pending = ApiResponseEnvelope.pending(ROUTES, "home", "execution1", BINDING, "SUCCESS");
        assertEquals(202, pending.httpStatus());
        assertFalse(pending.resultReady());
        assertEquals(0, pending.status());
        assertTrue(pending.data().isEmpty());
    }

    @Test
    void pendingRejectsUnknownAndMissingStates() {
        for (String state : new String[] {null, "", "UNKNOWN", "running"}) {
            assertThrows(IllegalArgumentException.class, () -> ApiResponseEnvelope.pending(ROUTES, "home", "execution1", BINDING, state));
        }
    }

    @Test
    void failureFactoryUsesExplicitFailureMatrixAndRejectsSuccessStates() {
        assertDoesNotThrow(() -> ApiResponseEnvelope.failed(ROUTES, "home", "execution1", BINDING, "KILLED",
            JsonNodeFactory.instance.objectNode()));
        assertDoesNotThrow(() -> ApiResponseEnvelope.failed(ROUTES, "home", "execution1", BINDING, "CANCELLED",
            JsonNodeFactory.instance.objectNode()));
        for (String state : new String[] {"SUCCESS", "WARNING", "SKIPPED", "RETRIED", "RESUBMITTED"}) {
            assertThrows(IllegalArgumentException.class, () -> ApiResponseEnvelope.failed(ROUTES, "home", "execution1", BINDING,
                state, JsonNodeFactory.instance.objectNode()));
        }
        assertThrows(IllegalArgumentException.class, () -> ApiResponseEnvelope.ready(ROUTES, "home", "execution1", BINDING,
            "FAILED", JsonNodeFactory.instance.objectNode()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "KILLED", "CANCELLED"})
    void failureWithoutAnyProducedOutputUsesAnEmptyObjectAndPreservesFailure(String state) {
        var failure = ApiResponseEnvelope.failed(ROUTES, "home", "execution1", BINDING, state);
        assertEquals(200, failure.httpStatus());
        assertNotEquals(0, failure.status());
        assertEquals(state, failure.executionState());
        assertTrue(failure.resultReady());
        assertTrue(failure.data().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "RUNNING", "PAUSED", "CREATED", "KILLING", "RETRYING"})
    void failureFactoryRejectsUnknownAndNonTerminalStates(String state) {
        assertThrows(IllegalArgumentException.class,
            () -> ApiResponseEnvelope.failed(ROUTES, "home", "execution1", BINDING, state));
    }

    @Test
    void successfulAndFailedFactoriesPreserveAllSourceBindingRejections() {
        for (var source : new ApiResponseEnvelope.Binding[] {
            new ApiResponseEnvelope.Binding("other", "model.test", "home", "execution1", "response"),
            new ApiResponseEnvelope.Binding("company._team", "other", "home", "execution1", "response"),
            new ApiResponseEnvelope.Binding("company._team", "model.test", "other", "execution1", "response"),
            new ApiResponseEnvelope.Binding("company._team", "model.test", "home", "other", "response"),
            new ApiResponseEnvelope.Binding("company._team", "model.test", "home", "execution1", "other")}) {
            assertThrows(IllegalArgumentException.class, () -> ApiResponseEnvelope.ready(ROUTES, "home", "execution1", source,
                "SUCCESS", JsonNodeFactory.instance.objectNode()));
            assertThrows(IllegalArgumentException.class, () -> ApiResponseEnvelope.failed(ROUTES, "home", "execution1", source, "FAILED"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "WARNING", "RETRIED", "SKIPPED", "RESUBMITTED"})
    void frontendSuccessTerminalMatrixProducesStatusZeroIncludingExplicitEmptyObject(String state) {
        var response = ApiResponseEnvelope.ready(ROUTES, "home", "execution1", BINDING, state,
            JsonNodeFactory.instance.objectNode());
        assertEquals(0, response.status());
        assertTrue(response.resultReady());
        assertTrue(response.data().isEmpty());
    }

    @Test
    void renamedResultOutputMustMatchTheSelectedApiDeclaration() {
        var routes = RouteDeclarations.from(YamlParser.parse(PackagedTriggerMain.coreFlow()
            + PackagedTriggerMain.declarations() + "    resultOutput: business.response\n", io.kestra.core.models.flows.Flow.class));
        var source = new ApiResponseEnvelope.Binding("company._team", "model.test", "home", "execution1", "business.response");
        assertDoesNotThrow(() -> ApiResponseEnvelope.ready(routes, "home", "execution1", source, "SUCCESS",
            JsonNodeFactory.instance.objectNode().put("message", "ok")));
        assertThrows(IllegalArgumentException.class, () -> ApiResponseEnvelope.pending(routes, "home", "execution1", BINDING, "RUNNING"));
    }

    @Test
    void responseCannotBeCreatedForAnInactiveApiSnapshot() {
        var disabled = RouteDeclarations.from(YamlParser.parse(PackagedTriggerMain.coreFlow()
            + PackagedTriggerMain.declarations().replace("id: api-home", "disabled: true\n    id: api-home"), io.kestra.core.models.flows.Flow.class));
        assertThrows(IllegalArgumentException.class, () -> ApiResponseEnvelope.pending(disabled, "home", "execution1", BINDING, "RUNNING"));
    }

    @Test
    void allBindingDimensionsMustMatchTheSelectedApi() {
        assertThrows(IllegalArgumentException.class, () -> ApiResponseEnvelope.pending(ROUTES, "other", "execution1", BINDING, "RUNNING"));
        assertThrows(IllegalArgumentException.class, () -> ApiResponseEnvelope.pending(ROUTES, "home", "other", BINDING, "RUNNING"));
        assertThrows(IllegalArgumentException.class, () -> ApiResponseEnvelope.pending(ROUTES, "home", "execution1",
            new ApiResponseEnvelope.Binding("other", "model.test", "home", "execution1", "response"), "RUNNING"));
        assertThrows(IllegalArgumentException.class, () -> ApiResponseEnvelope.pending(ROUTES, "home", "execution1",
            new ApiResponseEnvelope.Binding("company._team", "other", "home", "execution1", "response"), "RUNNING"));
        assertThrows(IllegalArgumentException.class, () -> ApiResponseEnvelope.pending(ROUTES, "home", "execution1",
            new ApiResponseEnvelope.Binding("company._team", "model.test", "other", "execution1", "response"), "RUNNING"));
        assertThrows(IllegalArgumentException.class, () -> ApiResponseEnvelope.pending(ROUTES, "home", "execution1",
            new ApiResponseEnvelope.Binding("company._team", "model.test", "home", "other", "response"), "RUNNING"));
        assertThrows(IllegalArgumentException.class, () -> ApiResponseEnvelope.pending(ROUTES, "home", "execution1",
            new ApiResponseEnvelope.Binding("company._team", "model.test", "home", "execution1", "other"), "RUNNING"));
    }

    @Test
    void envelopeDoesNotExposeBodySuppliedIdentityOrMutableOutput() throws Exception {
        var data = JsonNodeFactory.instance.objectNode().put("executionId", "body-forged").put("apiId", "body-forged");
        var response = ApiResponseEnvelope.ready(ROUTES, "home", "execution1", BINDING, "SUCCESS", data);
        assertEquals("execution1", response.executionId());
        assertEquals("/company._team/model.test/apis/home/executions/execution1", response.executionUrl());
        response.data().put("changed", true);
        assertFalse(response.data().has("changed"));
        assertFalse(new ObjectMapper().writeValueAsString(response).contains("httpStatus"));
    }

    @Test
    void bindingValidatesOutputAndRouteIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> new ApiResponseEnvelope.Binding("company", "flow", "../api", "e1", "response"));
        assertThrows(IllegalArgumentException.class, () -> new ApiResponseEnvelope.Binding("company", "flow", "api", "", "response"));
        assertThrows(IllegalArgumentException.class, () -> new ApiResponseEnvelope.Binding("company", "flow", "api", "e1", "../output"));
    }
}
