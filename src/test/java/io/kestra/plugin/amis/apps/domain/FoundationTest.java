package io.kestra.plugin.amis.apps.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FoundationTest {
    @Test
    void routesAndIdentifiers() {
        assertEquals(new AppRoute("company.team", "my_flow-1", AppRoute.Kind.PAGE, "home"),
            AppRoute.parse("/company.team/my_flow-1/pages/home"));
        assertEquals(AppRoute.Kind.API, AppRoute.parse("/company/f/apis/save").kind());
        assertEquals("flow.v1", AppRoute.parse("/company._team/flow.v1/apis/save").flowId());
        assertEquals("a".repeat(100), Identifiers.id("a".repeat(100)));
        assertEquals("a".repeat(150), Identifiers.namespace("a".repeat(150)));
        assertThrows(IllegalArgumentException.class, () -> Identifiers.id("a".repeat(101)));
        assertThrows(IllegalArgumentException.class, () -> Identifiers.namespace("a".repeat(151)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {".", "..", "a/b", "a\\b", "%2e", "%252e", "a?b", "a#b", "a b", "é", "a\n", "-a"})
    void invalidIds(String value) {
        assertThrows(IllegalArgumentException.class, () -> Identifiers.id(value));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Company", ".a", "a.", "a..b", "a/b", "a%2eb", "a b", "a.*", "../a"})
    void invalidNamespaces(String value) {
        assertThrows(IllegalArgumentException.class, () -> Identifiers.namespace(value));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"https://example/a/f/pages/p", "//a/f/pages/p", "/a/f/pages/p/", "/a/f/pages/p?x=1",
        "/a/f/pages/p#x", "/a/f/pages/%2e%2e", "/a/f/pages/%252e%252e", "/a/f/pages/..", "/a/f/pages/a%2fb",
        "/a/f/pages/a\\b", "/a/f/other/p", "/a/f/pages", "/a/f/pages/a/b", "/a/f/apis/", "/a//pages/p"})
    void unsafeRoutes(String value) {
        assertThrows(IllegalArgumentException.class, () -> AppRoute.parse(value));
    }

    @Test
    void schemaCandidatesAreCurrentThenParentsOnly() {
        SchemaReference reference = SchemaReference.parse("nsfile:///pages/home.json");
        assertEquals(List.of(new SchemaReference.Candidate("company.team.app", "/pages/home.json"),
            new SchemaReference.Candidate("company.team", "/pages/home.json"),
            new SchemaReference.Candidate("company", "/pages/home.json")), reference.candidates("company.team.app"));
        assertEquals(1, reference.candidates("company").size());
        assertThrows(UnsupportedOperationException.class, () -> reference.candidates("company").clear());
        assertThrows(IllegalArgumentException.class, () -> reference.candidates("company..team"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"https://host/page.json", "nsfile://sibling/page.json", "nsfile:/page.json",
        "nsfile:page.json", "nsfile:////page.json", "nsfile:///../page.json", "nsfile:///a/./page.json",
        "nsfile:///%2e%2e/page.json", "nsfile:///%252e%252e/page.json", "nsfile:///a%2fb.json",
        "nsfile:///a\\b.json", "nsfile:///a//b.json", "nsfile:///page.json?ns=other", "nsfile:///page.json#x",
        "nsfile:///page.yaml", "nsfile:///", "nsfile:///页.json", "nsfile://user@host:80/a.json"})
    void unsafeSchemaReferences(String value) {
        assertThrows(IllegalArgumentException.class, () -> SchemaReference.parse(value));
    }

    @Test
    void boundedJson() {
        assertEquals("page", schema("{\"type\":\"page\",\"body\":[]}").path("type").textValue());
        String prefix = "{\"type\":\"page\",\"x\":\"";
        String suffix = "\"}";
        byte[] exact = (prefix + "a".repeat(AmisSchema.MAX_BYTES - prefix.length() - suffix.length()) + suffix)
            .getBytes(StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> AmisSchema.parse(exact));
        assertThrows(IllegalArgumentException.class, () -> AmisSchema.parse(new byte[AmisSchema.MAX_BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> AmisSchema.parse(new byte[] {(byte) 0xc3, 0x28}));
        assertThrows(IllegalArgumentException.class, () -> AmisSchema.parse(null));
        assertDoesNotThrow(() -> schema("{\"type\":\"page\",\"x\":" + "[".repeat(63) + "0" + "]".repeat(63) + "}"));
        assertThrows(IllegalArgumentException.class,
            () -> schema("{\"type\":\"page\",\"x\":" + "[".repeat(64) + "0" + "]".repeat(64) + "}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "1", "true", "{}", "{\"type\":[]}", "{\"type\":\"form\"}",
        "{\"type\":\"page\"} {}", "{\"type\":\"page\",\"type\":\"page\"}", "{\"type\":\"page\",}",
        "{\"type\":\"page\",/*comment*/\"body\":[]}"})
    void invalidSchema(String value) {
        assertThrows(IllegalArgumentException.class, () -> schema(value));
    }

    @Test
    void declarationUniquenessAndDefaults() {
        var page = new FlowDeclarations.Page("home", SchemaReference.parse("nsfile:///home.json"));
        var api = new FlowDeclarations.Api("save", null);
        assertEquals(ApiConfiguration.Mode.ASYNC, api.configuration().mode());
        assertDoesNotThrow(() -> new FlowDeclarations("company", "flow", List.of(page), List.of(api)));
        assertThrows(IllegalArgumentException.class,
            () -> new FlowDeclarations("company", "flow", List.of(page, page), List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new FlowDeclarations("company", "flow", List.of(), List.of(api, api)));
        assertDoesNotThrow(
            () -> new FlowDeclarations("company", "flow", List.of(page), List.of(new FlowDeclarations.Api("home", null))));
        assertThrows(IllegalArgumentException.class,
            () -> new FlowDeclarations("company", "flow", null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new FlowDeclarations.Page("home", null));
    }

    @Test
    void explicitSyncBounds() {
        assertEquals(ApiConfiguration.Mode.ASYNC, ApiConfiguration.defaults().mode());
        assertDoesNotThrow(() -> new ApiConfiguration(ApiConfiguration.Mode.SYNC, Duration.ofNanos(1)));
        assertDoesNotThrow(() -> new ApiConfiguration(ApiConfiguration.Mode.SYNC, Duration.ofSeconds(30)));
        for (Duration timeout : List.of(Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(30).plusNanos(1))) {
            assertThrows(IllegalArgumentException.class, () -> new ApiConfiguration(ApiConfiguration.Mode.SYNC, timeout));
        }
        for (int concurrency : List.of(0, -1, 33)) {
            assertThrows(IllegalArgumentException.class,
                () -> new RuntimeLimits(concurrency));
        }
        assertDoesNotThrow(() -> new RuntimeLimits(1));
        assertDoesNotThrow(() -> new RuntimeLimits(32));
        assertThrows(IllegalArgumentException.class, () -> new ApiConfiguration(ApiConfiguration.Mode.SYNC, null));
        assertThrows(IllegalArgumentException.class, () -> new ApiConfiguration(null, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ApiConfiguration(ApiConfiguration.Mode.ASYNC, Duration.ofSeconds(1)));
    }

    private com.fasterxml.jackson.databind.JsonNode schema(String json) {
        return AmisSchema.parse(json.getBytes(StandardCharsets.UTF_8));
    }
}
