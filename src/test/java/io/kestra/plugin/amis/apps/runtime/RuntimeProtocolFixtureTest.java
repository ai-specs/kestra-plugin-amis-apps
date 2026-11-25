package io.kestra.plugin.amis.apps.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.serializers.YamlParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeProtocolFixtureTest {
    private static Stream<Arguments> cases() throws Exception {
        Path fixturePath = Path.of("contracts/runtime-v1-cases.json");
        assertTrue(Files.isRegularFile(fixturePath), "Shared runtime fixture must be present");
        JsonNode fixture = new ObjectMapper().readTree(Files.readString(fixturePath));
        assertEquals(1, fixture.path("version").intValue());
        assertTrue(fixture.path("cases").isArray());
        assertFalse(fixture.path("cases").isEmpty());
        return StreamSupport.stream(fixture.path("cases").spliterator(), false)
            .map(testCase -> Arguments.of(testCase.path("id").textValue(), fixture, testCase));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void javaProducerMatchesSharedRuntimeV1FixtureWithoutUsingMessageAsAnAssertion(
        String id, JsonNode fixture, JsonNode testCase) throws Exception {
        JsonNode bindingNode = fixture.path("binding");
        ApiResponseEnvelope.Binding binding = new ApiResponseEnvelope.Binding(
            bindingNode.path("namespace").textValue(), bindingNode.path("flowId").textValue(),
            bindingNode.path("apiId").textValue(), bindingNode.path("executionId").textValue(),
            bindingNode.path("resultOutput").textValue());
        RouteDeclarations routes = routes(binding);
            assertTrue(Set.of("pending", "ready", "failed").contains(testCase.path("factory").textValue()), id);
            boolean reject = testCase.path("reject").asBoolean(false);
            try {
                ApiResponseEnvelope actual = invoke(testCase, routes, binding);
                if (reject) {
                    fail("Fixture reject case was accepted: " + id);
                }
                assertEquals(testCase.path("httpStatus").asInt(), actual.httpStatus(), id + " httpStatus");
                assertEquals(testCase.path("expect").path("status").asInt(), actual.status(), id + " status");
                assertEquals(testCase.path("executionState").textValue(), actual.executionState(), id + " state");
                assertEquals(testCase.path("expect").path("resultReady").asBoolean(), actual.resultReady(), id + " readiness");
                assertEquals(testCase.path("expect").path("data"), actual.data(), id + " data");
                assertEquals(1, actual.version(), id + " version");
                assertEquals(binding.executionId(), actual.executionId(), id + " executionId");
                assertEquals("/" + binding.namespace() + "/" + binding.flowId() + "/apis/" + binding.apiId()
                    + "/executions/" + binding.executionId(), actual.executionUrl(), id + " executionUrl");
                var serialized = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(actual));
                assertEquals(actual.data(), serialized.path("data"), id + " serialized data");
                assertEquals(actual.resultReady(), serialized.path("resultReady").booleanValue(), id + " serialized readiness");
                assertEquals(actual.executionId(), serialized.path("executionId").textValue(), id + " serialized executionId");
            } catch (IllegalArgumentException failure) {
                if (!reject) {
                    throw new AssertionError("Fixture valid case rejected: " + id, failure);
                }
            }
    }

    private static ApiResponseEnvelope invoke(JsonNode testCase, RouteDeclarations routes,
                                               ApiResponseEnvelope.Binding binding) {
        String factory = testCase.path("factory").textValue();
        String state = testCase.path("executionState").textValue();
        JsonNode selectedOutput = testCase.get("selectedOutput");
        return switch (factory) {
            case "pending" -> ApiResponseEnvelope.pending(routes, binding.apiId(), binding.executionId(), binding, state);
            case "ready" -> ApiResponseEnvelope.ready(routes, binding.apiId(), binding.executionId(), binding, state, selectedOutput);
            case "failed" -> ApiResponseEnvelope.failed(routes, binding.apiId(), binding.executionId(), binding, state, selectedOutput);
            default -> throw new IllegalArgumentException("Unknown fixture factory: " + factory);
        };
    }

    private static RouteDeclarations routes(ApiResponseEnvelope.Binding binding) {
        String yaml = "id: " + binding.flowId() + "\nnamespace: " + binding.namespace() + "\n"
            + "tasks:\n  - id: log\n    type: io.kestra.plugin.core.log.Log\n    message: fixture\n"
            + "triggers:\n  - id: api-trigger\n    type: io.kestra.plugin.amis.apps.ApiTrigger\n    apiId: "
            + binding.apiId() + "\n    resultOutput: " + binding.resultOutput() + "\n";
        Flow flow = YamlParser.parse(yaml, Flow.class);
        return RouteDeclarations.from(flow);
    }
}
