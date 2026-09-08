package com.demeter.backend.admin.application;

import com.demeter.backend.admin.application.AdminRows.MaintenanceRunRow;
import com.demeter.backend.common.chain.BusinessChain;
import com.demeter.backend.common.chain.BusinessChainExecutionMode;
import com.demeter.backend.common.chain.BusinessChainExecutor;
import com.demeter.backend.common.chain.BusinessContext;
import com.demeter.backend.common.chain.BusinessHandler;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import com.demeter.backend.maintenance.domain.MaintenanceRun;
import com.demeter.backend.maintenance.infrastructure.MaintenanceRunRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnRuntimeRole(RuntimeRole.API)
public class AdminMaintenanceQueryService {
    private static final int MAX_RUNS = 50;

    private final MaintenanceRunRepository runs;
    private final BusinessChainExecutor executor;
    private final BusinessChain<MaintenanceContext, List<MaintenanceRunRow>> chain;

    public AdminMaintenanceQueryService(MaintenanceRunRepository runs, BusinessChainExecutor executor) {
        this.runs = runs;
        this.executor = executor;
        this.chain = BusinessChain.of("admin.maintenance-runs", BusinessChainExecutionMode.READ_ONLY,
                List.of(
                        BusinessHandler.named("validate-limit", context -> {
                            if (context.limit < 1 || context.limit > MAX_RUNS) {
                                throw new IllegalArgumentException("Invalid maintenance run limit");
                            }
                        }),
                        BusinessHandler.named("query", context -> context.result = runs
                                .findAllByRunTypeOrderByStartedAtDescIdDesc("system.maintenance",
                                        PageRequest.of(0, context.limit, Sort.by(Sort.Direction.DESC, "startedAt")
                                                .and(Sort.by(Sort.Direction.DESC, "id"))))
                                .stream().map(MaintenanceRunRow::from).toList())),
                context -> context.result);
    }

    @Transactional(readOnly = true)
    public List<MaintenanceRunRow> recentRuns() {
        return executor.execute(chain, new MaintenanceContext(MAX_RUNS));
    }

    private static final class MaintenanceContext extends BusinessContext {
        private final int limit;
        private List<MaintenanceRunRow> result;

        private MaintenanceContext(int limit) {
            this.limit = limit;
        }
    }
}
