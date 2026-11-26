package io.kestra.plugin.amis.apps;

import io.kestra.core.models.flows.Flow;
import io.kestra.core.plugins.DefaultPluginRegistry;
import io.kestra.core.serializers.YamlParser;
import io.kestra.plugin.amis.apps.testing.HostModelContext;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class P1ExamplesTest {
    @Test void publicScenariosParseAndValidateAgainstTheFormalHost() throws Exception {
        DefaultPluginRegistry.getOrCreate();
        try (var context = new HostModelContext(); var files = Files.list(Path.of("examples/p1"))) {
            var flows = files.filter(path -> path.toString().endsWith(".yaml")).sorted().toList();
            assertEquals(7, flows.size());
            for (Path path : flows) {
                Flow flow = YamlParser.parse(Files.readString(path), Flow.class);
                assertDoesNotThrow(() -> context.modelValidator().validate(flow), path.toString());
                assertEquals(2, flow.getTriggers().size());
                assertEquals("home", ((PageTrigger) flow.getTriggers().get(0)).getPageId());
                assertEquals("run", ((ApiTrigger) flow.getTriggers().get(1)).getApiId());
                assertEquals("response", flow.getOutputs().get(0).getId());
                assertEquals("{{ outputs.responseValue.value }}", flow.getOutputs().get(0).getValue());
                assertInstanceOf(io.kestra.plugin.core.debug.Return.class, flow.getTasks().get(0));
                assertEquals("responseValue", flow.getTasks().get(0).getId());
                var declaration = io.kestra.core.serializers.JacksonMapper.ofJson().readTree(Files.readString(path));
                assertEquals("JSON", declaration.path("outputs").get(0).path("type").asText());
                assertTrue(declaration.path("tasks").get(0).path("format").asText().contains("| toJson"));
            }
        }
    }
}
