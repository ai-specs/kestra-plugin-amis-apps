package io.kestra.plugin.amis.apps;

import io.kestra.core.docs.JsonSchemaGenerator;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.validations.ModelValidator;
import io.kestra.core.plugins.DefaultPluginRegistry;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.serializers.YamlParser;
import io.kestra.plugin.amis.apps.domain.ApiConfiguration;
import io.kestra.plugin.amis.apps.testing.HostModelContext;
import io.kestra.plugin.amis.apps.testing.PackagedTriggerMain;
import io.micronaut.core.beans.BeanIntrospector;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TriggerModelTest {
    private static HostModelContext context;
    private static ModelValidator validator;

    @BeforeAll
    static void openContext() throws Exception {
        DefaultPluginRegistry.getOrCreate();
        context = new HostModelContext();
        validator = context.modelValidator();
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void pageAndApiCanShareTheirBusinessIdButNotTriggerId() {
        Flow flow = parse(PackagedTriggerMain.declarations());
        assertDoesNotThrow(() -> validator.validate(flow));
        assertEquals("home", ((PageTrigger) flow.getTriggers().get(0)).getPageId());
        assertEquals("home", ((ApiTrigger) flow.getTriggers().get(1)).getApiId());
        Flow duplicate = parse(PackagedTriggerMain.declarations().replace("id: api-home", "id: page-home"));
        assertThrows(ConstraintViolationException.class, () -> validator.validate(duplicate));
    }

    @Test
    void modelsDoNotPermanentlyLimitTheNumberOfDeclarations() {
        String declarations = PackagedTriggerMain.declarations()
            + "  - id: page-other\n    type: " + PageTrigger.class.getName() + "\n    pageId: other\n    schema: nsfile:///other.json\n"
            + "  - id: api-other\n    type: " + ApiTrigger.class.getName() + "\n    apiId: other\n";
        Flow flow = parse(declarations);
        assertEquals(4, flow.getTriggers().size());
        assertDoesNotThrow(() -> validator.validate(flow));
    }

    @Test
    void upstreamTriggerIdValidationIsNotReplacedByBusinessIdRules() {
        Flow invalid = parse(page("home", "nsfile:///home.json").replace("id: page-home", "id: '../bad'"));
        assertThrows(ConstraintViolationException.class, () -> validator.validate(invalid));
        Flow missing = parse(page("home", "nsfile:///home.json").replace("- id: page-home", "- description: model"));
        assertThrows(ConstraintViolationException.class, () -> validator.validate(missing));
    }

    @Test
    void isolatedContextDoesNotFabricateStorageAccess() {
        var failure = assertThrows(io.micronaut.context.exceptions.NoSuchBeanException.class,
            () -> context.getBean(io.kestra.core.services.NamespaceService.class).isNamespaceExists(null, "company._team"));
        assertTrue(failure.getMessage().contains("FlowMetaStoreInterface"));
    }

    @Test
    void typesExtendAbstractTriggerDirectlyAndHaveGeneratedIntrospection() {
        for (Class<?> type : List.of(PageTrigger.class, ApiTrigger.class)) {
            assertEquals(AbstractTrigger.class, type.getSuperclass());
            assertEquals(type, DefaultPluginRegistry.getOrCreate().findClassByIdentifier(type.getName()));
            var introspection = BeanIntrospector.SHARED.getIntrospection(type);
            assertEquals(type, introspection.getBeanType());
            assertTrue(introspection.getProperty(type == PageTrigger.class ? "pageId" : "apiId").isPresent());
        }
    }

    @Test
    void generatedPluginAndFlowSchemasExposeOnlyThePublicDeclarationFields() {
        var generator = new JsonSchemaGenerator(DefaultPluginRegistry.getOrCreate());
        for (Class<?> type : List.of(PageTrigger.class, ApiTrigger.class)) {
            var schema = JacksonMapper.ofJson().valueToTree(generator.schemas(type));
            var definition = schema.path("definitions").path(type.getName());
            String identifier = type == PageTrigger.class ? "pageId" : "apiId";
            assertEquals("string", definition.path("properties").path(identifier).path("type").asText());
            assertTrue(definition.path("required").toString().contains(identifier));
            assertFalse(definition.path("properties").has("appName"));
            if (type == PageTrigger.class) {
                assertTrue(definition.path("required").toString().contains("schema"));
            } else {
                assertEquals("ASYNC", definition.path("properties").path("mode").path("default").asText());
                assertFalse(definition.path("required").toString().contains("mode"));
                assertTrue(definition.path("properties").has("syncTimeout"));
                assertEquals("response", definition.path("properties").path("resultOutput").path("default").asText());
            }
        }
        String flowSchema = generator.schemas(Flow.class).toString();
        assertTrue(flowSchema.contains(PageTrigger.class.getName()));
        assertTrue(flowSchema.contains(ApiTrigger.class.getName()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {".", "..", "a/b", "a\\b", "%2e", "%252e", "a?b", "a#b", "a b", "é", "-a"})
    void unsafePageAndApiIdsAreRejectedByModelValidator(String value) throws Exception {
        rejectField(page(value, "nsfile:///home.json"), "pageId");
        rejectField(api(value, ""), "apiId");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"https://host/page.json", "nsfile://sibling/page.json", "nsfile:/page.json",
        "nsfile:////page.json", "nsfile:///../page.json", "nsfile:///a/./page.json", "nsfile:///%2e%2e/page.json",
        "nsfile:///%252e%252e/page.json", "nsfile:///a%2fb.json", "nsfile:///a\\b.json", "nsfile:///a//b.json",
        "nsfile:///page.json?namespace=other", "nsfile:///page.json#x", "nsfile:///page.yaml", "nsfile:///页.json"})
    void unsafeSchemaReferencesAreRejectedByModelValidator(String value) throws Exception {
        rejectField(page("home", value), "schema");
    }

    @Test
    void missingRequiredFieldsFailEvenThoughJacksonCanConstructTheModel() throws Exception {
        rejectField(page("home", "nsfile:///home.json").replace("    pageId: \"home\"\n", ""), "pageId");
        rejectField(page("home", "nsfile:///home.json").replace("    schema: \"nsfile:///home.json\"\n", ""), "schema");
        rejectField(api("save", "").replace("    apiId: \"save\"\n", ""), "apiId");
        rejectField(api("save", "    mode: null\n"), "mode");
    }

    @ParameterizedTest
    @ValueSource(strings = {"    appName: legacy\n", "    unexpectedField: true\n", "    maxConcurrency: 2\n"})
    void unknownPropertiesAreRejectedByTheRealStrictYamlParser(String extra) {
        assertThrows(ConstraintViolationException.class, () -> parse(page("home", "nsfile:///home.json") + extra));
        assertThrows(ConstraintViolationException.class, () -> parse(api("save", extra)));
    }

    @Test
    void omittedModeDefaultsToAsyncAndExplicitAsyncIsAccepted() {
        ApiTrigger omitted = (ApiTrigger) parse(api("save", "")).getTriggers().getFirst();
        assertEquals(ApiConfiguration.Mode.ASYNC, omitted.getMode());
        assertNull(omitted.getSyncTimeout());
        assertDoesNotThrow(() -> validator.validate(omitted));
        assertDoesNotThrow(() -> validator.validate(parse(api("save", "    mode: ASYNC\n"))));
    }

    @Test
    void resultOutputDefaultsToResponseAndNamesAFlowOutputNotATask() {
        ApiTrigger omitted = (ApiTrigger) parse(api("save", "")).getTriggers().getFirst();
        assertEquals("response", omitted.getResultOutput());
        Flow declared = parse(api("save", "    resultOutput: business.response\n"));
        assertEquals("business.response", ((ApiTrigger) declared.getTriggers().getFirst()).getResultOutput());
        assertDoesNotThrow(() -> validator.validate(declared));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {".", "..", "../response", "a/b", "a\\b", "%2e", "a?b", "a#b", "a b", "_response", "é"})
    void invalidResultOutputIdsAreRejected(String value) throws Exception {
        rejectField(api("save", "    resultOutput: " + json(value) + "\n"), "resultOutput");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0.000000001S", "PT1S", "PT30S"})
    void explicitSyncAllowsOnlyPositiveBoundedDurations(String timeout) {
        Flow flow = parse(api("save", "    mode: SYNC\n    syncTimeout: " + timeout + "\n"));
        assertDoesNotThrow(() -> validator.validate(flow));
        ApiTrigger trigger = (ApiTrigger) flow.getTriggers().getFirst();
        assertEquals(ApiConfiguration.Mode.SYNC, trigger.getMode());
        assertEquals(Duration.parse(timeout), trigger.getSyncTimeout());
    }

    @ParameterizedTest
    @ValueSource(strings = {"    mode: SYNC\n", "    mode: SYNC\n    syncTimeout: null\n",
        "    mode: SYNC\n    syncTimeout: PT0S\n", "    mode: SYNC\n    syncTimeout: PT-1S\n",
        "    mode: SYNC\n    syncTimeout: PT30.000000001S\n", "    mode: ASYNC\n    syncTimeout: PT1S\n",
        "    syncTimeout: PT1S\n"})
    void invalidModeTimeoutCombinationsFailModelValidation(String extra) throws Exception {
        rejectField(api("save", extra), "syncTimeout");
    }

    @ParameterizedTest
    @ValueSource(strings = {"    mode: UNKNOWN\n", "    mode: sync\n", "    mode: [SYNC]\n",
        "    mode: SYNC\n    syncTimeout: not-a-duration\n"})
    void invalidEnumAndDurationSyntaxFailParsing(String extra) {
        assertThrows(ConstraintViolationException.class, () -> parse(api("save", extra)));
    }

    @Test
    void declarationLengthBoundsAreValidatedThroughTheDomainParser() throws Exception {
        assertDoesNotThrow(() -> validator.validate(parse(page("a".repeat(100), "nsfile:///home.json"))));
        rejectField(page("a".repeat(101), "nsfile:///home.json"), "pageId");
        rejectField(api("a".repeat(101), ""), "apiId");
        rejectField(page("home", "nsfile:///" + "a".repeat(129) + ".json"), "schema");
    }

    private static String page(String pageId, String schema) {
        return "triggers:\n  - id: page-home\n    type: " + PageTrigger.class.getName() + "\n    pageId: " + json(pageId)
            + "\n    schema: " + json(schema) + "\n";
    }

    private static String api(String apiId, String extra) {
        return "triggers:\n  - id: api-home\n    type: " + ApiTrigger.class.getName() + "\n    apiId: " + json(apiId) + "\n" + extra;
    }

    private static String json(String value) {
        return JacksonMapper.ofJson().valueToTree(value).toString();
    }

    private static Flow parse(String declarations) {
        return YamlParser.parse(PackagedTriggerMain.coreFlow() + declarations, Flow.class);
    }

    private static void rejectField(String declarations, String field) throws Exception {
        Flow flow = parse(declarations);
        ConstraintViolationException failure = assertThrows(ConstraintViolationException.class, () -> validator.validate(flow));
        assertTrue(failure.getConstraintViolations().stream().anyMatch(violation -> violation.getPropertyPath().toString().contains(field)),
            failure.getMessage());
    }
}
