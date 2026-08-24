package com.demeter.backend.ocr.application;

import com.demeter.backend.ocr.domain.OcrRecognitionResult;
import com.demeter.backend.ocr.spi.HandwrittenBillOcrProvider;
import com.demeter.backend.ocr.spi.OcrRecognitionRequest;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.springframework.stereotype.Component;

@Component
public class ResilientOcrRecognitionClient {

    @CircuitBreaker(name = "ocrProvider")
    @TimeLimiter(name = "ocrProvider")
    @Bulkhead(name = "ocrProvider", type = Bulkhead.Type.THREADPOOL)
    public CompletionStage<OcrRecognitionResult> recognize(
            HandwrittenBillOcrProvider provider,
            OcrRecognitionRequest request) {
        return CompletableFuture.completedFuture(provider.recognize(request));
    }
}
