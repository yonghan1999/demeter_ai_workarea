package com.demeter.backend.ocr.application;

import com.demeter.backend.audit.application.AuditService;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import com.demeter.backend.common.chain.BusinessChain;
import com.demeter.backend.common.chain.BusinessChainExecutor;
import com.demeter.backend.common.chain.BusinessContext;
import com.demeter.backend.common.chain.BusinessHandler;
import com.demeter.backend.common.chain.BusinessChainExecutionMode;
import com.demeter.backend.ocr.domain.OcrDocument;
import com.demeter.backend.ocr.domain.OcrRecognitionResult;
import com.demeter.backend.ocr.domain.OcrTask;
import com.demeter.backend.ocr.domain.OcrTaskStatus;
import com.demeter.backend.ocr.infrastructure.OcrTaskRepository;
import com.demeter.backend.ocr.infrastructure.OcrWorkerProperties;
import com.demeter.backend.ocr.infrastructure.OcrResultProperties;
import com.demeter.backend.ocr.infrastructure.OcrTaskMetrics;
import com.demeter.backend.ocr.spi.HandwrittenBillOcrProvider;
import com.demeter.backend.ocr.spi.OcrDocumentStorage;
import com.demeter.backend.ocr.spi.OcrRecognitionRequest;
import com.demeter.backend.ocr.spi.OcrProviderFailure;
import com.demeter.backend.ocr.spi.OcrRecognitionMemory;
import com.demeter.backend.bill.infrastructure.BillRepository;
import com.demeter.backend.security.DemeterPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnRuntimeRole({RuntimeRole.WORKER})
@ConditionalOnProperty(prefix = "demeter.ocr.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OcrTaskWorker {

    private final OcrTaskRepository taskRepository;
    private final BillRepository billRepository;
    private final OcrDocumentStorage storage;
    private final ObjectProvider<HandwrittenBillOcrProvider> provider;
    private final ResilientOcrRecognitionClient recognitionClient;
    private final AuditService auditService;
    private final BusinessChainExecutor chainExecutor;
    private final TransactionTemplate transactionTemplate;
    private final OcrWorkerProperties properties;
    private final OcrResultProperties resultProperties;
    private final ObjectMapper objectMapper;
    private final OcrTaskMetrics metrics;
    private final Clock clock;
    private final BusinessChain<ProcessTaskContext, Boolean> processChain;

    public OcrTaskWorker(
            OcrTaskRepository taskRepository,
            BillRepository billRepository,
            OcrDocumentStorage storage,
            ObjectProvider<HandwrittenBillOcrProvider> provider,
            ResilientOcrRecognitionClient recognitionClient,
            AuditService auditService,
            BusinessChainExecutor chainExecutor,
            PlatformTransactionManager transactionManager,
            OcrWorkerProperties properties,
            OcrResultProperties resultProperties,
            ObjectMapper objectMapper,
            OcrTaskMetrics metrics,
            Clock clock) {
        this.taskRepository = taskRepository;
        this.billRepository = billRepository;
        this.storage = storage;
        this.provider = provider;
        this.recognitionClient = recognitionClient;
        this.auditService = auditService;
        this.chainExecutor = chainExecutor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.resultProperties = resultProperties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.clock = clock;
        this.processChain = buildProcessChain();
    }

    @Scheduled(
            fixedDelayString = "${demeter.ocr.worker.fixed-delay:2s}",
            initialDelayString = "${demeter.ocr.worker.initial-delay:0s}")
    public void poll() {
        int processed = 0;
        while (processed < properties.maxTasksPerPoll()
                && Boolean.TRUE.equals(chainExecutor.execute(processChain, new ProcessTaskContext()))) {
            processed += 1;
        }
    }

    private BusinessChain<ProcessTaskContext, Boolean> buildProcessChain() {
        return BusinessChain.of(
                "ocr.task.process",
                BusinessChainExecutionMode.EXTERNAL_IO,
                List.of(
                        BusinessHandler.named("claim-task", this::claimTask),
                        BusinessHandler.named("load-document", context -> {
                            if (context.claim == null || context.claim.exhausted()) {
                                return;
                            }
                            try {
                                context.document = storage.load(
                                        context.claim.storageKey(),
                                        context.claim.originalFilename(),
                                        context.claim.contentType());
                            } catch (RuntimeException exception) {
                                context.fail(
                                        "OCR_DOCUMENT_UNAVAILABLE",
                                        "The OCR image could not be loaded",
                                        false,
                                        OcrTaskMetrics.FailureCategory.DOCUMENT_UNAVAILABLE);
                            }
                        }),
                        BusinessHandler.named("resolve-provider", context -> {
                            if (context.claim == null || context.claim.exhausted() || context.failureCode != null) {
                                return;
                            }
                            context.provider = provider.getIfAvailable();
                            if (context.provider == null) {
                                context.fail(
                                        "OCR_NOT_CONFIGURED",
                                        "OCR recognition provider is not configured",
                                        true,
                                        OcrTaskMetrics.FailureCategory.NOT_CONFIGURED);
                            }
                        }),
                        BusinessHandler.named("load-history-memory", context -> {
                            if (context.claim == null || context.claim.exhausted() || context.failureCode != null) {
                                return;
                            }
                            context.memory = loadHistoryMemory(context.claim.tenantId());
                        }),
                        BusinessHandler.named("recognize-document", context -> {
                            if (context.claim == null || context.claim.exhausted() || context.failureCode != null) {
                                return;
                            }
                            try {
                                context.result = recognitionClient
                                        .recognize(
                                                context.provider,
                                                new OcrRecognitionRequest(
                                                        context.claim.publicId(),
                                                        context.claim.attempt(),
                                                        context.document,
                                                        context.memory))
                                        .toCompletableFuture()
                                        .join();
                            } catch (RuntimeException exception) {
                                classifyProviderFailure(context, exception);
                                return;
                            }
                            if (context.result == null) {
                                context.fail(
                                        "OCR_EMPTY_RESPONSE",
                                        "OCR provider returned an empty result",
                                        false,
                                        OcrTaskMetrics.FailureCategory.EMPTY_RESPONSE);
                                return;
                            }
                            try {
                                validateResult(context.result);
                                context.resultJson = serializeResult(context.result);
                            } catch (RuntimeException exception) {
                                context.fail(
                                        "OCR_INVALID_RESPONSE",
                                        "OCR provider returned an invalid result",
                                        true,
                                        OcrTaskMetrics.FailureCategory.INVALID_RESPONSE);
                            }
                        }),
                        BusinessHandler.named("persist-outcome", this::persistOutcome),
                        BusinessHandler.named("report-work", context -> context.didWork = context.claim != null)),
                context -> context.didWork);
    }

    private OcrRecognitionMemory loadHistoryMemory(long tenantId) {
        org.springframework.data.domain.Pageable limit = org.springframework.data.domain.PageRequest.of(0, 100);
        return new OcrRecognitionMemory(
                billRepository.findShipperNames(tenantId, "", limit),
                billRepository.findOriginNames(tenantId, limit),
                billRepository.findDestinationNames(tenantId, limit),
                billRepository.findVehicleCargoNames(tenantId, limit));
    }

    private void claimTask(ProcessTaskContext context) {
        context.claim = transactionTemplate.execute(status -> {
            OcrTask task = taskRepository.findNextClaimableForUpdate(clock.instant()).orElse(null);
            if (task == null) {
                return null;
            }
            if (task.hasExhaustedAttempts()) {
                task.failPermanently(
                        "OCR_ATTEMPTS_EXHAUSTED",
                        "OCR task exhausted its attempts while recovering an expired lease",
                        clock.instant());
                taskRepository.saveAndFlush(task);
                auditService.record(
                        new DemeterPrincipal(
                                task.getCreatedBy(),
                                task.getTenantId(),
                                "system:ocr-worker",
                                "OCR Worker"),
                        "OCR_TASK_FAILED",
                        "OCR_TASK",
                        task.getPublicId(),
                        Map.of(
                                "errorCode", "OCR_ATTEMPTS_EXHAUSTED",
                                "attempt", task.getAttemptCount()));
                return TaskClaim.exhausted(task);
            }
            task.claim(clock.instant(), properties.leaseDuration());
            taskRepository.saveAndFlush(task);
            return new TaskClaim(
                    task.getId(),
                    task.getVersion(),
                    task.getPublicId(),
                    task.getTenantId(),
                    task.getCreatedBy(),
                    task.getStorageKey(),
                    task.getOriginalFilename(),
                    task.getContentType(),
                    task.getAttemptCount(),
                    false);
        });
        if (context.claim != null && context.claim.exhausted()) {
            metrics.recordOutcome(OcrTaskMetrics.WorkOutcome.ATTEMPTS_EXHAUSTED);
            metrics.recordFailure(OcrTaskMetrics.FailureCategory.ATTEMPTS_EXHAUSTED);
        }
    }

    private void persistOutcome(ProcessTaskContext context) {
        if (context.claim == null || context.claim.exhausted()) {
            return;
        }
        OcrTaskMetrics.WorkOutcome outcome = transactionTemplate.execute(status -> {
            OcrTask task = taskRepository.findByIdForUpdate(context.claim.id()).orElse(null);
            if (task == null
                    || task.getStatus() != OcrTaskStatus.PROCESSING
                    || task.getVersion() != context.claim.version()) {
                return OcrTaskMetrics.WorkOutcome.LEASE_LOST;
            }
            DemeterPrincipal actor = new DemeterPrincipal(
                    context.claim.createdBy(),
                    context.claim.tenantId(),
                    "system:ocr-worker",
                    "OCR Worker");
            if (context.failureCode != null) {
                if (context.permanentFailure) {
                    task.failPermanently(context.failureCode, context.failureMessage, clock.instant());
                } else {
                    task.fail(
                            context.failureCode,
                            context.failureMessage,
                            clock.instant(),
                            properties.retryDelayForAttempt(task.getAttemptCount()));
                }
                auditService.record(
                        actor,
                        task.getStatus() == OcrTaskStatus.FAILED
                                ? "OCR_TASK_FAILED"
                                : "OCR_TASK_RETRY_SCHEDULED",
                        "OCR_TASK",
                        task.getPublicId(),
                        Map.of(
                                "errorCode", context.failureCode,
                                "attempt", task.getAttemptCount()));
            } else {
                task.succeed(context.result, context.resultJson, clock.instant());
                auditService.record(
                        actor,
                        "OCR_TASK_SUCCEEDED",
                        "OCR_TASK",
                        task.getPublicId(),
                        Map.of("billCount", context.result.bills().size()));
            }
            taskRepository.saveAndFlush(task);
            if (task.getStatus() == OcrTaskStatus.SUCCEEDED) {
                return OcrTaskMetrics.WorkOutcome.SUCCEEDED;
            }
            if (task.getStatus() == OcrTaskStatus.RETRYING) {
                return OcrTaskMetrics.WorkOutcome.RETRY_SCHEDULED;
            }
            return OcrTaskMetrics.WorkOutcome.FAILED;
        });
        if (outcome != null) {
            metrics.recordOutcome(outcome);
            if (context.failureCategory != null && outcome != OcrTaskMetrics.WorkOutcome.LEASE_LOST) {
                metrics.recordFailure(context.failureCategory);
            }
        }
    }

    private static void classifyProviderFailure(ProcessTaskContext context, RuntimeException exception) {
        OcrProviderFailure failure = findProviderFailure(exception);
        if (failure == null) {
            context.fail(
                    "OCR_INTERNAL_ERROR",
                    "OCR processing failed unexpectedly",
                    true,
                    OcrTaskMetrics.FailureCategory.INTERNAL_ERROR);
            return;
        }
        if (failure.kind() == OcrProviderFailure.Kind.TRANSIENT) {
            context.fail(
                    "OCR_PROVIDER_RETRYABLE",
                    "OCR provider is temporarily unavailable",
                    false,
                    OcrTaskMetrics.FailureCategory.PROVIDER_RETRYABLE);
            return;
        }
        switch (failure.kind()) {
            case INVALID_INPUT -> context.fail(
                    "OCR_PROVIDER_INVALID_INPUT",
                    "The OCR provider rejected the image input",
                    true,
                    OcrTaskMetrics.FailureCategory.PROVIDER_INVALID_INPUT);
            case QUOTA_EXCEEDED -> context.fail(
                    "OCR_PROVIDER_QUOTA_EXCEEDED",
                    "OCR provider quota is unavailable",
                    true,
                    OcrTaskMetrics.FailureCategory.PROVIDER_QUOTA_EXCEEDED);
            case PERMANENT -> context.fail(
                    "OCR_PROVIDER_PERMANENT",
                    "The OCR provider permanently rejected the request",
                    true,
                    OcrTaskMetrics.FailureCategory.PROVIDER_PERMANENT);
            case TRANSIENT -> throw new IllegalStateException("Transient OCR failures are handled above");
        }
    }

    private static OcrProviderFailure findProviderFailure(Throwable exception) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current instanceof OcrProviderFailure providerFailure) {
                return providerFailure;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return null;
    }

    private String serializeResult(OcrRecognitionResult result) {
        try {
            String json = objectMapper.writeValueAsString(result);
            if (json.getBytes(StandardCharsets.UTF_8).length > resultProperties.maxJsonBytes()) {
                throw new IllegalStateException("OCR result exceeds the configured size limit");
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize OCR result", exception);
        }
    }

    private void validateResult(OcrRecognitionResult result) {
        if (result.provider() == null || result.provider().isBlank() || result.provider().length() > 80) {
            throw new IllegalArgumentException("OCR provider name is invalid");
        }
        if (result.providerRequestId() != null && result.providerRequestId().length() > 160) {
            throw new IllegalArgumentException("OCR provider request id is too long");
        }
        if (result.bills() == null || result.bills().size() > resultProperties.maxBills()) {
            throw new IllegalArgumentException("OCR result contains too many bills");
        }
        result.bills().forEach(candidate -> {
            if (candidate == null) {
                throw new IllegalArgumentException("OCR result contains an empty bill");
            }
            requireLength(candidate.externalId(), 160, "external id");
            requireLength(candidate.code(), 64, "bill code");
            requireLength(candidate.shipper(), 120, "shipper");
            requireLength(candidate.vehicleCargo(), 120, "vehicle/cargo");
            requireLength(candidate.from(), 64, "origin");
            requireLength(candidate.to(), 64, "destination");
            if (candidate.amount() != null
                    && (candidate.amount().signum() <= 0
                    || candidate.amount().compareTo(new BigDecimal("99999999.99")) > 0
                    || candidate.amount().scale() > 2)) {
                throw new IllegalArgumentException("OCR bill amount is invalid");
            }
            validateConfidence(candidate.confidence());
            if (candidate.fieldConfidences() != null) {
                if (candidate.fieldConfidences().size() > 50) {
                    throw new IllegalArgumentException("OCR result contains too many field confidences");
                }
                candidate.fieldConfidences().forEach((field, confidence) -> {
                    requireLength(field, 80, "confidence field");
                    validateConfidence(confidence);
                });
            }
        });
    }

    private static void validateConfidence(BigDecimal confidence) {
        if (confidence != null
                && (confidence.compareTo(BigDecimal.ZERO) < 0
                || confidence.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("OCR confidence must be between zero and one");
        }
    }

    private static void requireLength(String value, int maxLength, String field) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException("OCR " + field + " is too long");
        }
    }

    private record TaskClaim(
            long id,
            long version,
            String publicId,
            long tenantId,
            long createdBy,
            String storageKey,
            String originalFilename,
            String contentType,
            int attempt,
            boolean exhausted) {

        private static TaskClaim exhausted(OcrTask task) {
            return new TaskClaim(
                    task.getId(),
                    task.getVersion(),
                    task.getPublicId(),
                    task.getTenantId(),
                    task.getCreatedBy(),
                    task.getStorageKey(),
                    task.getOriginalFilename(),
                    task.getContentType(),
                    task.getAttemptCount(),
                    true);
        }
    }

    private static final class ProcessTaskContext extends BusinessContext {
        private TaskClaim claim;
        private OcrDocument document;
        private HandwrittenBillOcrProvider provider;
        private OcrRecognitionResult result;
        private OcrRecognitionMemory memory = OcrRecognitionMemory.empty();
        private String resultJson;
        private String failureCode;
        private String failureMessage;
        private boolean permanentFailure;
        private OcrTaskMetrics.FailureCategory failureCategory;
        private boolean didWork;

        private void fail(
                String code,
                String message,
                boolean permanent,
                OcrTaskMetrics.FailureCategory category) {
            this.failureCode = code;
            this.failureMessage = message;
            this.permanentFailure = permanent;
            this.failureCategory = category;
        }
    }
}
