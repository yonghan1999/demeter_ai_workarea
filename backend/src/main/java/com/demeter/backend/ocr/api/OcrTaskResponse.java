package com.demeter.backend.ocr.api;

import com.demeter.backend.ocr.domain.OcrRecognitionResult;
import com.demeter.backend.ocr.domain.OcrTask;
import com.demeter.backend.ocr.domain.OcrTaskStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;

public record OcrTaskResponse(
        String id,
        OcrTaskStatus status,
        String originalFilename,
        String contentType,
        long sizeBytes,
        String provider,
        String providerRequestId,
        String currency,
        OcrRecognitionResult result,
        int attemptCount,
        int maxAttempts,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static OcrTaskResponse from(OcrTask task, ObjectMapper objectMapper) {
        return new OcrTaskResponse(
                task.getPublicId(),
                task.getStatus(),
                task.getOriginalFilename(),
                task.getContentType(),
                task.getSizeBytes(),
                task.getProvider(),
                task.getProviderRequestId(),
                "CNY",
                deserialize(task.getResultJson(), objectMapper),
                task.getAttemptCount(),
                task.getMaxAttempts(),
                task.getLastErrorCode(),
                task.getLastErrorMessage(),
                task.getStartedAt(),
                task.getCompletedAt(),
                task.getCreatedAt(),
                task.getUpdatedAt());
    }

    public static OcrTaskResponse summary(OcrTask task) {
        return new OcrTaskResponse(
                task.getPublicId(),
                task.getStatus(),
                task.getOriginalFilename(),
                task.getContentType(),
                task.getSizeBytes(),
                task.getProvider(),
                task.getProviderRequestId(),
                "CNY",
                null,
                task.getAttemptCount(),
                task.getMaxAttempts(),
                task.getLastErrorCode(),
                task.getLastErrorMessage(),
                task.getStartedAt(),
                task.getCompletedAt(),
                task.getCreatedAt(),
                task.getUpdatedAt());
    }

    private static OcrRecognitionResult deserialize(String json, ObjectMapper objectMapper) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, OcrRecognitionResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not deserialize OCR task result", exception);
        }
    }
}
