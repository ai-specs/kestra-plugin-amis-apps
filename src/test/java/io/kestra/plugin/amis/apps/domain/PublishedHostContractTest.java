package io.kestra.plugin.amis.apps.domain;

import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class PublishedHostContractTest {
    @Test
    void flowIdsMatchPublishedPatternAndSize() throws Exception {
        assertMatchesHost("id", Identifiers::flowId, Arrays.asList(null, "", "a", "Flow.v1", "a..b", "a.",
            "a-b_c", "_a", "-a", ".a", ".", "..", "a/b", "a\\b", "a%2eb", "a b", "a\n", "中",
            "a".repeat(100), "a".repeat(101)));
    }

    @Test
    void namespacesMatchPublishedPatternAndSize() throws Exception {
        assertMatchesHost("namespace", Identifiers::namespace, Arrays.asList(null, "", "company", "company._team",
            "company.-team", "company._", "company.-", "company1.team_a-b", "_company", "-company", "Company",
            "company.Team", "company.", "company..team", ".company", "company/team", "company%2eteam",
            "company.*", "company\n", "a".repeat(150), "a".repeat(151)));
    }

    @Test
    void jacksonLoadedOnlyFromThePublishedHostArtifact() throws Exception {
        for (Class<?> type : List.of(com.fasterxml.jackson.databind.ObjectMapper.class,
            com.fasterxml.jackson.core.JsonFactory.class, com.fasterxml.jackson.annotation.JsonProperty.class)) {
            assertTrue(type.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith("/host-webserver.jar"));
        }
        assertEquals("2.22.3", new com.fasterxml.jackson.databind.ObjectMapper().version().toString());
        assertEquals("2.22.3", new com.fasterxml.jackson.core.JsonFactory().version().toString());
    }

    private void assertMatchesHost(String fieldName, Function<String, String> validator, List<String> values) throws Exception {
        var field = Class.forName("io.kestra.core.models.flows.AbstractFlow", false, getClass().getClassLoader())
            .getDeclaredField(fieldName);
        var pattern = field.getAnnotation(jakarta.validation.constraints.Pattern.class);
        var size = field.getAnnotation(Size.class);
        assertNotNull(pattern);
        assertNotNull(size);
        for (String value : values) {
            boolean expected = value != null && value.length() >= size.min() && value.length() <= size.max()
                && java.util.regex.Pattern.matches(pattern.regexp(), value);
            if (expected) {
                assertEquals(value, validator.apply(value));
            } else {
                assertThrows(IllegalArgumentException.class, () -> validator.apply(value));
            }
        }
    }
}
