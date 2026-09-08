package com.demeter.backend.admin.application;

import com.demeter.backend.admin.application.AdminRows.AuditDetailRow;
import com.demeter.backend.audit.domain.AuditEvent;
import com.demeter.backend.audit.infrastructure.AuditEventRepository;
import com.demeter.backend.common.chain.BusinessChain;
import com.demeter.backend.common.chain.BusinessChainExecutionMode;
import com.demeter.backend.common.chain.BusinessChainExecutor;
import com.demeter.backend.common.chain.BusinessContext;
import com.demeter.backend.common.chain.BusinessHandler;
import com.demeter.backend.common.error.ResourceNotFoundException;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnRuntimeRole(RuntimeRole.API)
public class AdminAuditDetailService {
    private final AuditEventRepository audit;
    private final BusinessChainExecutor executor;
    private final BusinessChain<AuditContext, AuditDetailRow> chain;

    public AdminAuditDetailService(AuditEventRepository audit, BusinessChainExecutor executor) {
        this.audit = audit;
        this.executor = executor;
        this.chain = BusinessChain.of("admin.audit.detail", BusinessChainExecutionMode.READ_ONLY,
                java.util.List.of(
                        BusinessHandler.named("validate-id", context -> {
                            if (context.auditId < 1) {
                                throw new IllegalArgumentException("Invalid audit id");
                            }
                        }),
                        BusinessHandler.named("load", context -> context.result = load(context.auditId))),
                context -> context.result);
    }

    @Transactional(readOnly = true)
    public AuditDetailRow find(long id) {
        return executor.execute(chain, new AuditContext(id));
    }

    private AuditDetailRow load(long id) {
        AuditEvent event = audit.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Audit event " + id + " does not exist"));
        return AuditDetailRow.from(event);
    }

    private static final class AuditContext extends BusinessContext {
        private final long auditId;
        private AuditDetailRow result;

        private AuditContext(long auditId) {
            this.auditId = auditId;
        }
    }
}
