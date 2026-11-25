package io.kestra.plugin.amis.apps.testing;

import io.kestra.core.docs.JsonSchemaGenerator;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.plugins.DefaultPluginRegistry;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.serializers.YamlParser;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.validation.validator.Validator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PackagedTriggerMain {
    private static final String PAGE = "io.kestra.plugin.amis.apps.PageTrigger";
    private static final String API = "io.kestra.plugin.amis.apps.ApiTrigger";

    public static void main(String[] arguments) throws Exception {
        String scenario = arguments[0];
        Path output = Path.of(arguments[1]);
        Files.createDirectories(output);
        boolean present = scenario.equals("present") || scenario.equals("metadata");
        Map<String, Object> report = new LinkedHashMap<>();
        DefaultPluginRegistry registry = DefaultPluginRegistry.getOrCreate();
        require(registry.findMetadataByIdentifier("io.kestra.plugin.core.log.Log").isPresent(), "Core log missing");
        for (String type : List.of(PAGE, API)) {
            boolean discovered = registry.findMetadataByIdentifier(type).isPresent();
            require(discovered == present, "Unexpected plugin discovery for " + type);
            report.put(type, discovered);
            if (present) {
                Class<?> model = registry.findClassByIdentifier(type);
                require(model.getSuperclass() == AbstractTrigger.class, "Unexpected trigger superclass");
                var introspection = BeanIntrospector.SHARED.getIntrospection(model);
                var properties = introspection.getBeanProperties().stream().map(property -> property.getName()).toList();
                require(properties.contains(type.equals(PAGE) ? "pageId" : "apiId"), "Introspection omitted identifier");
                require(properties.contains(type.equals(PAGE) ? "schema" : "syncTimeout"), "Introspection omitted declaration field");
                if (type.equals(API)) {
                    require(properties.contains("resultOutput"), "Introspection omitted resultOutput");
                }
                require(model.getProtectionDomain().getCodeSource().getLocation().toURI().equals(Path.of(arguments[2]).toUri()),
                    "Model did not load from the tested thin jar");
                require(introspection.getClass().getProtectionDomain().getCodeSource().getLocation().toURI()
                    .equals(Path.of(arguments[2]).toUri()), "Introspection did not load from the tested thin jar");
                report.put(type + ".introspection", introspection.getClass().getName());
            }
        }
        if (!scenario.equals("metadata")) {
            try (HostModelContext context = new HostModelContext()) {
                require(context.getBean(Validator.class).getClass().getName()
                    .equals("io.micronaut.configuration.hibernate.validator.MicronautHibernateValidator"), "Wrong validation engine");
                context.modelValidator().validate(YamlParser.parse(coreFlow(), Flow.class));
                String flowSchema = JacksonMapper.ofJson().writeValueAsString(new JsonSchemaGenerator(registry).schemas(Flow.class));
                require(flowSchema.contains(PAGE) == present && flowSchema.contains(API) == present, "Flow schema subtype mismatch");
                Files.writeString(output.resolve("flow-schema.json"), flowSchema);
                if (present) {
                    Flow flow = YamlParser.parse(coreFlow() + declarations(), Flow.class);
                    context.modelValidator().validate(flow);
                    require(flow.getTriggers().size() == 2, "Missing parsed trigger");
                    require(flow.getTriggers().get(1).getClass().getMethod("getMode").invoke(flow.getTriggers().get(1)).toString()
                        .equals("ASYNC"), "Missing API mode did not default to ASYNC");
                    for (String type : List.of(PAGE, API)) {
                        var schema = new JsonSchemaGenerator(registry).schemas(registry.findClassByIdentifier(type));
                        Files.writeString(output.resolve(type.substring(type.lastIndexOf('.') + 1) + "-schema.json"),
                            JacksonMapper.ofJson().writeValueAsString(schema));
                    }
                } else {
                    try {
                        YamlParser.parse(coreFlow() + declarations(), Flow.class);
                        throw new AssertionError("Missing plugin type was accepted");
                    } catch (io.kestra.core.exceptions.InvalidTypeConstraintViolationException expected) {
                        require(expected.getMessage().contains(PAGE), "Wrong missing-type rejection");
                        report.put("absentTypeRejected", expected.getMessage());
                    }
                }
                report.put("coreFlowModelValidation", true);
                report.put("flowSchema", true);
            }
        }
        report.put("passed", true);
        Files.writeString(output.resolve("result.json"), JacksonMapper.ofJson().writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println(report);
    }

    public static String coreFlow() {
        return "id: model.test\nnamespace: company._team\ntasks:\n  - id: log\n    type: io.kestra.plugin.core.log.Log\n    message: model test\n";
    }

    public static String declarations() {
        return "triggers:\n  - id: page-home\n    type: " + PAGE + "\n    pageId: home\n    schema: nsfile:///pages/home.json\n"
            + "  - id: api-home\n    type: " + API + "\n    apiId: home\n";
    }

    private static void require(boolean condition, String failure) {
        if (!condition) {
            throw new AssertionError(failure);
        }
    }
}
