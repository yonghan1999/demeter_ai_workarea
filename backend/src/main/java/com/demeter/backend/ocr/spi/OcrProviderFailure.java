package com.demeter.backend.ocr.spi;

/**
 * Stable failure contract for an OCR provider adapter.
 *
 * <p>Adapters must not expose vendor response bodies or credentials through the task queue. They
 * should translate vendor errors into one of these categories and keep the original exception as
 * the cause for logs only.</p>
 */
public final class OcrProviderFailure extends RuntimeException {

    public enum Kind {
        TRANSIENT,
        INVALID_INPUT,
        QUOTA_EXCEEDED,
        PERMANENT
    }

    private final Kind kind;

    public OcrProviderFailure(Kind kind, String message) {
        super(message);
        this.kind = requireKind(kind);
    }

    public OcrProviderFailure(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = requireKind(kind);
    }

    public Kind kind() {
        return kind;
    }

    private static Kind requireKind(Kind value) {
        if (value == null) {
            throw new IllegalArgumentException("OCR provider failure kind must not be null");
        }
        return value;
    }
}
