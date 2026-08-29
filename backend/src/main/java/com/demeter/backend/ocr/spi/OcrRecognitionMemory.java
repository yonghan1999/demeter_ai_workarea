package com.demeter.backend.ocr.spi;

import java.util.List;

/** Tenant-scoped historical values used only as OCR disambiguation hints. */
public record OcrRecognitionMemory(
        List<String> shippers,
        List<String> origins,
        List<String> destinations,
        List<String> vehicleCargo) {

    public OcrRecognitionMemory {
        shippers = immutable(shippers);
        origins = immutable(origins);
        destinations = immutable(destinations);
        vehicleCargo = immutable(vehicleCargo);
    }

    public static OcrRecognitionMemory empty() {
        return new OcrRecognitionMemory(List.of(), List.of(), List.of(), List.of());
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
