package io.kestra.plugin.amis.apps;

import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.flows.Type;
import io.kestra.core.plugins.DefaultPluginRegistry;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.serializers.YamlParser;
import io.kestra.plugin.amis.apps.runtime.RouteDeclarations;
import io.kestra.plugin.amis.apps.testing.HostModelContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class P2ExamplesTest {
    @Test void allNormalAndVariantFlowsParseAndValidateAgainstFormalHostInputTypes() throws Exception {
        DefaultPluginRegistry.getOrCreate();
        try (var context = new HostModelContext(); var files = Files.walk(Path.of("examples/p2"))) {
            var paths = files.filter(path -> path.toString().endsWith(".yaml")).sorted().toList();
            assertEquals(5, paths.size(), "Every public normal and negative-purpose Flow must be validated");
            for (Path path : paths) {
                Flow flow = YamlParser.parse(Files.readString(path), Flow.class);
                assertDoesNotThrow(() -> context.modelValidator().validate(flow), path.toString());
                var inputs = flow.getInputs().stream().collect(Collectors.toMap(input -> input.getId(), input -> input.getType()));
                assertEquals(Type.INT, inputs.get("quantity"), path.toString());
                assertEquals(Type.BOOL, inputs.get("enabled"), path.toString());
                assertEquals(Type.ARRAY, inputs.get("tags"), path.toString());
                assertEquals(Type.STRING, inputs.get("apiId"), path.toString());
                var routes = RouteDeclarations.from(flow);
                assertEquals(RouteDeclarations.Status.ACTIVE, routes.activation(), path.toString());
                assertEquals(Set.of("home", "detail"), routes.pages().stream().map(RouteDeclarations.Page::pageId).collect(Collectors.toSet()));
                assertEquals(Set.of("alpha", "beta"), routes.apis().stream().map(RouteDeclarations.Api::apiId).collect(Collectors.toSet()));
                assertEquals(2, routes.pages().size());
                assertEquals(2, routes.apis().size());
                var outputs = flow.getOutputs().stream().collect(Collectors.toMap(output -> output.getId(), output -> output));
                assertEquals(Set.of("alphaResponse", "betaResponse"), outputs.keySet());
                for (var entry : Map.of("alphaResponse", "alphaValue", "betaResponse", "betaValue").entrySet()) {
                    var output = outputs.get(entry.getKey());
                    assertEquals(Type.JSON, output.getType());
                    if (path.getFileName().toString().equals("invalid-output.yaml") && entry.getKey().equals("alphaResponse")) {
                        assertEquals("[]", output.getValue(), "Intentional invalid-output production negative");
                    } else {
                        assertEquals("{{ outputs." + entry.getValue() + ".value }}", output.getValue(), path.toString());
                    }
                    var task = flow.getTasks().stream().filter(item -> item.getId().equals(entry.getValue())).findFirst().orElseThrow();
                    assertInstanceOf(io.kestra.plugin.core.debug.Return.class, task);
                }
                var alpha = routes.api(flow.getNamespace(), flow.getId(), "alpha").declaration();
                var beta = routes.api(flow.getNamespace(), flow.getId(), "beta").declaration();
                assertEquals("invokeAlpha", alpha.triggerId());
                assertEquals("invokeBeta", beta.triggerId());
                assertEquals("betaResponse", beta.resultOutput());
                if (path.getFileName().toString().equals("missing-output.yaml")) {
                    assertFalse(outputs.containsKey(alpha.resultOutput()), "Intentional missing-result declaration negative");
                } else {
                    assertEquals("alphaResponse", alpha.resultOutput());
                }
            }
        }
    }

    @Test void scenarioCatalogueCoversEveryFlowAndUsesRealPageSchemaNavigation() throws Exception {
        var mapper = JacksonMapper.ofJson();
        var catalogue = mapper.readTree(Files.readString(Path.of("examples/p2/scenarios.json")));
        Set<Path> listed = new HashSet<>();
        for (String group : Set.of("scenarios", "variants")) {
            for (var scenario : catalogue.path(group)) {
                Path path = Path.of(scenario.path("flow").asText());
                assertTrue(listed.add(path), "Duplicate public Flow path");
                assertTrue(Files.isRegularFile(path));
                if (group.equals("scenarios")) {
                    Flow flow = YamlParser.parse(Files.readString(path), Flow.class);
                    assertEquals(scenario.path("namespace").asText(), flow.getNamespace());
                    assertEquals(scenario.path("flowId").asText(), flow.getId());
                    for (var page : scenario.path("pages")) {
                        var schema = mapper.readTree(Files.readString(Path.of(page.path("schema").asText())));
                        assertEquals("page", schema.path("type").asText());
                        var button = schema.path("body").get(0);
                        assertEquals("url", button.path("actionType").asText());
                        assertEquals(page.path("pageId").asText().equals("home") ? "detail" : "home", button.path("url").asText());
                    }
                }
            }
        }
        try (var paths = Files.walk(Path.of("examples/p2"))) {
            assertEquals(paths.filter(path -> path.toString().endsWith(".yaml")).collect(Collectors.toSet()), listed);
        }
    }
}
