package io.kestra.plugin.amis.apps.runtime;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class JsonInputParser {
    private final int maxBytes;
    private final ObjectMapper mapper;

    public JsonInputParser(int maxBytes, int maxDepth) {
        if (maxBytes < 1 || maxBytes == Integer.MAX_VALUE || maxDepth < 1) {
            throw new IllegalArgumentException("JSON limits require positive bytes/depth and room for one overflow byte");
        }
        this.maxBytes = maxBytes;
        StreamReadConstraints defaults = StreamReadConstraints.defaults();
        mapper = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(Math.min(maxDepth, Math.min(defaults.getMaxNestingDepth(), StreamReadConstraints.DEFAULT_MAX_DEPTH)))
                .maxStringLength(Math.min(maxBytes, Math.min(defaults.getMaxStringLength(), StreamReadConstraints.DEFAULT_MAX_STRING_LEN)))
                .maxNameLength(Math.min(maxBytes, Math.min(defaults.getMaxNameLength(), StreamReadConstraints.DEFAULT_MAX_NAME_LEN)))
                .maxNumberLength(Math.min(maxBytes, Math.min(defaults.getMaxNumberLength(), StreamReadConstraints.DEFAULT_MAX_NUM_LEN))).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public ObjectNode parse(byte[] bytes) {
        validateSize(bytes);
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("JSON input root must be an object");
            }
            return (ObjectNode) root;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid UTF-8 JSON object or nesting limit exceeded", exception);
        }
    }

    public ObjectNode parse(InputStream input) throws IOException {
        return parse(readBounded(input));
    }

    byte[] readBounded(InputStream input) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("Missing JSON input stream");
        }
        byte[] bytes = input.readNBytes(maxBytes + 1);
        validateSize(bytes);
        return bytes;
    }

    private void validateSize(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > maxBytes) {
            throw new IllegalArgumentException("JSON input must be nonempty and within the configured byte limit");
        }
    }
}
