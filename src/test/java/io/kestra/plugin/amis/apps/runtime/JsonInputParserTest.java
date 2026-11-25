package io.kestra.plugin.amis.apps.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class JsonInputParserTest {
    @Test
    void limitsMustBePositiveAndLeaveRoomForTheOverflowProbe() {
        assertThrows(IllegalArgumentException.class, () -> new JsonInputParser(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new JsonInputParser(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new JsonInputParser(Integer.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> new JsonInputParser(32, 0));
        assertThrows(IllegalArgumentException.class, () -> new JsonInputParser(32, -1));
        assertDoesNotThrow(() -> new JsonInputParser(Integer.MAX_VALUE - 1, 1).parse(bytes("{}")));
    }

    @Test
    void acceptsObjectsWithoutRenderingTemplateStrings() {
        String template = "{{ readFile('/not-executed') }}";
        String json = "{\"template\":\"" + template + "\",\"name\":\"中文😀\",\"items\":[1,true,null,{\"x\":2}]}";
        var parsed = new JsonInputParser(bytes(json).length, 3).parse(bytes(json));
        assertEquals(template, parsed.path("template").textValue());
        assertEquals("中文😀", parsed.path("name").textValue());
        assertEquals(4, parsed.path("items").size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "null", "[]", "1", "true", "\"text\"", "{} {}", "{} trailing",
        "{\"a\":1,\"a\":2}", "{\"nested\":{\"a\":1,\"a\":2}}", "{unquoted:1}", "{'a':1}", "{\"a\":1,}",
        "{/*comment*/}", "{\"a\":NaN}", "{\"a\":Infinity}", "{\"a\":", "\uFEFF{}"})
    void rejectsNonObjectsAndNonStrictJson(String json) {
        assertThrows(IllegalArgumentException.class, () -> new JsonInputParser(512, 8).parse(bytes(json)));
    }

    @Test
    void missingByteArrayAndStreamAreRejected() {
        var parser = new JsonInputParser(128, 4);
        assertThrows(IllegalArgumentException.class, () -> parser.parse((byte[]) null));
        assertThrows(IllegalArgumentException.class, () -> parser.parse((InputStream) null));
    }

    @Test
    void byteLimitCountsUtf8BytesNotCharactersAndAcceptsTheExactBoundary() {
        String json = "{\"name\":\"中文\"}";
        byte[] encoded = bytes(json);
        assertDoesNotThrow(() -> new JsonInputParser(encoded.length, 1).parse(encoded));
        assertThrows(IllegalArgumentException.class, () -> new JsonInputParser(encoded.length - 1, 1).parse(encoded));
        assertThrows(IllegalArgumentException.class, () -> new JsonInputParser(json.length(), 1).parse(encoded));
    }

    @Test
    void malformedUtf8IsNotReplacedOrAcceptedAsAnotherEncoding() {
        byte[] malformed = new byte[] {'{', '"', 'a', '"', ':', '"', (byte) 0xc3, 0x28, '"', '}'};
        var failure = assertThrows(IllegalArgumentException.class,
            () -> new JsonInputParser(128, 4).parse(malformed));
        assertInstanceOf(CharacterCodingException.class, failure.getCause());
        byte[] utf16 = "{}".getBytes(StandardCharsets.UTF_16);
        assertThrows(IllegalArgumentException.class, () -> new JsonInputParser(128, 4).parse(utf16));
    }

    @Test
    void configuredDepthIsEnforcedAndInstancesDoNotChangeEachOthersLimits() {
        byte[] json = bytes("{\"a\":{\"b\":1}}");
        var shallow = new JsonInputParser(128, 1);
        var deeper = new JsonInputParser(128, 2);
        assertThrows(IllegalArgumentException.class, () -> shallow.parse(json));
        assertDoesNotThrow(() -> deeper.parse(json));
        assertThrows(IllegalArgumentException.class, () -> shallow.parse(json));
        assertDoesNotThrow(() -> new JsonInputParser(2, 1).parse(bytes("{}")));
    }

    @Test
    void byteBudgetDoesNotRaiseJacksonDefaultNumberOrFieldNameLimits() {
        String longNumber = "{" + "\"n\":" + "1".repeat(1001) + "}";
        String longName = "{\"" + "n".repeat(50001) + "\":1}";
        var parser = new JsonInputParser(1_048_576, 64);
        assertTrue(longNumber.getBytes(StandardCharsets.UTF_8).length < 1_048_576);
        assertTrue(longName.getBytes(StandardCharsets.UTF_8).length < 1_048_576);
        assertThrows(IllegalArgumentException.class, () -> parser.parse(bytes(longNumber)));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(bytes(longName)));
        assertDoesNotThrow(() -> parser.parse(bytes("{\"n\":" + "1".repeat(1000) + "}")));
        assertDoesNotThrow(() -> parser.parse(bytes("{\"" + "n".repeat(50000) + "\":1}")));
    }

    @Test
    void configuredDepthCannotRaiseJacksonDefaultDepth() {
        String nested = "[".repeat(1001) + "0" + "]".repeat(1001);
        var failure = assertThrows(IllegalArgumentException.class,
            () -> new JsonInputParser(1_048_576, 2_000).parse(bytes("{\"nested\":" + nested + "}")));
        assertInstanceOf(com.fasterxml.jackson.core.exc.StreamConstraintsException.class, failure.getCause());
    }

    @Test
    void nestingBoundaryIncludesTheRootObjectAndArrays() {
        String exact = "{\"body\":" + "[".repeat(63) + "0" + "]".repeat(63) + "}";
        String excessive = "{\"body\":" + "[".repeat(64) + "0" + "]".repeat(64) + "}";
        var parser = new JsonInputParser(512, 64);
        assertDoesNotThrow(() -> parser.parse(bytes(exact)));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(bytes(excessive)));
    }

    @Test
    void borrowedStreamIsNotClosedAndOnlyOneOverflowByteIsRead() throws Exception {
        var parser = new JsonInputParser(8, 2);
        var excessive = new TrackingStream(new byte[100]);
        assertThrows(IllegalArgumentException.class, () -> parser.parse(excessive));
        assertEquals(9, excessive.bytesRead);
        assertEquals(0, excessive.closeCount);
        var valid = new TrackingStream(bytes("{}"));
        assertTrue(parser.parse(valid).isObject());
        assertEquals(0, valid.closeCount);
        valid.close();
        assertEquals(1, valid.closeCount);
    }

    @Test
    void streamReadFailuresArePropagatedAndOwnershipRemainsWithCaller() {
        var stream = new TrackingStream(bytes("{}"));
        stream.readFailure = new IOException("input read failure");
        IOException failure = assertThrows(IOException.class, () -> new JsonInputParser(128, 4).parse(stream));
        assertSame(stream.readFailure, failure);
        assertEquals(0, stream.closeCount);
    }

    @Test
    void trailingWhitespaceIsAllowedButTrailingValuesAreNot() throws Exception {
        var parser = new JsonInputParser(128, 4);
        assertDoesNotThrow(() -> parser.parse(bytes(" \t\r\n{} \n")));
        var stream = new TrackingStream(bytes("{} []"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(stream));
        assertEquals(0, stream.closeCount);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class TrackingStream extends InputStream {
        private final ByteArrayInputStream delegate;
        private int bytesRead;
        private int closeCount;
        private IOException readFailure;

        private TrackingStream(byte[] bytes) {
            delegate = new ByteArrayInputStream(bytes);
        }

        @Override
        public int read() throws IOException {
            if (readFailure != null) {
                throw readFailure;
            }
            int value = delegate.read();
            if (value >= 0) {
                bytesRead++;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (readFailure != null) {
                throw readFailure;
            }
            int count = delegate.read(bytes, offset, length);
            if (count > 0) {
                bytesRead += count;
            }
            return count;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }
}
