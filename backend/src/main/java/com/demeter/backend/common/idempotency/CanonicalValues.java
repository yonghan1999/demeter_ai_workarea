package com.demeter.backend.common.idempotency;

import com.demeter.backend.security.TokenDigests;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds an unambiguous, UTF-8 based representation for command fingerprints.
 * Null, empty text, and collection boundaries are encoded separately.
 */
public final class CanonicalValues {

    private CanonicalValues() {
    }

    public static Encoder builder() {
        return new Encoder();
    }

    public static String sha256(Object... values) {
        Encoder encoder = builder();
        for (Object value : values) {
            encoder.add(value);
        }
        return encoder.digest();
    }

    public static final class Encoder {

        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private Encoder() {
        }

        public Encoder add(Object value) {
            if (value == null) {
                output.write(0);
                return this;
            }
            byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
            output.write(1);
            writeInt(bytes.length);
            output.writeBytes(bytes);
            return this;
        }

        /** Adds a collection with an explicit boundary and element count. */
        public Encoder addCollection(Iterable<?> values) {
            if (values == null) {
                output.write(2);
                writeInt(-1);
                return this;
            }
            List<Object> elements = new ArrayList<>();
            values.forEach(value -> elements.add(value));
            output.write(2);
            writeInt(elements.size());
            elements.forEach(this::add);
            return this;
        }

        public String digest() {
            return TokenDigests.sha256Bytes(output.toByteArray());
        }

        private void writeInt(int value) {
            output.write((value >>> 24) & 0xff);
            output.write((value >>> 16) & 0xff);
            output.write((value >>> 8) & 0xff);
            output.write(value & 0xff);
        }
    }
}
