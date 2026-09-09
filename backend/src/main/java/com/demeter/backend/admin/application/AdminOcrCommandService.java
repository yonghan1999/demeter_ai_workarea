package com.demeter.backend.admin.application;

import static com.demeter.backend.admin.application.AdminCommandInputs.requireReason;

import com.demeter.backend.audit.application.AuditService;
import com.demeter.backend.common.chain.BusinessChain;
import com.demeter.backend.common.chain.BusinessChainExecutionMode;
import com.demeter.backend.common.chain.BusinessContext;
import com.demeter.backend.common.chain.BusinessHandler;
import com.demeter.backend.common.error.BusinessRuleException;
import com.demeter.backend.common.error.ResourceNotFoundException;
import com.demeter.backend.common.idempotency.CanonicalValues;
import com.demeter.backend.common.idempotency.IdempotencyKeys;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import com.demeter.backend.ocr.domain.OcrTask;
import com.demeter.backend.ocr.infrastructure.OcrTaskRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnRuntimeRole(RuntimeRole.API)
public class AdminOcrCommandService {
    private static final String OCR_RETRY = "admin.ocr.retry";

    private final OcrTaskRepository tasks;
    private final AuditService audit;
    private final Clock clock;
    private final AdminCommandRunner runner;
    private final BusinessChain<RetryContext, Boolean> retryChain;

    public AdminOcrCommandService(OcrTaskRepository tasks, AuditService audit, Clock clock,
            AdminCommandRunner runner) {
        this.tasks = tasks;
        this.audit = audit;
        this.clock = clock;
        this.runner = runner;
        this.retryChain = buildRetryChain();
    }

    public void retry(String publicId, String reason, String idempotencyKey) {
        runner.execute(retryChain, new RetryContext(publicId, reason, idempotencyKey));
    }

    private BusinessChain<RetryContext, Boolean> buildRetryChain() {
        return BusinessChain.of(
                OCR_RETRY,
                BusinessChainExecutionMode.ATOMIC_DATABASE,
                List.of(
                        BusinessHandler.named("normalize-command", context -> {
                            context.reason = requireReason(context.requestedReason, "重试原因不能为空");
                            context.key = IdempotencyKeys.require(context.requestedKey);
                            context.hash = CanonicalValues.sha256(context.publicId, context.reason);
                        }),
                        BusinessHandler.named("reserve-idempotency", context -> {
                            if (runner.reserve(context, clock.instant()) == AdminCommandRunner.Reservation.REPLAY) {
                                context.halt();
                            }
                        }),
                        BusinessHandler.named("load-task-for-update", context -> context.task = tasks
                                .findByPublicIdForUpdate(context.publicId)
                                .orElseThrow(() -> new ResourceNotFoundException("OCR task does not exist"))),
                        BusinessHandler.named("retry-task", context -> {
                            try {
                                context.task.retry(clock.instant());
                            } catch (IllegalStateException exception) {
                                throw new BusinessRuleException(exception.getMessage());
                            }
                        }),
                        BusinessHandler.named("persist-task", context -> tasks.saveAndFlush(context.task)),
                        BusinessHandler.named("write-audit", context -> audit.recordSystem(
                                context.task.getTenantId(),
                                "ADMIN_OCR_TASK_RETRIED",
                                "OCR_TASK",
                                context.task.getPublicId(),
                                Map.of("reason", context.reason)))),
                ignored -> Boolean.TRUE);
    }

    private static final class RetryContext extends BusinessContext implements AdminIdempotentCommand {
        private final String publicId;
        private final String requestedReason;
        private final String requestedKey;
        private String reason;
        private String key;
        private String hash;
        private OcrTask task;

        private RetryContext(String publicId, String requestedReason, String requestedKey) {
            this.publicId = publicId;
            this.requestedReason = requestedReason;
            this.requestedKey = requestedKey;
        }

        @Override
        public String operationName() {
            return OCR_RETRY;
        }

        @Override
        public String idempotencyKey() {
            return key;
        }

        @Override
        public String requestHash() {
            return hash;
        }
    }
}
