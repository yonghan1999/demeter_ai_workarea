package com.demeter.backend.admin.application;

import com.demeter.backend.admin.infrastructure.AdminCommandReplay;
import com.demeter.backend.admin.infrastructure.AdminCommandReplayRepository;
import com.demeter.backend.common.chain.BusinessChain;
import com.demeter.backend.common.chain.BusinessChainExecutor;
import com.demeter.backend.common.chain.BusinessContext;
import com.demeter.backend.common.error.ConflictException;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnRuntimeRole(RuntimeRole.API)
public class AdminCommandRunner {
    enum Reservation {
        RESERVED,
        REPLAY
    }

    private final AdminCommandReplayRepository replays;
    private final BusinessChainExecutor executor;
    private final TransactionTemplate transaction;

    public AdminCommandRunner(AdminCommandReplayRepository replays, BusinessChainExecutor executor,
            PlatformTransactionManager transactionManager) {
        this.replays = replays;
        this.executor = executor;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    <C extends BusinessContext & AdminIdempotentCommand> void execute(
            BusinessChain<C, Boolean> chain, C context) {
        try {
            transaction.executeWithoutResult(status -> executor.execute(chain, context));
        } catch (DataIntegrityViolationException race) {
            transaction.executeWithoutResult(status -> replays
                    .findByOperationNameAndIdempotencyKey(context.operationName(), context.idempotencyKey())
                    .ifPresentOrElse(
                            replay -> verifyReplay(replay, context.requestHash()),
                            () -> { throw race; }));
        }
    }

    Reservation reserve(AdminIdempotentCommand command, Instant createdAt) {
        Optional<AdminCommandReplay> existing = replays.findByOperationNameAndIdempotencyKey(
                command.operationName(), command.idempotencyKey());
        if (existing.isPresent()) {
            verifyReplay(existing.get(), command.requestHash());
            return Reservation.REPLAY;
        }
        replays.saveAndFlush(new AdminCommandReplay(
                command.operationName(), command.idempotencyKey(), command.requestHash(), createdAt));
        return Reservation.RESERVED;
    }

    private static void verifyReplay(AdminCommandReplay replay, String requestHash) {
        if (!replay.getRequestHash().equals(requestHash)) {
            throw new ConflictException("The Idempotency-Key was already used for a different request");
        }
    }
}
