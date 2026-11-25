package io.kestra.plugin.amis.apps.domain;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class AmisSchema {
    public static final int MAX_BYTES = 1_048_576;
    public static final int MAX_DEPTH = 64;
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_DEPTH).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private AmisSchema() { }

    public static JsonNode parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Schema byte limit exceeded or empty input");
        }
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isObject() || !root.path("type").isTextual()
                || !"page".equals(root.path("type").textValue())) {
                throw new IllegalArgumentException("AMIS schema root must be an object with type=page");
            }
            return root;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Schema must be UTF-8", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid or over-depth JSON schema", exception);
        }
    }
}
